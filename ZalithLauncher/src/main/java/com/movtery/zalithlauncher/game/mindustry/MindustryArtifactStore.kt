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

import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.child
import com.movtery.zalithlauncher.utils.file.ensureParentDirectory
import com.movtery.zalithlauncher.utils.network.downloadFromMirrorListSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object MindustryArtifactStore {
    fun artifactFile(artifact: MindustryArtifact): File =
        PathManager.DIR_MINDUSTRY_CATALOG_CACHE
            .child("artifacts", safePathPart(artifact.id), safeArtifactFileName(artifact))

    fun cachedArtifactFile(artifact: MindustryArtifact): File? =
        artifactFile(artifact).takeIf { file ->
            file.isFile && (artifact.size <= 0L || file.length() == artifact.size)
        }

    suspend fun downloadToCache(
        artifact: MindustryArtifact,
        manifest: MindustryCatalogManifest
    ): File = withContext(Dispatchers.IO) {
        val targetFile = artifactFile(artifact)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        val urls = MindustryCatalog.artifactDownloadUrls(artifact, manifest)

        require(urls.isNotEmpty()) { "Artifact ${artifact.id} has no download URL" }

        targetFile.ensureParentDirectory()
        tempFile.delete()

        downloadFromMirrorListSuspend(
            urls = urls,
            outputFile = tempFile
        )

        if (artifact.size > 0L && tempFile.length() != artifact.size) {
            tempFile.delete()
            throw IOException(
                "Artifact ${artifact.id} size mismatch: expected ${artifact.size}, got ${tempFile.length()}"
            )
        }

        val actualSha256 = calculateSha256(tempFile)
        if (!artifact.sha256.equals(actualSha256, ignoreCase = true)) {
            tempFile.delete()
            throw IOException("Artifact ${artifact.id} SHA-256 mismatch")
        }

        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        targetFile
    }

    /** Revalidates a cached artifact before an installer or launcher consumes it. */
    suspend fun verifyCachedArtifact(artifact: MindustryArtifact): File = withContext(Dispatchers.IO) {
        val file = artifactFile(artifact)
        require(file.isFile) { "Artifact ${artifact.id} is not cached" }
        if (artifact.size > 0L && file.length() != artifact.size) {
            throw IOException("Artifact ${artifact.id} size mismatch")
        }
        val actualSha256 = calculateSha256(file)
        if (!artifact.sha256.equals(actualSha256, ignoreCase = true)) {
            throw IOException("Artifact ${artifact.id} SHA-256 mismatch")
        }
        file
    }

    private fun safeArtifactFileName(artifact: MindustryArtifact): String {
        val fallbackExt = when (artifact.backend) {
            MindustryBackend.JAR -> "jar"
            MindustryBackend.APK -> "apk"
        }
        val rawName = artifact.urls.firstOrNull()
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "${artifact.id}.$fallbackExt"

        return rawName.replace(Regex("[\\\\/:*?\"<>|\\t\\n]"), "_")
    }

    private fun safePathPart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "artifact" }

    private suspend fun calculateSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
