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

import com.movtery.zalithlauncher.utils.network.fetchStringFromUrl
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface MindustryCatalogLoadResult {
    data object Loading : MindustryCatalogLoadResult

    data class Success(
        val manifest: MindustryCatalogManifest,
        val sourceUrl: String,
        val attemptedUrls: List<String>
    ) : MindustryCatalogLoadResult

    data class Empty(
        val manifest: MindustryCatalogManifest,
        val sourceUrl: String,
        val attemptedUrls: List<String>
    ) : MindustryCatalogLoadResult

    data class Error(
        val message: String,
        val attemptedUrls: List<String>
    ) : MindustryCatalogLoadResult
}

object MindustryCatalogRepository {
    suspend fun fetchDefaultManifest(): MindustryCatalogLoadResult =
        fetchManifest(MindustryCatalog.defaultManifestUrls())

    suspend fun fetchManifest(
        urls: List<String>,
        fetcher: suspend (String) -> String = ::fetchStringFromUrl
    ): MindustryCatalogLoadResult {
        require(urls.isNotEmpty()) { "Catalog URL list must not be empty." }

        val attemptedUrls = mutableListOf<String>()
        var lastError: Throwable? = null

        for (url in urls) {
            attemptedUrls += url

            try {
                val manifest = MindustryCatalog.parse(fetcher(url))
                return if (manifest.artifacts.isEmpty()) {
                    MindustryCatalogLoadResult.Empty(
                        manifest = manifest,
                        sourceUrl = url,
                        attemptedUrls = attemptedUrls.toList()
                    )
                } else {
                    MindustryCatalogLoadResult.Success(
                        manifest = manifest,
                        sourceUrl = url,
                        attemptedUrls = attemptedUrls.toList()
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }

        return MindustryCatalogLoadResult.Error(
            message = lastError?.message ?: IOException("Failed to load Mindustry catalog").message.orEmpty(),
            attemptedUrls = attemptedUrls.toList()
        )
    }
}
