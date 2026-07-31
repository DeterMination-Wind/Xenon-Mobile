// Copyright (c) 2020-2022 ByteDance, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#ifndef BYTEDANCE_BYTEHOOK_H
#define BYTEDANCE_BYTEHOOK_H 1

#include <stdbool.h>
#include <stdint.h>

#define BYTEHOOK_VERSION "1.0.10"

#define BYTEHOOK_STATUS_CODE_OK 0
#define BYTEHOOK_MODE_AUTOMATIC 0
#define BYTEHOOK_MODE_MANUAL 1

#ifdef __cplusplus
extern "C" {
#endif

typedef void *bytehook_stub_t;

typedef void (*bytehook_hooked_t)(bytehook_stub_t task_stub, int status_code, const char *caller_path_name,
                                  const char *sym_name, void *new_func, void *prev_func, void *arg);

int bytehook_init(int mode, bool debug);

bytehook_stub_t bytehook_hook_all(const char *callee_path_name, const char *sym_name, void *new_func,
                                  bytehook_hooked_t hooked, void *hooked_arg);

void *bytehook_get_prev_func(void *func);
int bytehook_get_mode(void);
void bytehook_pop_stack(void *return_address);

#ifdef __cplusplus
}
#endif

#endif
