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

class MindustryCatalogTest {
    @Test
    fun parsesArm64CatalogArtifacts() {
        val sha = "a".repeat(64)
        val catalog = MindustryCatalog.parse(
            """
            {
              "schemaVersion": 1,
              "artifacts": [
                {
                  "id": "vanilla-v146-jar",
                  "variant": "vanilla",
                  "channel": "stable",
                  "backend": "jar",
                  "versionName": "v146",
                  "build": 146,
                  "buildType": "stable",
                  "javaVersion": 17,
                  "urls": ["https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.jar"],
                  "sha256": "$sha",
                  "size": 42,
                  "nativeProfile": "arm64-v8a",
                  "mgVersion": "mg-v1",
                  "minLauncherVersion": 1,
                  "changelog": "test",
                  "sourceRepo": "Anuken/Mindustry",
                  "sourceCommit": "20da6a38ab0874b5d971bffede3995efd3da5d70",
                  "releaseTag": "v146"
                },
                {
                  "id": "mindustryx-slot1",
                  "variant": "mindustryx",
                  "channel": "stable",
                  "backend": "apk",
                  "slot": 1,
                  "packageName": "com.xenon.mobile.clone.mindustryx.slot1",
                  "versionCode": 8000001,
                  "versionName": "v8",
                  "build": 8,
                  "buildType": "stable",
                  "urls": ["https://github.com/TinyLake/MindustryX/releases/download/v8/MindustryX-slot1.apk"],
                  "sha256": "$sha",
                  "size": 43,
                  "nativeProfile": "arm64-v8a",
                  "minLauncherVersion": 1,
                  "sourceRepo": "TinyLake/MindustryX",
                  "sourceCommit": "3b894f8518c1a36ec60f1f32af50a8b249d0f060",
                  "releaseTag": "v8",
                  "signatureSha256": ["$sha"]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, catalog.artifacts.size)
        assertEquals(1, catalog.artifactsFor(MindustryVariant.VANILLA, MindustryBackend.JAR).size)
        assertEquals(1, catalog.artifactsFor(MindustryVariant.MINDUSTRY_X, MindustryBackend.APK).single().slot)
    }

    @Test
    fun mirrorUrlsNeverIncludeGithubFallback() {
        val urls = MindustryCatalog.mirrorUrls(
            "https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.jar"
        )

        assertEquals(
            listOf("http://play.mindustry.men/github/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar"),
            urls
        )
    }

    @Test
    fun defaultManifestUsesMirrorOnly() {
        val urls = MindustryCatalog.defaultManifestUrls()

        assertEquals(
            listOf("http://play.mindustry.men/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"),
            urls
        )
    }

    @Test
    fun artifactUrlsUseServerMirrorOnly() {
        val sha = "a".repeat(64)
        val catalog = MindustryCatalog.parse(
            """
            {
              "schemaVersion": 1,
              "mirrors": [
                {
                  "id": "mirror",
                  "baseUrl": "http://play.mindustry.men/github",
                  "priority": 0
                }
              ],
              "artifacts": [
                {
                  "id": "vanilla-v146-jar",
                  "variant": "vanilla",
                  "channel": "stable",
                  "backend": "jar",
                  "versionName": "v146",
                  "build": 146,
                  "buildType": "stable",
                  "urls": ["https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.jar"],
                  "sha256": "$sha",
                  "size": 42,
                  "nativeProfile": "arm64-v8a",
                  "sourceRepo": "Anuken/Mindustry",
                  "sourceCommit": "20da6a38ab0874b5d971bffede3995efd3da5d70",
                  "releaseTag": "v146"
                }
              ]
            }
            """.trimIndent()
        )

        val urls = MindustryCatalog.artifactDownloadUrls(catalog.artifacts.single(), catalog)

        assertEquals(
            listOf("http://play.mindustry.men/github/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar"),
            urls
        )
    }

    @Test
    fun serverListSourcesUseConfiguredMirrorOnly() {
        val canonical = "https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json"
        val urls = MindustryCatalog.serverListUrls(canonical)

        assertEquals(
            listOf("http://play.mindustry.men/github/repos/Anuken/MindustryServerList/servers_v8.json"),
            urls
        )
    }
}
