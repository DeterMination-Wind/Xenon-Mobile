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
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File

class MindustryLaunchOptionsTest {
    @Test
    fun jarLaunchArgsContainMindustryDataDirAndNoMinecraftRuntimePayload() {
        val jar = File("build/test-mindustry/instances/v146/v146.jar")
        val dataDir = File("build/test-mindustry/instances/v146/.data")
        val options = MindustryLaunchOptions(
            javaExecutable = File("java"),
            jar = jar,
            dataDir = dataDir,
            minHeapMb = 128,
            maxHeapMb = 1536,
            jvmArgs = listOf(
                "-Dminecraft.client.jar=/tmp/client.jar",
                "-javaagent:authlib-injector.jar",
                "-Djava.awt.headless=true",
                "-cp",
                "/tmp/minecraft-classpath",
                "-Dmindustry.test=true"
            ),
            gameArgs = listOf("-debug")
        )

        val args = options.buildJvmArgs()

        assertTrue(args.contains("-Xms128m"))
        assertTrue(args.contains("-Xmx1536m"))
        assertTrue(args.contains("-Dfile.encoding=UTF-8"))
        assertTrue(args.contains("-Dmindustry.data.dir=${dataDir.absolutePath}"))
        assertTrue(args.contains("-Dmindustry.test=true"))
        assertEquals(jar.absolutePath, args[args.indexOf("-jar") + 1])
        assertTrue(args.contains("-debug"))

        val lowered = args.joinToString(" ").lowercase()
        assertFalse(lowered.contains("minecraft"))
        assertFalse(lowered.contains("authlib"))
        assertFalse(lowered.contains("cacio"))
        assertFalse(args.contains("-cp"))
        assertFalse(args.contains("/tmp/minecraft-classpath"))

        assertEquals(
            dataDir.absolutePath,
            options.buildEnvironment()["MINDUSTRY_DATA_DIR"]
        )
    }

    @Test
    fun tokenizeSplitsFreeFormArguments() {
        assertEquals(
            listOf("-Xmx1024m", "-Dfoo=bar"),
            MindustryLaunchOptions.tokenize("-Xmx1024m   -Dfoo=bar")
        )
    }
}
