import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
    id("kotlin-parcelize")
    id("com.movtery.buildkeys")
}

val zalithNamespace = "com.movtery.zalithlauncher"
val xenonMobilePackageName = "com.xenon.mobile"
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherShortName = project.findProperty("launcher_short_name") as? String ?: error("The \"launcher_short_name\" property is not set in gradle.properties.")
val launcherUrl = project.findProperty("url_home") as? String ?: error("The \"url_home\" property is not set in gradle.properties.")

val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

val defaultOAuthClientID = project.findProperty("oauth_client_id") as? String
val defaultStorePassword = getKeyFromLocal("DEBUG_STORE_PASSWORD", ".store_password.txt").trim()
val defaultKeyPassword = getKeyFromLocal("DEBUG_KEY_PASSWORD", ".key_password.txt").trim()
val defaultCurseForgeApiKey = project.findProperty("curseforge_api_key") as? String
val releaseStorePassword = getKeyFromLocal("STORE_PASSWORD", ".store_password.txt").trim()
val releaseKeyPassword = getKeyFromLocal("KEY_PASSWORD", ".key_password.txt").trim()
val debugKeystoreFile = getFileFromLocal("DEBUG_KEYSTORE_PATH", "ZalithLauncher/zalith_launcher_debug.jks")
val releaseKeystoreFile = getFileFromLocal("RELEASE_KEYSTORE_PATH", "ZalithLauncher/zalith_launcher.jks")
val debugKeyAlias = System.getenv("DEBUG_KEY_ALIAS")?.trim().orEmpty().ifBlank { "movtery_zalith_debug" }
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim().orEmpty().ifBlank { "movtery_zalith" }

val projectArch: String = System.getProperty("arch", "arm64")

fun abiForArch(arch: String): String? = when (arch) {
    "all" -> null
    "arm" -> "armeabi-v7a"
    "arm64" -> "arm64-v8a"
    "x86" -> "x86"
    "x86_64" -> "x86_64"
    else -> error("Unsupported arch '$arch'. Expected one of: all, arm, arm64, x86, x86_64.")
}

fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    val key = System.getenv(envKey)
    return key ?: fileName?.let {
        val file = File(rootDir, fileName)
        if (file.canRead() && file.isFile) file.readText().trim() else null
    } ?: default ?: run {
        logger.warn("BUILD: $envKey not set; related features may throw exceptions.")
        ""
    }
}

fun getFileFromLocal(envKey: String, defaultFileName: String): File {
    val configured = System.getenv(envKey)?.trim().orEmpty()
    if (configured.isBlank()) return File(rootDir, defaultFileName)
    val file = File(configured)
    return if (file.isAbsolute) file else File(rootDir, configured)
}

android {
    namespace = zalithNamespace
    compileSdk = 37

    signingConfigs {
        create("releaseBuild") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        create("debugBuild") {
            storeFile = debugKeystoreFile
            storePassword = defaultStorePassword
            keyAlias = debugKeyAlias
            keyPassword = defaultKeyPassword
        }
    }

    defaultConfig {
        applicationId = xenonMobilePackageName
        minSdk = 26
        targetSdk = 35
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseBuild")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debugBuild")
        }
    }

    splits {
        val abiFilter = abiForArch(projectArch) ?: return@splits
        abi {
            isEnable = true
            reset()
            include(abiFilter)
        }
    }

    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                afterEvaluate {
                    val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                    task.doLast {
                        val assetsDir = task.outputDir.get().asFile
                        val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                        val tag = "JREAssetsCleanup"
                        logger.lifecycle("[$tag] arch: $projectArch")
                        jreList.forEach { jreVersion ->
                            val runtimeDir = File("$assetsDir/runtimes/$jreVersion")
                            logger.lifecycle("[$tag] runtimeDir: ${runtimeDir.absolutePath}")
                            runtimeDir.listFiles()?.forEach {
                                if (projectArch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-$projectArch.tar.xz") {
                                    logger.lifecycle("[$tag] delete: $it : ${it.delete()}")
                                }
                            }
                        }
                    }
                }

                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    val baseName = "$launcherName-${if (variant.buildType == "release") launcherVersionName else "Debug-$launcherVersionName"}"
                    output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                }
            }
        }
    }
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

buildKeys {
    string("OAUTH_CLIENT_ID", getKeyFromLocal("OAUTH_CLIENT_ID", ".oauth_client_id.txt", defaultOAuthClientID), true)
    string("LAUNCHER_NAME", launcherAPPName, true)
    string("LAUNCHER_IDENTIFIER", launcherName, true)
    string("LAUNCHER_SHORT_NAME", launcherShortName, true)
    string("URL_HOME", launcherUrl, true)
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
    string("BUILD_ARCH", projectArch)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.nav3)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.ktor3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    implementation(libs.reorderable)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui)
    implementation(libs.richtext.ui.material3)
    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.dev.haze)
    implementation(libs.dev.haze.blur)
    //Project
    implementation(project(":LayerController"))
    implementation(project(":ColorPicker"))
    implementation(project(":Terracotta"))
    //Utils
    implementation(libs.bytehook)
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.okio)
    implementation(libs.okhttp)
    implementation(libs.ktor.http)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.minidns.hla)
    implementation(libs.toml4j)
    implementation(libs.maven.artifact)
    implementation(libs.mmkv)
    implementation(libs.fishnet)
    implementation(libs.process.phoenix)
    implementation(libs.lunarcalendar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    //Safe
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    //Support
    implementation(libs.proxy.client.android)
    //Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    //Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
