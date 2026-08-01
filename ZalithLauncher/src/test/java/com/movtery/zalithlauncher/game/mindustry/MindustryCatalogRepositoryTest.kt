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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class MindustryCatalogRepositoryTest {
    @Test
    fun serverListRepositoryUsesServerMirrorOnly() = runBlocking {
        val cacheRoot = Files.createTempDirectory("xenon-server-list").toFile()
        val attemptedUrls = mutableListOf<String>()
        try {
            val result = MindustryServerListRepository(cacheRoot).load(
                variant = MindustryVariant.VANILLA,
                forceRefresh = true
            ) { url ->
                attemptedUrls += url
                "[]"
            }

            assertEquals(
                "http://play.mindustry.men/github/repos/Anuken/MindustryServerList/servers_v8.json",
                result.sourceUrl
            )
            assertEquals(listOf(result.sourceUrl), attemptedUrls)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun fetchManifestRejectsGithubFallbackSource() = runBlocking {
        val mirror = MindustryCatalog.defaultManifestUrls().single()
        val accepted = runCatching {
            MindustryCatalogRepository.fetchManifest(
                urls = listOf(mirror, "https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json")
            ) { error("GitHub must never be requested") }
        }

        assertTrue(accepted.isFailure)
    }

    @Test
    fun fetchManifestReturnsSuccessFromTheServerMirror() = runBlocking {
        val sha = "a".repeat(64)
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(MindustryCatalog.defaultManifestUrls().single())
        ) {
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
        }

        assertTrue(result is MindustryCatalogLoadResult.Success)
        result as MindustryCatalogLoadResult.Success
        assertEquals(MindustryCatalog.defaultManifestUrls().single(), result.sourceUrl)
        assertEquals(listOf(result.sourceUrl), result.attemptedUrls)
        assertEquals(1, result.manifest.artifacts.size)
    }

    @Test
    fun fetchManifestReturnsEmptyForPublishedEmptyCatalog() = runBlocking {
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(MindustryCatalog.defaultManifestUrls().single())
        ) {
            """{"schemaVersion":1,"artifacts":[]}"""
        }

        assertTrue(result is MindustryCatalogLoadResult.Empty)
        result as MindustryCatalogLoadResult.Empty
        assertEquals(MindustryCatalog.defaultManifestUrls().single(), result.sourceUrl)
        assertEquals(0, result.manifest.artifacts.size)
    }

    @Test
    fun fetchManifestReportsOnlyTheMirrorWhenItFails() = runBlocking {
        val mirror = MindustryCatalog.defaultManifestUrls().single()
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(mirror)
        ) { url ->
            throw IOException("$url failed")
        }

        assertTrue(result is MindustryCatalogLoadResult.Error)
        result as MindustryCatalogLoadResult.Error
        assertEquals(listOf(mirror), result.attemptedUrls)
        assertTrue(result.message.contains("$mirror failed"))
    }
}
