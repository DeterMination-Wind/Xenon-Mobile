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

class UuidProfileTest {
    @Test
    fun mindustryUuidUsesEightByteBase64Format() {
        val profile = UuidProfile.fromBytes(
            id = "main",
            name = "Player",
            uuidBytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        )

        assertEquals("AAAAAAAAAAA=", profile.uuid)
        assertTrue(UuidProfile.isValidUuid(profile.uuid))
        assertTrue(UuidProfile.isValidUuid("AQIDBAUGBwg="))
    }

    @Test
    fun javaUuidIsNotAcceptedForMindustryProfile() {
        assertFalse(UuidProfile.isValidUuid("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(UuidProfile.isValidUuid("AQIDBAUGBwg"))
        assertFalse(UuidProfile.isValidUuid("AQIDBAUGBwg=="))
    }
}
