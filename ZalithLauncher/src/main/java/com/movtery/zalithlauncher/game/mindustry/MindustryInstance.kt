/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.mindustry

import java.io.File
import com.google.gson.GsonBuilder
import com.movtery.zalithlauncher.path.PathManager

/**
 * Persistent launcher metadata for one Mindustry instance.
 */
data class MindustryInstance(
    val id: String,
    val name: String = id,
    val backend: MindustryBackend = MindustryBackend.JAR,
    val variant: MindustryVariant = MindustryVariant.VANILLA,
    val build: Int = 0,
    val buildType: String = variant.defaultChannel,
    val jarPath: String? = null,
    val javaVersion: Int = 17,
    val javaRuntime: String? = null,
    val dataDirectoryPolicy: DataDirectoryPolicy = DataDirectoryPolicy.ISOLATED,
    val customDataDir: String? = null,
    val uuidProfileId: String? = null,
    val apkSlot: Int? = null,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "Instance id must not be blank" }
        require(name.isNotBlank()) { "Instance name must not be blank" }
        if (backend == MindustryBackend.APK) {
            require(apkSlot != null) { "APK backend instances must specify a slot" }
            require(ApkCloneSlot.isValidSlot(variant, apkSlot)) {
                "Invalid APK slot $apkSlot for ${variant.displayName}"
            }
        }
    }

    fun resolveRoot(paths: MindustryPaths): File =
        paths.instanceRoot(id)

    fun resolveJar(paths: MindustryPaths): File {
        val raw = jarPath?.takeIf { it.isNotBlank() } ?: return paths.jarFile(id)
        val file = File(raw)
        return if (file.isAbsolute) file else File(resolveRoot(paths), raw)
    }

    fun resolveDataDir(paths: MindustryPaths): File =
        when (dataDirectoryPolicy) {
            DataDirectoryPolicy.ISOLATED -> paths.isolatedDataDir(id)
            DataDirectoryPolicy.GLOBAL -> paths.globalData
            DataDirectoryPolicy.CUSTOM -> customDataDir
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: paths.isolatedDataDir(id)
        }
}

/** Small file-backed registry for JAR instances. */
object MindustryInstanceStore {
    private const val FILE_NAME = "instance.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun read(directory: File): MindustryInstance? = runCatching {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        gson.fromJson(file.readText(Charsets.UTF_8), MindustryInstance::class.java)
    }.getOrNull()?.takeIf { it.id.isNotBlank() }

    fun read(instanceId: String, paths: MindustryPaths = MindustryPaths(PathManager.DIR_MINDUSTRY)): MindustryInstance? =
        read(paths.instanceRoot(instanceId))

    fun list(paths: MindustryPaths = MindustryPaths(PathManager.DIR_MINDUSTRY)): List<MindustryInstance> =
        paths.instances.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull(::read)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun save(instance: MindustryInstance, paths: MindustryPaths = MindustryPaths(PathManager.DIR_MINDUSTRY)) {
        val root = instance.resolveRoot(paths)
        root.mkdirs()
        File(root, FILE_NAME).writeText(gson.toJson(instance), Charsets.UTF_8)
    }

    fun ensureFromArtifact(
        artifact: MindustryArtifact,
        jar: File,
        paths: MindustryPaths = MindustryPaths(PathManager.DIR_MINDUSTRY)
    ): MindustryInstance {
        require(artifact.backend == MindustryBackend.JAR) { "Only JAR artifacts can create an instance" }
        val instance = MindustryInstance(
            id = artifact.id,
            name = "${artifact.variant.displayName} ${artifact.versionName}",
            backend = MindustryBackend.JAR,
            variant = artifact.variant,
            build = artifact.build,
            buildType = artifact.buildType,
            jarPath = jar.name,
            javaVersion = artifact.javaVersion
        )
        save(instance, paths)
        jar.copyTo(instance.resolveJar(paths), overwrite = true)
        return instance
    }
}
