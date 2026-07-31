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
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MindustryBridgeContractTest {
    @Test
    fun bridgeContractMatchesXenonMobilePackageAndActionSet() {
        assertEquals("com.xenon.mobile", MindustryBridgeContract.HUB_PACKAGE)
        assertEquals(
            "com.xenon.mobile.permission.MINDUSTRY_BRIDGE",
            MindustryBridgeContract.PERMISSION
        )
        assertEquals("uuid", MindustryBridgeContract.EXTRA_PROFILE_UUID)
        assertEquals("name", MindustryBridgeContract.EXTRA_PROFILE_NAME)

        val expected = setOf(
            MindustryBridgeContract.ACTION_LAUNCH,
            MindustryBridgeContract.ACTION_STATUS,
            MindustryBridgeContract.ACTION_SET_PROFILE,
            MindustryBridgeContract.ACTION_JOIN,
            MindustryBridgeContract.ACTION_IMPORT_ZIP,
            MindustryBridgeContract.ACTION_EXPORT_ZIP,
            MindustryBridgeContract.ACTION_EXPORT_DIAGNOSTICS,
            MindustryBridgeContract.ACTION_REQUEST_GRACEFUL_EXIT,
            MindustryBridgeContract.ACTION_RESET_WHITELISTED_DATA
        )

        assertEquals(expected, MindustryBridgeContract.actions)
        assertTrue(MindustryBridgeContract.actions.all { it.startsWith("com.xenon.mobile.bridge.") })
    }
}
