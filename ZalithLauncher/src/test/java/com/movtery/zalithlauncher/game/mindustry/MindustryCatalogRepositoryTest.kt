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
    private val mirrorCatalogUrl =
        "${MindustryCatalog.PRIMARY_SERVER_MIRROR}/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
    private val domainCatalogUrl =
        "${MindustryCatalog.DOMAIN_SERVER_MIRROR}/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
    private val githubCatalogUrl =
        "https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json"
    private val mirrorServerListUrl =
        "${MindustryCatalog.PRIMARY_SERVER_MIRROR}/repos/Anuken/MindustryServerList/servers_v8.json"
    private val domainServerListUrl =
        "${MindustryCatalog.DOMAIN_SERVER_MIRROR}/repos/Anuken/MindustryServerList/servers_v8.json"
    private val githubServerListUrl =
        "https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json"

    private fun manifestJson(): String {
        val sha = "a".repeat(64)
        return """
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

    @Test
    fun defaultManifestSourcesTryTheIpMirrorThenTheDomainThenGithub() {
        assertEquals(
            listOf(mirrorCatalogUrl, domainCatalogUrl, githubCatalogUrl),
            MindustryCatalog.defaultManifestUrls()
        )
    }

    @Test
    fun serverListRepositoryPrefersTheIpMirror() = runBlocking {
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

            assertEquals(mirrorServerListUrl, result.sourceUrl)
            assertEquals(listOf(mirrorServerListUrl), attemptedUrls)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun serverListRepositoryFallsBackToGithub() = runBlocking {
        val cacheRoot = Files.createTempDirectory("xenon-server-list-fallback").toFile()
        val attemptedUrls = mutableListOf<String>()
        try {
            val result = MindustryServerListRepository(cacheRoot).load(
                variant = MindustryVariant.VANILLA,
                forceRefresh = true
            ) { url ->
                attemptedUrls += url
                if (!url.startsWith("https://raw.githubusercontent.com/")) throw IOException("mirror is down")
                "[]"
            }

            assertEquals(githubServerListUrl, result.sourceUrl)
            assertEquals(listOf(mirrorServerListUrl, domainServerListUrl, githubServerListUrl), attemptedUrls)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun fetchManifestAcceptsTheGithubFallbackSource() = runBlocking {
        val accepted = runCatching {
            MindustryCatalogRepository.fetchManifest(
                urls = listOf(mirrorCatalogUrl, githubCatalogUrl)
            ) { manifestJson() }
        }

        assertTrue(accepted.isSuccess)
        val result = accepted.getOrThrow() as MindustryCatalogLoadResult.Success
        assertEquals(mirrorCatalogUrl, result.sourceUrl)
        assertEquals(listOf(mirrorCatalogUrl), result.attemptedUrls)
    }

    @Test
    fun fetchManifestFallsBackToGithubWhenEveryMirrorFails() = runBlocking {
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(mirrorCatalogUrl, domainCatalogUrl, githubCatalogUrl)
        ) { url ->
            if (url != githubCatalogUrl) throw IOException("mirror is down")
            manifestJson()
        }

        assertTrue(result is MindustryCatalogLoadResult.Success)
        result as MindustryCatalogLoadResult.Success
        assertEquals(githubCatalogUrl, result.sourceUrl)
        assertEquals(listOf(mirrorCatalogUrl, domainCatalogUrl, githubCatalogUrl), result.attemptedUrls)
    }

    @Test
    fun fetchManifestRejectsUnknownSources() = runBlocking {
        val rejected = runCatching {
            MindustryCatalogRepository.fetchManifest(
                urls = listOf("https://mirror.example.com/catalog.json")
            ) { error("Unknown sources must never be requested") }
        }

        assertTrue(rejected.isFailure)
    }

    @Test
    fun fetchManifestReturnsSuccessFromTheServerMirror() = runBlocking {
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(mirrorCatalogUrl)
        ) { manifestJson() }

        assertTrue(result is MindustryCatalogLoadResult.Success)
        result as MindustryCatalogLoadResult.Success
        assertEquals(mirrorCatalogUrl, result.sourceUrl)
        assertEquals(1, result.manifest.artifacts.size)
    }

    @Test
    fun fetchManifestReturnsEmptyForPublishedEmptyCatalog() = runBlocking {
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(mirrorCatalogUrl)
        ) {
            """{"schemaVersion":1,"artifacts":[]}"""
        }

        assertTrue(result is MindustryCatalogLoadResult.Empty)
        result as MindustryCatalogLoadResult.Empty
        assertEquals(mirrorCatalogUrl, result.sourceUrl)
        assertEquals(0, result.manifest.artifacts.size)
    }

    @Test
    fun fetchManifestReportsEveryAttemptedSource() = runBlocking {
        val result = MindustryCatalogRepository.fetchManifest(
            urls = listOf(mirrorCatalogUrl, domainCatalogUrl, githubCatalogUrl)
        ) { url ->
            throw IOException("$url failed")
        }

        assertTrue(result is MindustryCatalogLoadResult.Error)
        result as MindustryCatalogLoadResult.Error
        assertEquals(listOf(mirrorCatalogUrl, domainCatalogUrl, githubCatalogUrl), result.attemptedUrls)
        assertTrue(result.message.contains("$githubCatalogUrl failed"))
    }
}
