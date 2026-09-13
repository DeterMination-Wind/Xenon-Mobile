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

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.movtery.zalithlauncher.BuildKeys
import java.lang.reflect.Type

private val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")
private val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private val COMMIT = Regex("[0-9a-fA-F]{40}")
private val SOURCE_REPOSITORIES = setOf("Anuken/Mindustry", "TinyLake/MindustryX")
private const val MIRROR_ROUTE_MARKER = "/github/"
private const val MIRROR_REPO_ROUTE = "repos"
private const val MIRROR_RAW_ROUTE = "raw"

private fun JsonObject.addIfMissing(name: String, value: com.google.gson.JsonElement) {
    if (!has(name) || get(name).isJsonNull) add(name, value)
}

/** A parsed Xenon mirror route such as `https://host/github/repos/<owner>/<repo>/<path>`. */
private data class MirrorRoute(
    val base: String,
    val kind: String,
    val tail: String
)

private fun parseMirrorRoute(url: String): MirrorRoute? {
    val marker = url.indexOf(MIRROR_ROUTE_MARKER)
    if (marker <= 0) return null

    val remainder = url.substring(marker + MIRROR_ROUTE_MARKER.length)
    val kind = remainder.substringBefore('/')
    if (kind != MIRROR_REPO_ROUTE && kind != MIRROR_RAW_ROUTE) return null

    val tail = remainder.substringAfter('/', "")
    if (tail.isBlank()) return null

    val base = url.substring(0, marker + MIRROR_ROUTE_MARKER.length - 1)
    return MirrorRoute(base, kind, tail)
}

/**
 * Public artifact catalog consumed by Xenon Mobile.
 */
data class MindustryCatalogManifest(
    val schemaVersion: Int = 1,
    val variants: List<String> = MindustryVariant.entries.map { it.catalogId },
    val channels: List<String> = listOf("stable", "be", "dev"),
    val mirrors: List<CatalogMirror> = MindustryCatalog.defaultMirrors,
    val artifacts: List<MindustryArtifact> = emptyList()
) {
    fun validate(): MindustryCatalogManifest {
        require(schemaVersion >= 1) { "Unsupported catalog schema: $schemaVersion" }
        val keys = HashSet<String>()
        artifacts.forEach { artifact ->
            require(keys.add(artifact.identityKey())) {
                "Duplicate current artifact key: ${artifact.identityKey()}"
            }
            require(artifact.id.isNotBlank()) { "Artifact id must not be blank" }
            require(artifact.channel.isNotBlank()) { "Artifact ${artifact.id} has no channel" }
            require(artifact.versionName.isNotBlank()) { "Artifact ${artifact.id} has no versionName" }
            require(artifact.build > 0) { "Artifact ${artifact.id} has an invalid build" }
            require(artifact.buildType.isNotBlank()) { "Artifact ${artifact.id} has no buildType" }
            require(artifact.urls.isNotEmpty()) { "Artifact ${artifact.id} has no urls" }
            require(artifact.urls.all { MindustryCatalog.isCatalogUrl(it) }) {
                "Artifact ${artifact.id} must use HTTP(S) or the Xenon server mirror"
            }
            require(artifact.sha256.matches(HEX_SHA256)) {
                "Artifact ${artifact.id} has an invalid sha256"
            }
            require(artifact.size > 0L) { "Artifact ${artifact.id} has an invalid size" }
            require(artifact.nativeProfile == MindustryCatalog.ARM64_NATIVE_PROFILE) {
                "Only arm64-v8a artifacts are accepted in Xenon Mobile v1"
            }
            require(artifact.sourceRepo.matches(REPOSITORY) && artifact.sourceRepo in SOURCE_REPOSITORIES) {
                "Artifact ${artifact.id} has an invalid sourceRepo"
            }
            require(artifact.sourceCommit.matches(COMMIT) && artifact.sourceCommit.any { it != '0' }) {
                "Artifact ${artifact.id} has an invalid sourceCommit"
            }
            require(artifact.releaseTag.isNotBlank()) {
                "Artifact ${artifact.id} has no releaseTag"
            }
            require(artifact.minLauncherVersion > 0) {
                "Artifact ${artifact.id} has an invalid minLauncherVersion"
            }
            if (artifact.backend == MindustryBackend.APK) {
                val slot = artifact.slot ?: error("APK artifact ${artifact.id} must specify slot")
                require(ApkCloneSlot.isValidSlot(artifact.variant, slot)) {
                    "Artifact ${artifact.id} uses invalid APK slot $slot"
                }
                require(artifact.packageName == ApkCloneSlot(artifact.variant, slot).packageName) {
                    "Artifact ${artifact.id} has a packageName that does not match its slot"
                }
                require(artifact.versionCode != null && artifact.versionCode > 0L) {
                    "APK artifact ${artifact.id} must specify a positive versionCode"
                }
                require(artifact.signatureSha256.isNotEmpty() && artifact.signatureSha256.all { it.matches(HEX_SHA256) }) {
                    "APK artifact ${artifact.id} has an invalid signature digest"
                }
            } else {
                require(artifact.slot == null) { "JAR artifact ${artifact.id} must not specify slot" }
                require(artifact.packageName == null) { "JAR artifact ${artifact.id} must not specify packageName" }
                require(artifact.versionCode == null) { "JAR artifact ${artifact.id} must not specify versionCode" }
            }
        }
        return this
    }

    fun artifactsFor(
        variant: MindustryVariant,
        backend: MindustryBackend
    ): List<MindustryArtifact> =
        artifacts.filter { it.variant == variant && it.backend == backend }
}

data class CatalogMirror(
    val id: String,
    val baseUrl: String,
    val priority: Int = 0
)

data class MindustryArtifact(
    val id: String,
    val variant: MindustryVariant,
    val channel: String,
    val backend: MindustryBackend,
    val slot: Int? = null,
    val versionName: String,
    val build: Int,
    val buildType: String,
    val javaVersion: Int = 17,
    val urls: List<String>,
    val sha256: String,
    val size: Long,
    val nativeProfile: String = MindustryCatalog.ARM64_NATIVE_PROFILE,
    val mgVersion: String? = null,
    val minLauncherVersion: Int = 1,
    val changelog: String? = null,
    val packageName: String? = null,
    val versionCode: Long? = null,
    val sourceRepo: String = "unknown/unknown",
    val sourceCommit: String = "0000000000000000000000000000000000000000",
    val releaseTag: String = "unreleased",
    val signatureSha256: List<String> = emptyList()
) {
    fun identityKey(): String = when (backend) {
        MindustryBackend.APK -> "${variant.catalogId}:apk:${slot ?: error("APK artifact has no slot")}"
        MindustryBackend.JAR -> "${variant.catalogId}:jar"
    }
}

data class ServerListSource(
    val variant: MindustryVariant,
    val channel: String,
    val urls: List<String>
)

/**
 * Static catalog helpers and default mirror ordering.
 */
object MindustryCatalog {
    const val ARM64_NATIVE_PROFILE = "arm64-v8a"

    /**
     * Default Xenon mirror base, reached by the mirror's direct IP.
     *
     * The `mindustry.men` domains are rejected by the hosting provider's ICP filing filter (the
     * server answers the "Non-compliance ICP Filing" page), while the same host keeps serving the
     * mirror correctly when it is addressed by IP. The Android app permits cleartext traffic, so
     * the plain HTTP IP endpoint is usable. A build can still override the base through the
     * `mindustry_mirror` Gradle property.
     */
    const val DEFAULT_SERVER_MIRROR = "http://121.199.60.4/github"

    /** Domain mirror, kept as the second candidate behind the direct IP. */
    const val DOMAIN_SERVER_MIRROR = "https://play.mindustry.men/github"

    /** Xenon mirror base used at runtime. This mirror is always the first download source. */
    val PRIMARY_SERVER_MIRROR: String =
        BuildKeys.MINDUSTRY_MIRROR_BASE.trim().trimEnd('/').ifBlank { DEFAULT_SERVER_MIRROR }

    const val GITHUB_WEB_HOST = "https://github.com"
    const val GITHUB_RAW_HOST = "https://raw.githubusercontent.com"
    const val GITHUB_API_HOST = "https://api.github.com"

    const val SERVER_LIST_REPO = "Anuken/MindustryServerList"
    const val SERVER_LIST_BRANCH = "main"

    private val serverListGithubRaw = "$GITHUB_RAW_HOST/$SERVER_LIST_REPO/$SERVER_LIST_BRANCH"
    private val serverListMirrorPrefix = "$PRIMARY_SERVER_MIRROR/repos/$SERVER_LIST_REPO/"

    const val DEFAULT_CATALOG_REPO = "DeterMination-Wind/Xenon-Mobile"
    const val DEFAULT_CATALOG_BRANCH = "main"
    const val DEFAULT_CATALOG_PATH = "catalog/xenon-mobile-catalog.json"

    val gson = GsonBuilder()
        .registerTypeAdapter(MindustryVariant::class.java, MindustryVariantAdapter)
        .registerTypeAdapter(MindustryBackend::class.java, MindustryBackendAdapter)
        .setPrettyPrinting()
        .create()

    val defaultMirrors: List<CatalogMirror> = buildList {
        add(CatalogMirror(id = "xenon-ip", baseUrl = PRIMARY_SERVER_MIRROR, priority = 0))
        if (DOMAIN_SERVER_MIRROR != PRIMARY_SERVER_MIRROR) {
            add(CatalogMirror(id = "xenon-domain", baseUrl = DOMAIN_SERVER_MIRROR, priority = 1))
        }
    }

    val defaultServerListSources: List<ServerListSource> = listOf(
        ServerListSource(
            variant = MindustryVariant.VANILLA,
            channel = "stable",
            urls = listOf(serverListMirrorUrl("servers_v8.json"))
        ),
        ServerListSource(
            variant = MindustryVariant.BE,
            channel = "be",
            urls = listOf(serverListMirrorUrl("servers_be.json"))
        ),
        ServerListSource(
            variant = MindustryVariant.MINDUSTRY_X,
            channel = "stable",
            urls = listOf(serverListMirrorUrl("servers_v8.json"))
        )
    )

    fun parse(rawJson: String): MindustryCatalogManifest {
        val root = JsonParser.parseString(rawJson).asJsonObject
        root.addIfMissing("variants", JsonArray().apply {
            MindustryVariant.entries.forEach { add(it.catalogId) }
        })
        root.addIfMissing("channels", JsonArray().apply {
            add("stable")
            add("be")
            add("dev")
        })
        root.addIfMissing("mirrors", gson.toJsonTree(defaultMirrors))
        root.addIfMissing("artifacts", JsonArray())

        root.getAsJsonArray("artifacts").forEach { element ->
            val artifact = element.asJsonObject
            artifact.addIfMissing("id", JsonPrimitive(""))
            artifact.addIfMissing("variant", JsonPrimitive(""))
            artifact.addIfMissing("channel", JsonPrimitive(""))
            artifact.addIfMissing("backend", JsonPrimitive(""))
            artifact.addIfMissing("versionName", JsonPrimitive(""))
            artifact.addIfMissing("build", JsonPrimitive(0))
            artifact.addIfMissing("buildType", JsonPrimitive(""))
            artifact.addIfMissing("javaVersion", JsonPrimitive(17))
            artifact.addIfMissing("urls", JsonArray())
            artifact.addIfMissing("sha256", JsonPrimitive(""))
            artifact.addIfMissing("size", JsonPrimitive(0))
            artifact.addIfMissing("nativeProfile", JsonPrimitive(ARM64_NATIVE_PROFILE))
            artifact.addIfMissing("minLauncherVersion", JsonPrimitive(1))
            artifact.addIfMissing("sourceRepo", JsonPrimitive(""))
            artifact.addIfMissing("sourceCommit", JsonPrimitive(""))
            artifact.addIfMissing("releaseTag", JsonPrimitive(""))
            artifact.addIfMissing("signatureSha256", JsonArray())
        }

        return gson.fromJson(root, MindustryCatalogManifest::class.java).validate()
    }

    fun defaultManifestUrls(
        repo: String = DEFAULT_CATALOG_REPO,
        branch: String = DEFAULT_CATALOG_BRANCH,
        path: String = DEFAULT_CATALOG_PATH,
        mirrors: List<CatalogMirror> = defaultMirrors
    ): List<String> {
        val sourceUrl = "$GITHUB_RAW_HOST/${repo.trim('/')}/${branch.trim('/')}/${path.trimStart('/')}"
        return downloadCandidateUrls(sourceUrl, mirrors)
    }

    fun artifactDownloadUrls(
        artifact: MindustryArtifact,
        manifest: MindustryCatalogManifest
    ): List<String> {
        val mirrors = (defaultMirrors + manifest.mirrors).distinctBy { it.baseUrl.trimEnd('/') }
        return artifact.urls
            .flatMap { url -> downloadCandidateUrls(url, mirrors) }
            .distinct()
    }

    fun serverMirrorRepoUrl(repo: String, path: String): String =
        "$PRIMARY_SERVER_MIRROR/repos/${repo.trim('/')}/${path.trimStart('/')}"

    private fun serverListMirrorUrl(path: String): String =
        "$serverListMirrorPrefix${path.trimStart('/')}"

    /** Returns every configured mirror first and the canonical GitHub raw URL as the last resort. */
    fun serverListUrls(url: String): List<String> {
        val path = when {
            url.startsWith(serverListMirrorPrefix) -> url.removePrefix(serverListMirrorPrefix)
            url.startsWith("$serverListGithubRaw/") -> url.removePrefix("$serverListGithubRaw/")
            else -> return emptyList()
        }.trimStart('/').substringBefore('?').takeIf { it.isNotBlank() } ?: return emptyList()

        val mirrored = defaultMirrors.sortedBy { it.priority }
            .map { "${it.baseUrl.trimEnd('/')}/repos/$SERVER_LIST_REPO/$path" }
        return (mirrored + "$serverListGithubRaw/$path").distinct()
    }

    /** Returns only the Xenon mirror URLs for a supported canonical GitHub source URL. */
    fun mirrorUrls(url: String, mirrors: List<CatalogMirror> = defaultMirrors): List<String> {
        if (isPrimaryMirrorUrl(url)) return listOf(url)

        val ordered = mirrors.sortedBy { it.priority }
        val mirrored = ordered.mapNotNull { mirror ->
            when {
                url.startsWith("$GITHUB_WEB_HOST/") -> {
                    val path = url.removePrefix("$GITHUB_WEB_HOST/").trimStart('/')
                    "${mirror.baseUrl.trimEnd('/')}/repos/$path"
                }
                url.startsWith("$GITHUB_RAW_HOST/") -> {
                    val path = url.removePrefix("$GITHUB_RAW_HOST/").trimStart('/')
                    "${mirror.baseUrl.trimEnd('/')}/raw/$path"
                }
                else -> null
            }
        }

        return mirrored.distinct()
    }

    /**
     * Ordered download candidates for [url]: the configured Xenon mirror first, then any
     * published mirror URL, and finally the canonical GitHub source. GitHub is never used
     * before a mirror, so a reachable mirror always wins.
     */
    fun downloadCandidateUrls(
        url: String,
        mirrors: List<CatalogMirror> = defaultMirrors
    ): List<String> {
        val route = parseMirrorRoute(url)
        val candidates = when {
            isGithubFallbackUrl(url) -> mirrorUrls(url, mirrors)
            route != null -> {
                val bases = (listOf(PRIMARY_SERVER_MIRROR) + mirrors.sortedBy { it.priority }.map { it.baseUrl.trimEnd('/') })
                    .distinct()
                (bases.map { "$it/${route.kind}/${route.tail}" } + url).distinct()
            }
            url.startsWith("http://") || url.startsWith("https://") -> listOf(url)
            else -> emptyList()
        }
        return (candidates + listOfNotNull(githubFallbackUrl(url))).distinct()
    }

    /** True for a URL served by the currently configured Xenon mirror. */
    fun isPrimaryMirrorUrl(url: String): Boolean =
        url == PRIMARY_SERVER_MIRROR || url.startsWith("$PRIMARY_SERVER_MIRROR/")

    /** True for any Xenon mirror route, including a mirror different from the configured one. */
    fun isMirrorRouteUrl(url: String): Boolean = parseMirrorRoute(url) != null

    /** True for canonical GitHub hosts that can serve a download directly. */
    fun isGithubFallbackUrl(url: String): Boolean =
        url.startsWith("$GITHUB_WEB_HOST/") || url.startsWith("$GITHUB_RAW_HOST/")

    /** True for a Xenon mirror URL or a canonical GitHub URL. */
    fun isAllowedCatalogSource(url: String): Boolean =
        isPrimaryMirrorUrl(url) || isMirrorRouteUrl(url) || isGithubFallbackUrl(url)

    /**
     * Reverse maps a mirror URL to the canonical GitHub URL serving the same resource.
     * `raw` routes keep the exact ref, release assets map to `github.com` and
     * `releases/latest` maps to the GitHub API. Repository routes without a ref resolve
     * through `HEAD`.
     */
    fun githubFallbackUrl(url: String): String? {
        if (isGithubFallbackUrl(url)) return url

        val route = parseMirrorRoute(url) ?: return null
        if (route.kind == MIRROR_RAW_ROUTE) return "$GITHUB_RAW_HOST/${route.tail}"

        return when {
            route.tail.endsWith("releases/latest") -> "$GITHUB_API_HOST/repos/${route.tail}"
            route.tail.contains("/releases/download/") -> "$GITHUB_WEB_HOST/${route.tail}"
            else -> {
                val segments = route.tail.split('/', limit = 3)
                if (segments.size < 3) null
                else "$GITHUB_RAW_HOST/${segments[0]}/${segments[1]}/HEAD/${segments[2]}"
            }
        }
    }

    fun isCatalogUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://") || isPrimaryMirrorUrl(url)

    private object MindustryVariantAdapter : JsonDeserializer<MindustryVariant>, JsonSerializer<MindustryVariant> {
        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): MindustryVariant {
            val raw = json.asString
            return MindustryVariant.entries.firstOrNull {
                it.catalogId.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
            } ?: throw JsonParseException("Unknown Mindustry variant: $raw")
        }

        override fun serialize(
            src: MindustryVariant,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement = JsonPrimitive(src.catalogId)
    }

    private object MindustryBackendAdapter : JsonDeserializer<MindustryBackend>, JsonSerializer<MindustryBackend> {
        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): MindustryBackend {
            val raw = json.asString
            return MindustryBackend.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw JsonParseException("Unknown Mindustry backend: $raw")
        }

        override fun serialize(
            src: MindustryBackend,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement = JsonPrimitive(src.name.lowercase())
    }
}
