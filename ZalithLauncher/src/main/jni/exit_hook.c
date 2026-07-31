//
// Created by maks on 15.01.2025.
//

#include <jni.h>
#include <unistd.h>
#include <stdbool.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdlib.h>
#include "stdio_is.h"

static _Atomic bool exit_tripped = false;

typedef void (*exit_func)(int);
typedef bytehook_stub_t (*bytehook_hook_all_func)(const char *callee_path_name, const char *sym_name, void *new_func,
                                                  bytehook_hooked_t hooked, void *hooked_arg);
typedef int (*bytehook_init_func)(int mode, bool debug);
typedef void *(*bytehook_get_prev_func_func)(void *func);
typedef int (*bytehook_get_mode_func)(void);
typedef void (*bytehook_pop_stack_func)(void *return_address);

static bytehook_get_prev_func_func bytehook_get_prev_func_p = NULL;
static bytehook_get_mode_func bytehook_get_mode_p = NULL;
static bytehook_pop_stack_func bytehook_pop_stack_p = NULL;

static void pop_bytehook_stack(void *return_address) {
    if(bytehook_get_mode_p != NULL
       && bytehook_pop_stack_p != NULL
       && bytehook_get_mode_p() == BYTEHOOK_MODE_AUTOMATIC) {
        bytehook_pop_stack_p(return_address);
    }
}

static void custom_exit(int code) {
    // If the exit was already done (meaning it is recursive or from a different thread), pass the call through
    if(exit_tripped) {
        if(bytehook_get_prev_func_p != NULL) {
            ((exit_func)bytehook_get_prev_func_p((void *)custom_exit))(code);
        }
        pop_bytehook_stack(__builtin_return_address(0));
        return;
    }
    exit_tripped = true;
    // Perform a nominal exit, as we expect.
    nominal_exit(code, false);
    pop_bytehook_stack(__builtin_return_address(0));
}

static void custom_atexit() {
    // Same as custom_exit, but without the code or the exit passthrough.
    if(exit_tripped) {
        return;
    }
    exit_tripped = true;
    nominal_exit(0, false);
}

static bool init_exit_hook() {
    void* bytehook_handle = dlopen("libbytehook.so", RTLD_NOW);
    if(bytehook_handle == NULL) {
        goto dlerror;
    }

    bytehook_hook_all_func bytehook_hook_all_p;
    bytehook_init_func bytehook_init_p;

    bytehook_hook_all_p = dlsym(bytehook_handle, "bytehook_hook_all");
    bytehook_init_p = dlsym(bytehook_handle, "bytehook_init");
    bytehook_get_prev_func_p = dlsym(bytehook_handle, "bytehook_get_prev_func");
    bytehook_get_mode_p = dlsym(bytehook_handle, "bytehook_get_mode");
    bytehook_pop_stack_p = dlsym(bytehook_handle, "bytehook_pop_stack");

    if(bytehook_hook_all_p == NULL
       || bytehook_init_p == NULL
       || bytehook_get_prev_func_p == NULL
       || bytehook_get_mode_p == NULL
       || bytehook_pop_stack_p == NULL) {
        goto dlerror;
    }
    int bhook_status = bytehook_init_p(BYTEHOOK_MODE_AUTOMATIC, false);
    if(bhook_status == BYTEHOOK_STATUS_CODE_OK) {
        bytehook_stub_t stub = bytehook_hook_all_p(NULL, "exit", &custom_exit, NULL, NULL);
        __android_log_print(ANDROID_LOG_INFO, "exit_hook", "Successfully initialized exit hook, stub=%p", stub);
        return true;
    } else {
        __android_log_print(ANDROID_LOG_INFO, "exit_hook", "bytehook_init failed (%i)", bhook_status);
        dlclose(bytehook_handle);
        return false;
    }

    dlerror:
    if(bytehook_handle != NULL) dlclose(bytehook_handle);
    __android_log_print(ANDROID_LOG_ERROR, "exit_hook", "Failed to load hook library: %s", dlerror());
    return false;
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_bridge_ZLBridge_initializeGameExitHook(JNIEnv *env, jclass clazz) {
    bool hookReady = init_exit_hook();
    if(!hookReady){
        // If we can't hook, register atexit(). This won't report a proper error code,
        // but it will prevent a SIGSEGV or a SIGABRT from the depths of Dalvik that happens
        // on exit().
        atexit(custom_atexit);
    }
}
