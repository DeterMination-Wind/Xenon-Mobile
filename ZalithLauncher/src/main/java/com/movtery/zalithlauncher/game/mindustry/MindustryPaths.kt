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

/**
 * Filesystem layout for Xenon Mobile's Mindustry-only data root.
 */
data class MindustryPaths(
    val root: File
) {
    val instances: File = File(root, "instances")
    val globalData: File = File(root, "global-data")
    val catalog: File = File(root, "catalog")
    val clones: File = File(root, "clones")

    fun instanceRoot(instanceId: String): File =
        File(instances, sanitizeId(instanceId))

    fun jarFile(instanceId: String): File =
        File(instanceRoot(instanceId), "${sanitizeId(instanceId)}.jar")

    fun isolatedDataDir(instanceId: String): File =
        File(instanceRoot(instanceId), ".data")

    fun ensureBaseDirs() {
        listOf(root, instances, globalData, catalog, clones).forEach { it.mkdirs() }
    }

    companion object {
        fun sanitizeId(id: String): String =
            id.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "instance" }
    }
}
