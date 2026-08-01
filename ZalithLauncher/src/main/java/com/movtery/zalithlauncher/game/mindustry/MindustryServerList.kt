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

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * One source entry from the official Mindustry server list.
 */
data class MindustryServerEntry(
    val name: String,
    val address: String,
    val endpoint: MindustryServerEndpoint,
    val prioritized: Boolean,
    val sourceIndex: Int
)

/**
 * A validated host/port pair while retaining the exact source address for display.
 */
data class MindustryServerEndpoint(
    val displayAddress: String,
    val host: String,
    val port: Int
) {
    companion object {
        const val DEFAULT_PORT = 6567

        /**
         * Parses host names, IPv4 and bracketed IPv6 without rewriting the display value.
         */
        fun parse(raw: String): MindustryServerEndpoint {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Server address is empty" }
            require(!value.contains('/') && !value.contains(' ') && !value.contains('\t')) {
                "Server address contains an invalid character"
            }
            require(!value.contains("://")) { "Server address must not contain a scheme" }

            val (host, port) = when {
                value.startsWith('[') -> {
                    val close = value.indexOf(']')
                    require(close > 1) { "Invalid bracketed IPv6 address: $value" }
                    val hostPart = value.substring(1, close)
                    val suffix = value.substring(close + 1)
                    val portPart = if (suffix.isEmpty()) null else {
                        require(suffix.startsWith(':')) { "Invalid IPv6 port: $value" }
                        suffix.substring(1)
                    }
                    hostPart to parsePort(portPart)
                }
                value.count { it == ':' } > 1 -> value to DEFAULT_PORT
                value.contains(':') -> {
                    val separator = value.lastIndexOf(':')
                    value.substring(0, separator) to parsePort(value.substring(separator + 1))
                }
                else -> value to DEFAULT_PORT
            }

            require(host.isNotBlank()) { "Server host is empty" }
            require(host != "." && host != "..") { "Invalid server host: $host" }
            return MindustryServerEndpoint(value, host, port)
        }

        private fun parsePort(value: String?): Int = value?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT.takeIf { value == null }
            ?: throw IllegalArgumentException("Invalid server port: $value")
    }
}

/**
 * Server information returned by the native Mindustry UDP ping protocol.
 */
data class MindustryServerStatus(
    val online: Boolean,
    val pingMs: Long? = null,
    val players: Int? = null,
    val playerLimit: Int? = null,
    val map: String? = null,
    val version: Int? = null,
    val versionType: String? = null,
    val error: String? = null
)

/**
 * A server entry paired with its latest probe result.
 */
data class MindustryServerRow(
    val entry: MindustryServerEntry,
    val status: MindustryServerStatus
)

/**
 * Strict parser for `servers_v8.json` and `servers_be.json`.
 */
object MindustryServerListParser {
    fun parse(rawJson: String): List<MindustryServerEntry> {
        val root = JsonParser.parseString(rawJson)
        require(root.isJsonArray) { "Mindustry server list must be an array" }

        val result = mutableListOf<MindustryServerEntry>()
        root.asJsonArray.forEachIndexed { sourceIndex, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val objectValue = element.asJsonObject
            val name = objectValue.stringOrNull("name")?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val prioritized = objectValue.booleanOrFalse("prioritized")
            val addresses = objectValue["address"]
                ?.asAddressStrings()
                .orEmpty()

            addresses.forEach { rawAddress ->
                runCatching { MindustryServerEndpoint.parse(rawAddress) }
                    .onSuccess { endpoint ->
                        result += MindustryServerEntry(
                            name = name,
                            address = rawAddress,
                            endpoint = endpoint,
                            prioritized = prioritized,
                            sourceIndex = sourceIndex
                        )
                    }
            }
        }
        return result
    }

    private fun JsonElement.asAddressStrings(): List<String> = when {
        isJsonArray -> asJsonArray.mapNotNull { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
        isJsonPrimitive && asJsonPrimitive.isString -> listOf(asString)
        else -> emptyList()
    }

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun com.google.gson.JsonObject.booleanOrFalse(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}

/**
 * Parsed result of the binary response sent by a Mindustry UDP server.
 */
object MindustryUdpPinger {
    private const val MAX_PACKET_SIZE = 2048
    private const val TIMEOUT_MILLIS = 2_000

    suspend fun ping(
        endpoint: MindustryServerEndpoint,
        timeoutMillis: Int = TIMEOUT_MILLIS
    ): MindustryServerStatus = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMillis.toLong()) {
                coroutineContext.ensureActive()
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMillis
                    val target = InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port)
                    val request = DatagramPacket(byteArrayOf(0xFE.toByte(), 0x01), 2, target)
                    val started = System.nanoTime()
                    socket.send(request)
                    val data = ByteArray(MAX_PACKET_SIZE)
                    val response = DatagramPacket(data, data.size)
                    socket.receive(response)
                    val parsed = parseResponse(response.data, response.length)
                    parsed.copy(pingMs = (System.nanoTime() - started) / 1_000_000L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MindustryServerStatus(online = false, error = e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Decodes the stable server-data layout used by Mindustry's `NetworkIO`.
     */
    fun parseResponse(bytes: ByteArray, length: Int): MindustryServerStatus {
        require(length > 0 && length <= bytes.size) { "Invalid UDP response length: $length" }
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        readMindustryString(buffer, 100)
        val map = readMindustryString(buffer, 64)
        val players = buffer.int
        buffer.int // wave
        val version = buffer.int
        val versionType = readMindustryString(buffer, 32)
        if (!buffer.hasRemaining()) throw IOException("Mindustry response ended before mode")
        buffer.get() // mode ordinal
        val limit = buffer.int
        readMindustryString(buffer, 100) // description
        readMindustryString(buffer, 50) // mode name
        if (buffer.remaining() >= 2) buffer.short // advertised port, optional on old servers
        return MindustryServerStatus(
            online = true,
            players = players.coerceAtLeast(0),
            playerLimit = limit.coerceAtLeast(0),
            map = map,
            version = version,
            versionType = versionType
        )
    }

    private fun readMindustryString(buffer: ByteBuffer, maxBytes: Int): String {
        if (!buffer.hasRemaining()) throw IOException("Mindustry response ended in a string")
        val size = buffer.get().toInt() and 0xFF
        require(size <= maxBytes) { "Mindustry string exceeds $maxBytes bytes" }
        require(size <= buffer.remaining()) { "Mindustry string is truncated" }
        val bytes = ByteArray(size)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

/**
 * Concurrent, cancellable server probing with the release-defined eight-worker limit.
 */
object MindustryServerProbe {
    suspend fun probe(entries: List<MindustryServerEntry>): List<MindustryServerRow> = coroutineScope {
        val permits = Semaphore(8)
        entries.map { entry ->
            async {
                val status = permits.withPermit { MindustryUdpPinger.ping(entry.endpoint) }
                MindustryServerRow(entry, status)
            }
        }.awaitAll().sortedWith(compareBy<MindustryServerRow> {
            if (it.status.online) 0 else 1
        }.thenComparator { left, right ->
            when {
                left.status.online && right.status.online -> {
                    compareValuesBy(
                        left,
                        right,
                        { it.status.pingMs ?: Long.MAX_VALUE },
                        { if (it.entry.prioritized) 0 else 1 },
                        { it.entry.sourceIndex }
                    )
                }
                !left.status.online && !right.status.online ->
                    left.entry.sourceIndex.compareTo(right.entry.sourceIndex)
                else -> 0
            }
        })
    }
}

/**
 * Cached source loader with a fifteen-minute normal TTL and stale-cache fallback.
 */
class MindustryServerListRepository(
    private val cacheRoot: File = PathManager.DIR_MINDUSTRY_CATALOG_CACHE
) {
    data class LoadResult(
        val entries: List<MindustryServerEntry>,
        val sourceUrl: String?,
        val fromCache: Boolean,
        val stale: Boolean
    )

    suspend fun load(
        variant: MindustryVariant,
        forceRefresh: Boolean = false,
        fetcher: suspend (String) -> String = ::fetchStringFromUrl
    ): LoadResult = withContext(Dispatchers.IO) {
        val cache = File(cacheRoot, "servers-${variant.catalogId}.json")
        val timestamp = File(cacheRoot, "servers-${variant.catalogId}.timestamp")
        val cached = runCatching { cache.takeIf { it.isFile }?.readText()?.let(MindustryServerListParser::parse) }
            .getOrNull()
        val cacheAge = System.currentTimeMillis() - timestamp.takeIf { it.isFile }?.readText()?.toLongOrNull().orZero()
        if (!forceRefresh && cached != null && cacheAge in 0..CACHE_TTL_MILLIS) {
            return@withContext LoadResult(cached, null, fromCache = true, stale = false)
        }

        var lastError: Throwable? = null
        for (url in sourceUrls(variant)) {
            require(MindustryCatalog.isPrimaryMirrorUrl(url)) {
                "Server list source must use the configured Xenon mirror: $url"
            }
            try {
                val entries = MindustryServerListParser.parse(fetcher(url))
                cache.parentFile?.mkdirs()
                val temp = File(cache.parentFile, "${cache.name}.tmp")
                temp.writeText(JsonCanonicalizer.toServerListJson(entries))
                if (!temp.renameTo(cache)) {
                    temp.copyTo(cache, overwrite = true)
                    temp.delete()
                }
                timestamp.writeText(System.currentTimeMillis().toString())
                return@withContext LoadResult(entries, url, fromCache = false, stale = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }

        if (cached != null) return@withContext LoadResult(cached, null, fromCache = true, stale = true)
        throw IOException("Failed to load ${variant.displayName} server list", lastError)
    }

    private fun sourceUrls(variant: MindustryVariant): List<String> =
        MindustryCatalog.defaultServerListSources
            .first { it.variant == variant }
            .urls
            .flatMap { MindustryCatalog.serverListUrls(it) }
            .distinct()

    private fun Long?.orZero(): Long = this ?: 0L

    companion object {
        const val CACHE_TTL_MILLIS = 15 * 60 * 1000L
    }
}

/**
 * Minimal JSON writer used for a valid offline cache without storing probe data.
 */
private object JsonCanonicalizer {
    fun toServerListJson(entries: List<MindustryServerEntry>): String {
        val grouped = entries.groupBy { it.sourceIndex }
        return grouped.values.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { group ->
            val first = group.first()
            val addresses = group.joinToString(",") { "\"${escape(it.address)}\"" }
            "  {\"name\":\"${escape(first.name)}\",\"prioritized\":${first.prioritized},\"address\":[$addresses]}"
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
