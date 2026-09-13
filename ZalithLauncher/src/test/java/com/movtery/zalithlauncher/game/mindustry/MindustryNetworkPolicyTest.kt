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

import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Android network policy that lets the launcher reach a mirror by a direct IP.
 *
 * The Xenon mirror may be deployed behind a bare IP or a plain HTTP endpoint, so the app must
 * keep permitting cleartext traffic. GitHub stays the last-resort fallback and is always HTTPS.
 */
class MindustryNetworkPolicyTest {
    @Test
    fun networkSecurityConfigPermitsCleartextTraffic() {
        val config = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml is missing", config.isFile)

        val text = config.readText()
        assertTrue(
            "cleartext traffic must stay permitted for direct mirror IPs",
            text.contains("cleartextTrafficPermitted=\"true\"")
        )
    }

    @Test
    fun manifestKeepsCleartextTrafficEnabled() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml is missing", manifest.isFile)

        assertTrue(
            "android:usesCleartextTraffic must stay enabled for direct mirror IPs",
            manifest.readText().contains("android:usesCleartextTraffic=\"true\"")
        )
    }
}
