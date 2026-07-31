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

import junit.framework.TestCase.assertEquals
import org.junit.Test
import java.io.File

class MindustryPathsTest {
    @Test
    fun isolatedDataDirResolvesUnderInstanceRoot() {
        val paths = MindustryPaths(File("build/test-mindustry"))
        val instance = MindustryInstance(id = "Vanilla 146")

        assertEquals(
            File(paths.instances, "Vanilla_146"),
            instance.resolveRoot(paths)
        )
        assertEquals(
            File(File(paths.instances, "Vanilla_146"), ".data"),
            instance.resolveDataDir(paths)
        )
        assertEquals(
            File(File(paths.instances, "Vanilla_146"), "Vanilla_146.jar"),
            instance.resolveJar(paths)
        )
    }

    @Test
    fun globalAndCustomDataPoliciesResolveExplicitly() {
        val paths = MindustryPaths(File("build/test-mindustry"))
        val global = MindustryInstance(
            id = "global",
            dataDirectoryPolicy = DataDirectoryPolicy.GLOBAL
        )
        val custom = MindustryInstance(
            id = "custom",
            dataDirectoryPolicy = DataDirectoryPolicy.CUSTOM,
            customDataDir = "D:/MindustryData"
        )

        assertEquals(paths.globalData, global.resolveDataDir(paths))
        assertEquals(File("D:/MindustryData"), custom.resolveDataDir(paths))
    }
}
