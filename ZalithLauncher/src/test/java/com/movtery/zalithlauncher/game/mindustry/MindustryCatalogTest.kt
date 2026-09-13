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

class MindustryCatalogTest {
    private val primaryMirror = MindustryCatalog.PRIMARY_SERVER_MIRROR
    private val domainMirror = MindustryCatalog.DOMAIN_SERVER_MIRROR
    private val githubJarUrl = "https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.jar"

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
                  "urls": ["$githubJarUrl"],
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
    fun defaultMirrorIsTheDirectIpAndTheDomainStaysSecondary() {
        assertEquals("http://121.199.60.4/github", MindustryCatalog.DEFAULT_SERVER_MIRROR)

        val bases = MindustryCatalog.defaultMirrors.sortedBy { it.priority }.map { it.baseUrl }
        assertEquals(primaryMirror, bases.first())
        assertEquals(domainMirror, bases.last())
        assertTrue(MindustryCatalog.isCatalogUrl(primaryMirror))
        assertTrue(MindustryCatalog.isPrimaryMirrorUrl("$primaryMirror/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar"))
    }

    @Test
    fun githubUrlKeepsTheMirrorsFirstAndGithubLast() {
        val urls = MindustryCatalog.downloadCandidateUrls(githubJarUrl)

        assertEquals(
            listOf(
                "$primaryMirror/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar",
                "$domainMirror/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar",
                githubJarUrl
            ),
            urls
        )
    }

    @Test
    fun publishedMirrorUrlStillGetsEveryOtherMirrorAndTheGithubFallback() {
        val published = "$domainMirror/repos/DeterMination-Wind/Xenon-Mobile/releases/download/v1/x.apk"
        val urls = MindustryCatalog.downloadCandidateUrls(published)

        assertEquals(
            listOf(
                "$primaryMirror/repos/DeterMination-Wind/Xenon-Mobile/releases/download/v1/x.apk",
                published,
                "https://github.com/DeterMination-Wind/Xenon-Mobile/releases/download/v1/x.apk"
            ),
            urls
        )
    }

    @Test
    fun mirrorRoutesMapBackToCanonicalGithubUrls() {
        assertEquals(
            "https://github.com/DeterMination-Wind/Xenon-Mobile/releases/download/v1/x.apk",
            MindustryCatalog.githubFallbackUrl(
                "$primaryMirror/repos/DeterMination-Wind/Xenon-Mobile/releases/download/v1/x.apk"
            )
        )
        assertEquals(
            "https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json",
            MindustryCatalog.githubFallbackUrl(
                "$primaryMirror/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
            )
        )
        assertEquals(
            "https://api.github.com/repos/DeterMination-Wind/Xenon-Mobile/releases/latest",
            MindustryCatalog.githubFallbackUrl(
                "$primaryMirror/repos/DeterMination-Wind/Xenon-Mobile/releases/latest"
            )
        )
    }

    @Test
    fun defaultManifestUsesMirrorsThenGithubFallback() {
        val urls = MindustryCatalog.defaultManifestUrls()

        assertEquals(
            listOf(
                "$primaryMirror/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json",
                "$domainMirror/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json",
                "https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
            ),
            urls
        )
    }

    @Test
    fun artifactUrlsUseMirrorsThenGithubFallback() {
        val sha = "a".repeat(64)
        val catalog = MindustryCatalog.parse(
            """
            {
              "schemaVersion": 1,
              "mirrors": [
                {
                  "id": "xenon-domain",
                  "baseUrl": "$domainMirror",
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
                  "urls": ["$githubJarUrl"],
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
            listOf(
                "$primaryMirror/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar",
                "$domainMirror/repos/Anuken/Mindustry/releases/download/v146/Mindustry.jar",
                githubJarUrl
            ),
            urls
        )
    }

    @Test
    fun serverListSourcesUseMirrorsThenGithubFallback() {
        val canonical = "https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json"
        val urls = MindustryCatalog.serverListUrls(canonical)

        assertEquals(
            listOf(
                "$primaryMirror/repos/Anuken/MindustryServerList/servers_v8.json",
                "$domainMirror/repos/Anuken/MindustryServerList/servers_v8.json",
                canonical
            ),
            urls
        )
    }

    @Test
    fun cleartextDirectIpMirrorsStayAllowed() {
        val directIp = "http://192.168.1.10:8080/github/repos/X/Y/releases/download/v1/x.apk"

        assertTrue(MindustryCatalog.isCatalogUrl(directIp))
        assertTrue(MindustryCatalog.isAllowedCatalogSource(directIp))
        assertTrue(MindustryCatalog.isAllowedCatalogSource(MindustryCatalog.PRIMARY_SERVER_MIRROR))
        assertTrue(
            MindustryCatalog.isAllowedCatalogSource(
                "https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
            )
        )
        assertFalse(MindustryCatalog.isAllowedCatalogSource("https://mirror.example.com/repos/X/Y/x.apk"))
    }
}
