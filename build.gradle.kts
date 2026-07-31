// Top-level build file where you can add configuration options common to all sub-projects/modules.
import groovy.json.JsonSlurper
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp.plugin) apply false
    kotlin("plugin.serialization") version libs.versions.kotlin apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt) apply false
}

buildscript {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.buildkeys)
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        doFirst {
            val mainSourceRoot = projectDir.resolve("src/main")
            if (mainSourceRoot.resolve("assets").isDirectory) {
                classpath = classpath + files(mainSourceRoot)
            }
        }
    }
}

val xenonCatalogPath = providers.gradleProperty("xenonMobileCatalog")
    .orElse("catalog/xenon-mobile-catalog.json")
    .get()
val xenonCatalogFile = layout.projectDirectory.file(xenonCatalogPath).asFile
val xenonSourceLockFile = layout.projectDirectory.file("game-source-lock.json").asFile

/**
 * Removes only generated Kotlin/KSP state. This is useful after switching between
 * a native Windows path and the temporary drive used by the local build helpers.
 */
tasks.register<Delete>("cleanXenonGeneratedKotlinCaches") {
    val launcherBuild = project(":ZalithLauncher").layout.buildDirectory
    delete(
        launcherBuild.dir("kspCaches"),
        launcherBuild.dir("generated/ksp"),
        launcherBuild.dir("kotlin"),
        launcherBuild.dir("intermediates/built_in_kotlinc")
    )
}

fun Any.asStringField(name: String, artifactId: String): String =
    (this as? Map<*, *>)?.get(name)?.toString()?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Catalog artifact $artifactId is missing $name")

tasks.register("validateXenonMobileCatalog") {
    group = "verification"
    description = "Validate Xenon Mobile catalog identity keys, source pins and release metadata."
    inputs.file(xenonCatalogFile)
    doLast {
        val root = JsonSlurper().parse(xenonCatalogFile) as? Map<*, *>
            ?: throw GradleException("Catalog root must be a JSON object")
        val schema = (root["schemaVersion"] as? Number)?.toInt()
            ?: throw GradleException("Catalog schemaVersion is missing")
        if (schema != 1) throw GradleException("Unsupported catalog schema: $schema")

        val artifacts = (root["artifacts"] as? List<*>) ?: emptyList<Any?>()
        val keys = HashSet<String>()
        artifacts.forEach { raw ->
            val artifact = raw as? Map<*, *> ?: throw GradleException("Catalog artifact must be an object")
            val id = artifact.asStringField("id", "<unknown>")
            val variant = artifact.asStringField("variant", id)
            if (variant !in setOf("vanilla", "be", "mindustryx")) {
                throw GradleException("Artifact $id has an unsupported variant")
            }
            val backend = artifact.asStringField("backend", id).lowercase()
            if (backend !in setOf("apk", "jar")) {
                throw GradleException("Artifact $id has an unsupported backend")
            }
            val slot = (artifact["slot"] as? Number)?.toInt()
            val key = if (backend == "apk") {
                if (slot == null) throw GradleException("APK artifact $id is missing slot")
                "$variant:apk:$slot"
            } else {
                if (slot != null) throw GradleException("JAR artifact $id must not have slot")
                "$variant:jar"
            }
            if (!keys.add(key)) throw GradleException("Duplicate catalog identity key: $key")
            val urls = artifact["urls"] as? List<*> ?: throw GradleException("Artifact $id has no urls")
            if (urls.isEmpty() || urls.any { !it.toString().startsWith("https://") }) {
                throw GradleException("Artifact $id must use canonical HTTPS URLs")
            }
            artifact.asStringField("versionName", id)
            if ((artifact["build"] as? Number)?.toLong()?.let { it > 0L } != true) {
                throw GradleException("Artifact $id has invalid build")
            }
            artifact.asStringField("buildType", id)
            if (artifact.asStringField("nativeProfile", id) != "arm64-v8a") {
                throw GradleException("Artifact $id must target arm64-v8a")
            }
            val sha = artifact.asStringField("sha256", id)
            if (!sha.matches(Regex("[0-9a-fA-F]{64}"))) throw GradleException("Artifact $id has invalid sha256")
            val size = (artifact["size"] as? Number)?.toLong() ?: 0L
            if (size <= 0L) throw GradleException("Artifact $id has invalid size")
            if (artifact.asStringField("sourceRepo", id) !in setOf("Anuken/Mindustry", "TinyLake/MindustryX")) {
                throw GradleException("Artifact $id has an unsupported sourceRepo")
            }
            val commit = artifact.asStringField("sourceCommit", id)
            if (!commit.matches(Regex("[0-9a-fA-F]{40}"))) throw GradleException("Artifact $id has invalid sourceCommit")
            artifact.asStringField("releaseTag", id)
            if (backend == "apk") {
                val packageName = artifact.asStringField("packageName", id)
                val expected = "com.xenon.mobile.clone.$variant.slot$slot"
                if (packageName != expected) throw GradleException("Artifact $id has packageName $packageName, expected $expected")
                if ((artifact["versionCode"] as? Number)?.toLong()?.let { it > 0L } != true) {
                    throw GradleException("APK artifact $id has invalid versionCode")
                }
                val signatures = artifact["signatureSha256"] as? List<*>
                    ?: throw GradleException("APK artifact $id is missing signatureSha256")
                if (signatures.isEmpty() || signatures.any { !it.toString().matches(Regex("[0-9a-fA-F]{64}")) }) {
                    throw GradleException("APK artifact $id has invalid signatureSha256")
                }
            } else if (artifact["versionCode"] != null || artifact["packageName"] != null) {
                throw GradleException("JAR artifact $id contains APK-only metadata")
            }
        }
    }
}

tasks.register("validateGameSourceLock") {
    group = "verification"
    description = "Validate the reproducible Mindustry source lock used by release builds."
    inputs.file(xenonSourceLockFile)
    doLast {
        val root = JsonSlurper().parse(xenonSourceLockFile) as? Map<*, *>
            ?: throw GradleException("Source lock root must be a JSON object")
        if ((root["schemaVersion"] as? Number)?.toInt() != 1) {
            throw GradleException("Unsupported game source lock schema")
        }
        val defaults = root["defaults"] as? Map<*, *> ?: throw GradleException("Source lock defaults are missing")
        val expected = mapOf(
            "vanilla" to ("Anuken/Mindustry" to "20da6a38ab0874b5d971bffede3995efd3da5d70"),
            "be" to ("Anuken/Mindustry" to "20da6a38ab0874b5d971bffede3995efd3da5d70"),
            "mindustryx" to ("TinyLake/MindustryX" to "3b894f8518c1a36ec60f1f32af50a8b249d0f060")
        )
        expected.forEach { (variant, pin) ->
            val entry = defaults[variant] as? Map<*, *> ?: throw GradleException("Missing source lock for $variant")
            if (entry["sourceRepo"] != pin.first || entry["sourceCommit"] != pin.second) {
                throw GradleException("Source lock mismatch for $variant")
            }
        }
        val fixture = (root["fixtures"] as? Map<*, *>)?.get("serverList") as? Map<*, *>
            ?: throw GradleException("Server list fixture source lock is missing")
        if (fixture["sourceRepo"] != "Anuken/MindustryServerList" ||
            fixture["sourceCommit"] != "f297264dc24621753bc008a18e17b582fa5e3f65") {
            throw GradleException("Server list fixture source lock mismatch")
        }
    }
}

tasks.register("validateXenonMobileRelease") {
    group = "verification"
    description = "Validate all Xenon Mobile release metadata."
    dependsOn("validateXenonMobileCatalog", "validateGameSourceLock")
}
