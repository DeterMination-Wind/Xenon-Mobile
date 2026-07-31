/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.mindustry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * SAF-independent archive implementation for Bridge backups and diagnostics.
 * All limits are enforced while streaming, so an entry's declared size is never trusted.
 */
object MindustryBridgeArchive {
    const val MAX_COMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    const val MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
    const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
    private const val MAX_DIAGNOSTIC_FILE_BYTES = 16L * 1024L * 1024L
    private const val BUFFER_SIZE = 32 * 1024

    data class Manifest(
        val kind: String,
        val variant: String?,
        val backend: String?,
        val slot: Int?
    )

    fun exportBackup(
        sourceRoot: File,
        output: OutputStream,
        variant: MindustryVariant?,
        backend: MindustryBackend?,
        slot: Int?
    ) {
        val manifest = manifestJson("backup", variant, backend, slot)
        writeZip(output) { zip ->
            addBytes(zip, "manifest.json", manifest.toByteArray(Charsets.UTF_8))
            if (sourceRoot.isDirectory) {
                addDirectory(zip, sourceRoot, sourceRoot, "data")
            }
        }
    }

    fun importBackup(
        input: InputStream,
        targetRoot: File,
        expectedVariant: MindustryVariant?,
        expectedBackend: MindustryBackend?,
        expectedSlot: Int?
    ): Manifest {
        val stage = File(targetRoot.parentFile ?: targetRoot, ".bridge-import-${UUID.randomUUID()}")
        stage.mkdirs()
        try {
            val manifest = extract(input, stage)
            require(manifest.kind == "backup") { "Archive is not a Mindustry backup" }
            requireMatches(manifest, expectedVariant, expectedBackend, expectedSlot)
            val stagedData = File(stage, "data")
            copyTree(stagedData, targetRoot)
            return manifest
        } finally {
            deleteTree(stage)
        }
    }

    fun exportDiagnostics(
        output: OutputStream,
        logsRoot: File,
        variant: MindustryVariant?,
        backend: MindustryBackend?,
        slot: Int?
    ) {
        val metadata = JsonObject().apply {
            addProperty("protocolVersion", MindustryBridgeContract.PROTOCOL_VERSION)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("variant", variant?.catalogId)
            addProperty("backend", backend?.name?.lowercase())
            slot?.let { addProperty("slot", it) }
        }.toString()
        writeZip(output) { zip ->
            addBytes(zip, "manifest.json", manifestJson("diagnostics", variant, backend, slot).toByteArray(Charsets.UTF_8))
            addBytes(zip, "data/diagnostics.json", metadata.toByteArray(Charsets.UTF_8))
            if (logsRoot.isDirectory) addDiagnosticDirectory(zip, logsRoot, logsRoot, "data/logs")
        }
    }

    private fun writeZip(output: OutputStream, body: (ZipOutputStream) -> Unit) {
        val counting = CountingOutputStream(output, MAX_COMPRESSED_BYTES)
        ZipOutputStream(counting).use(body)
    }

    private fun addDirectory(zip: ZipOutputStream, root: File, current: File, prefix: String) {
        current.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic links are not allowed in backups")
            val entryName = "$prefix/${file.relativeTo(root).invariantSeparatorsPath}"
            if (file.isDirectory) addDirectory(zip, root, file, prefix)
            else if (file.isFile) addFile(zip, entryName, file)
        }
    }

    private fun addDiagnosticDirectory(zip: ZipOutputStream, root: File, current: File, prefix: String) {
        current.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            val lowerName = file.name.lowercase()
            if (file.isDirectory) {
                addDiagnosticDirectory(zip, root, file, prefix)
            } else if (file.isFile && (lowerName.endsWith(".log") || lowerName.endsWith(".txt"))) {
                if (file.length() > MAX_DIAGNOSTIC_FILE_BYTES) return@forEach
                val entryName = "$prefix/${file.relativeTo(root).invariantSeparatorsPath}"
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                addBytes(zip, entryName, redact(text).toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyLimitedTo(zip) }
        zip.closeEntry()
    }

    private fun addBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun extract(input: InputStream, stage: File): Manifest {
        val counted = CountingInputStream(input, MAX_COMPRESSED_BYTES)
        val seen = HashSet<String>()
        var entries = 0
        var uncompressed = 0L
        var manifestBytes: ByteArray? = null

        ZipInputStream(counted).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Archive contains too many entries" }
                val name = safeEntryName(entry.name, entry.isDirectory)
                require(seen.add(name)) { "Archive contains duplicate entry: $name" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val manifestOutput = if (name == "manifest.json") ByteArrayOutputStream() else null
                val dataFile = if (name.startsWith("data/")) File(stage, name) else null
                if (dataFile != null) {
                    require(dataFile.canonicalPath.startsWith(stage.canonicalPath + File.separator)) {
                        "Archive path escapes staging directory"
                    }
                    dataFile.parentFile?.mkdirs()
                }
                val output = manifestOutput ?: dataFile?.outputStream()
                    ?: throw IOException("Unexpected archive entry: $name")
                val remaining = minOf(
                    MAX_UNCOMPRESSED_BYTES - uncompressed,
                    if (manifestOutput != null) MAX_MANIFEST_BYTES else MAX_UNCOMPRESSED_BYTES
                )
                val copied = output.use {
                    zip.copyLimitedTo(it, remaining)
                }
                uncompressed += copied
                require(uncompressed <= MAX_UNCOMPRESSED_BYTES) { "Archive expands beyond the safety limit" }
                if (name == "manifest.json") {
                    require(copied <= MAX_MANIFEST_BYTES) { "Manifest is too large" }
                    manifestBytes = manifestOutput?.toByteArray()
                }
                zip.closeEntry()
            }
        }

        val manifest = manifestBytes ?: throw IOException("Archive is missing manifest.json")
        return parseManifest(manifest)
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(json["schemaVersion"]?.asInt == 1) { "Unsupported archive schema" }
        return Manifest(
            kind = json["kind"]?.asString.orEmpty(),
            variant = json["variant"]?.takeUnless { it.isJsonNull }?.asString,
            backend = json["backend"]?.takeUnless { it.isJsonNull }?.asString,
            slot = json["slot"]?.takeUnless { it.isJsonNull }?.asInt
        )
    }

    private fun requireMatches(manifest: Manifest, variant: MindustryVariant?, backend: MindustryBackend?, slot: Int?) {
        require(variant == null || manifest.variant.equals(variant.catalogId, true)) { "Archive variant does not match target" }
        require(backend == null || manifest.backend.equals(backend.name, true)) { "Archive backend does not match target" }
        require(slot == null || manifest.slot == slot) { "Archive slot does not match target" }
    }

    private fun manifestJson(kind: String, variant: MindustryVariant?, backend: MindustryBackend?, slot: Int?): String =
        JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("kind", kind)
            addProperty("createdAt", System.currentTimeMillis())
            variant?.let { addProperty("variant", it.catalogId) }
            backend?.let { addProperty("backend", it.name.lowercase()) }
            slot?.let { addProperty("slot", it) }
        }.toString()

    private fun safeEntryName(raw: String, directory: Boolean): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.contains(':')) {
            "Invalid archive path"
        }
        val name = normalized.trimEnd('/')
        require(name.isNotBlank()) { "Invalid archive path" }
        val components = name.split('/')
        require(components.none { it.isEmpty() || it == "." || it == ".." }) {
            "Archive path traversal is not allowed"
        }
        require(
            name == "manifest.json" || name == "data" || name.startsWith("data/")
        ) { "Archive entry is outside data/" }
        require(directory || name != "data") { "Archive data root must be a directory" }
        return name
    }

    private fun copyTree(source: File, target: File) {
        if (!source.isDirectory) return
        source.walkTopDown().forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic links are not allowed in backups")
            val relative = file.relativeTo(source)
            val destination = if (relative.path.isEmpty()) target else File(target, relative.invariantSeparatorsPath)
            if (file.isDirectory) destination.mkdirs()
            else if (file.isFile) {
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        root.walkBottomUp().forEach { it.delete() }
    }

    private fun redact(value: String): String {
        val json = Regex(
            "(?i)(\\\"(?:access[_-]?token|refresh[_-]?token|authorization|password|secret|client[_-]?secret|api[_-]?key)\\\"\\s*:\\s*)\\\"[^\\\"]*\\\""
        )
        val plain = Regex(
            "(?i)\\b(access[_-]?token|refresh[_-]?token|authorization|password|secret|client[_-]?secret|api[_-]?key)\\b\\s*[:=]\\s*[^\\s,;}]+"
        )
        return value
            .replace(json, "$1\"[redacted]\"")
            .replace(plain) { "${it.groupValues[1]}=[redacted]" }
            .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [redacted]")
    }

    private fun InputStream.copyLimitedTo(output: OutputStream, remaining: Long = Long.MAX_VALUE): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= remaining) { "Archive expands beyond the safety limit" }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private class CountingInputStream(
        input: InputStream,
        private val limit: Long
    ) : java.io.FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) increment(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) increment(read.toLong())
            return read
        }

        private fun increment(value: Long) {
            count += value
            require(count <= limit) { "Compressed archive exceeds the safety limit" }
        }
    }

    private class CountingOutputStream(
        output: OutputStream,
        private val limit: Long
    ) : java.io.FilterOutputStream(output) {
        private var count = 0L

        override fun write(value: Int) {
            super.write(value)
            increment(1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            super.write(buffer, offset, length)
            increment(length.toLong())
        }

        private fun increment(value: Long) {
            count += value
            require(count <= limit) { "Compressed archive exceeds the safety limit" }
        }
    }
}
