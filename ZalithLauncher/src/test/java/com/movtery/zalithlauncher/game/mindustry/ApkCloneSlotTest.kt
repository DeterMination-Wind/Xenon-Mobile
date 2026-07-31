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

class ApkCloneSlotTest {
    @Test
    fun fixedSlotRegistryMatchesPlan() {
        val slots = ApkCloneSlot.all()

        assertEquals(11, slots.size)
        assertEquals(5, slots.count { it.variant == MindustryVariant.VANILLA })
        assertEquals(5, slots.count { it.variant == MindustryVariant.MINDUSTRY_X })
        assertEquals(1, slots.count { it.variant == MindustryVariant.BE })
        assertEquals("com.xenon.mobile.clone.vanilla.slot1", slots.first().packageName)
        assertFalse(ApkCloneSlot.isValidSlot(MindustryVariant.BE, 2))
    }

    @Test
    fun runtimeCoordinatorBlocksConcurrentJarAndApkRuns() {
        MindustryRuntimeCoordinator.current()?.let { MindustryRuntimeCoordinator.release(it.instanceId) }

        assertTrue(MindustryRuntimeCoordinator.tryAcquire("jar-1", MindustryBackend.JAR))
        assertFalse(MindustryRuntimeCoordinator.tryAcquire("apk-1", MindustryBackend.APK))

        MindustryRuntimeCoordinator.release("jar-1")

        assertTrue(MindustryRuntimeCoordinator.tryAcquire("apk-1", MindustryBackend.APK))
        MindustryRuntimeCoordinator.release("apk-1")
    }
}
