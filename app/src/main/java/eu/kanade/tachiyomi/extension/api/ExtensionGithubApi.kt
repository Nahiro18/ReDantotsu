package eu.kanade.tachiyomi.extension.api

import ani.dantotsu.asyncMap
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.novel.AvailableNovelSources
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.json.JSONArray
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private fun cleanRepoUrl(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/repo.json")
            .removeSuffix("/plugins.min.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    private fun List<ExtensionSourceJsonObject>.toAnimeExtensionSources(): List<AvailableAnimeSources> {
        return this.map {
            AvailableAnimeSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toAnimeExtensions(repository: String): List<AnimeExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.ANIME_LIB_VERSION_MIN && libVersion <= ExtensionLoader.ANIME_LIB_VERSION_MAX
            }
            .map {
                AnimeExtension.Available(
                    name = it.name.removePrefix("Aniyomi: ").removePrefix("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toAnimeExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = it.iconUrl ?: "$cleanRepo/icon/${it.pkg}.png",
                )
            }
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (this.size < 2) return this
        val isGzip = (this[0].toInt() and 0xFF == 0x1F) && (this[1].toInt() and 0xFF == 0x8B)
        if (!isGzip) return this
        return try {
            ByteArrayInputStream(this).source().gzip().buffer().readByteArray()
        } catch (_: Throwable) {
            this
        }
    }

    private fun updateStoreUrl(oldUrl: String, newUrl: String, mediaType: MediaType) {
        val prefName = when (mediaType) {
            MediaType.ANIME -> PrefName.AnimeExtensionRepos
            MediaType.MANGA -> PrefName.MangaExtensionRepos
            MediaType.NOVEL -> PrefName.NovelExtensionRepos
        }
        val current = PrefManager.getVal<Set<String>>(prefName)
        if (current.contains(oldUrl)) {
            PrefManager.setVal(prefName, current.minus(oldUrl).plus(newUrl))
        }
    }

    // Fetches one repo trying every known index format:
    // legacy JSON array (index.min.json) and the modern Mihon store
    // (repo.json / index.pb, JSON or protobuf, inline or via extensionListUrl).
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchExtensions(
        repoUrl: String,
        mediaType: MediaType,
        originalUrl: String = repoUrl,
    ): List<ExtensionJsonObject> {
        val cleanBase = cleanRepoUrl(repoUrl)
        val candidateUrls = mutableListOf<String>()
        val trimmed = repoUrl.trim()
        if (trimmed.endsWith(".json") || trimmed.endsWith(".pb")) {
            candidateUrls.add(trimmed)
        }
        val defaultEndpoints = when (mediaType) {
            MediaType.ANIME -> listOf(
                "$cleanBase/index.min.json",
                "$cleanBase/repo.json",
                "$cleanBase/index.json",
                "$cleanBase/index.pb",
            )
            MediaType.MANGA -> listOf(
                "$cleanBase/index.pb",
                "$cleanBase/repo.json",
                "$cleanBase/index.min.json",
                "$cleanBase/index.json",
            )
            MediaType.NOVEL -> listOf(
                "$cleanBase/index.json",
                "$cleanBase/index.min.json",
                "$cleanBase/repo.json",
                "$cleanBase/index.pb",
            )
        }
        for (endpoint in defaultEndpoints) {
            if (!candidateUrls.contains(endpoint)) candidateUrls.add(endpoint)
        }

        for (targetUrl in candidateUrls) {
            try {
                val response = try {
                    networkService.client.newCall(GET(targetUrl)).awaitSuccess()
                } catch (_: Throwable) {
                    continue
                }
                val rawBytes = try {
                    response.body.bytes()
                } catch (_: Throwable) {
                    continue
                }
                if (rawBytes.isEmpty()) continue
                val bytes = rawBytes.decompressIfGzipped()
                if (bytes.isEmpty()) continue

                when (bytes[0]) {
                    0x5B.toByte() -> { // '[' JSON array of extensions
                        val list = runCatching {
                            json.decodeFromString<List<ExtensionJsonObject>>(bytes.toString(Charsets.UTF_8))
                        }.getOrNull()
                        if (!list.isNullOrEmpty()) return list
                    }
                    0x7B.toByte() -> { // '{' JSON object: store or legacy repo.json pointer
                        val bodyString = bytes.toString(Charsets.UTF_8)
                        if (bodyString.contains("\"index_v2\"")) {
                            val nextUrl = runCatching {
                                json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                            }.getOrNull()?.indexV2
                            if (nextUrl != null) {
                                updateStoreUrl(originalUrl, nextUrl, mediaType)
                                return fetchExtensions(nextUrl, mediaType, originalUrl)
                            }
                        }
                        val isMetaOnly = bodyString.contains("\"meta\"") &&
                            !bodyString.contains("\"extensionList\"") &&
                            !bodyString.contains("\"extensions\"")
                        if (!isMetaOnly) {
                            val list = runCatching {
                                json.decodeFromString<NetworkExtensionStore>(bodyString)
                            }.getOrNull()?.toExtensionJsonObjects(mediaType, cleanRepoUrl(targetUrl))
                            if (!list.isNullOrEmpty()) return list
                        }
                    }
                    else -> { // protobuf store
                        val list = runCatching {
                            ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
                        }.getOrNull()?.toExtensionJsonObjects(mediaType, cleanRepoUrl(targetUrl))
                        if (!list.isNullOrEmpty()) return list
                    }
                }
            } catch (e: Throwable) {
                Logger.log("Failed candidate $targetUrl for $repoUrl: $e")
            }
        }
        return emptyList()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun NetworkExtensionStore.toExtensionJsonObjects(
        mediaType: MediaType,
        storeBase: String,
    ): List<ExtensionJsonObject>? {
        val resolvedList = if (extensionListUrl != null) {
            val listUrl = if (extensionListUrl.startsWith("http")) {
                extensionListUrl
            } else {
                "$storeBase/${extensionListUrl.removePrefix("/")}"
            }
            val listBytes = runCatching {
                networkService.client.newCall(GET(listUrl)).awaitSuccess().body.bytes()
            }.getOrNull()?.decompressIfGzipped() ?: return null
            if (listBytes.isEmpty()) return null
            if (listBytes[0] == 0x7B.toByte()) {
                runCatching {
                    json.decodeFromString<NetworkExtensionStore.ExtensionList>(listBytes.toString(Charsets.UTF_8))
                }.getOrNull()
            } else {
                runCatching {
                    ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(listBytes)
                }.getOrNull()
            }
        } else {
            extensionList
        } ?: return null
        if (resolvedList.extensions.isEmpty()) return null
        val prefix = when (mediaType) {
            MediaType.ANIME -> "Aniyomi: "
            MediaType.MANGA -> "Tachiyomi: "
            else -> ""
        }
        return resolvedList.extensions.map { ext ->
            val prefixedName = if (prefix.isNotEmpty() && !ext.name.startsWith(prefix)) "$prefix${ext.name}" else ext.name
            ExtensionJsonObject(
                name = prefixedName,
                pkg = ext.packageName,
                apk = ext.resources.apkUrl,
                lang = ext.sources.firstOrNull()?.language ?: "all",
                code = ext.versionCode,
                version = ext.versionName.ifBlank { "1.0" },
                nsfw = if (ext.contentWarning == NetworkExtensionStore.ContentWarning.NSFW ||
                    ext.contentWarning == NetworkExtensionStore.ContentWarning.MIXED
                ) 1 else 0,
                sources = ext.sources.map { src ->
                    ExtensionSourceJsonObject(
                        id = src.id,
                        lang = src.language,
                        name = src.name,
                        baseUrl = src.homeUrl,
                    )
                },
                iconUrl = ext.resources.iconUrl.ifBlank { null },
                extensionLib = ext.extensionLib.ifBlank { null },
            )
        }
    }

    private fun <T> List<T>.distinctByPkg(selector: (T) -> String): List<T> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(selector(it)) }
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> {
        return withIOContext {
            val repos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toList()
            repos.asyncMap { repo ->
                try {
                    var list = fetchExtensions(repo, MediaType.ANIME)
                    if (list.isEmpty()) {
                        val fallback = fallbackRepoUrl(repo)
                        if (fallback != null) list = fetchExtensions(fallback, MediaType.ANIME)
                    }
                    list.toAnimeExtensions(repo)
                } catch (e: Throwable) {
                    Logger.log("Failed to get anime extensions from $repo")
                    Logger.log(e)
                    emptyList()
                }
            }.flatten().distinctByPkg { it.pkgName }
        }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
        }
    }

    private fun List<ExtensionSourceJsonObject>.toMangaExtensionSources(): List<AvailableMangaSources> {
        return this.map {
            AvailableMangaSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toMangaExtensions(repository: String): List<MangaExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.MANGA_LIB_VERSION_MIN && libVersion <= ExtensionLoader.MANGA_LIB_VERSION_MAX
            }
            .map {
                MangaExtension.Available(
                    name = it.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toMangaExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = it.iconUrl ?: "$cleanRepo/icon/${it.pkg}.png",
                )
            }
    }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            val repos = PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).toList()
            repos.asyncMap { repo ->
                try {
                    var list = fetchExtensions(repo, MediaType.MANGA)
                    if (list.isEmpty()) {
                        val fallback = fallbackRepoUrl(repo)
                        if (fallback != null) list = fetchExtensions(fallback, MediaType.MANGA)
                    }
                    list.toMangaExtensions(repo)
                } catch (e: Throwable) {
                    Logger.log("Failed to get manga extensions from $repo")
                    Logger.log(e)
                    emptyList()
                }
            }.flatten().distinctByPkg { it.pkgName }
        }
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
        }
    }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> {
        return withIOContext {
            val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toList()
            repos.asyncMap { repo ->
                val found = arrayListOf<NovelExtension.Available>()
                try {
                    var list = fetchExtensions(repo, MediaType.NOVEL)
                    if (list.isEmpty()) {
                        val fallback = fallbackRepoUrl(repo)
                        if (fallback != null) list = fetchExtensions(fallback, MediaType.NOVEL)
                    }
                    found.addAll(list.toNovelExtensions(repo))

                    // LNReader repos are not Keiyoushi/Mihon repos: keep plugins.min.json fallback
                    if (list.isEmpty() && (repo.contains("plugins.min.json") || repo.contains("lnreader", ignoreCase = true))) {
                        try {
                            val pluginUrl = if (repo.contains("plugins.min.json")) repo else "${repo.trimEnd('/')}/plugins.min.json"
                            val pluginResponse = networkService.client.newCall(GET(pluginUrl)).awaitSuccess()
                            val arr = JSONArray(pluginResponse.body.string())
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val pkgName = "lnreader.plugin.${obj.getString("id")}"
                                if (found.none { it.pkgName == pkgName }) {
                                    val iconUrl = obj.optString("iconUrl", "")
                                    found.add(
                                        NovelExtension.Available(
                                            name = obj.getString("name"),
                                            pkgName = pkgName,
                                            versionName = obj.getString("version"),
                                            versionCode = 1,
                                            repository = repo,
                                            sources = emptyList(),
                                            iconUrl = iconUrl.ifBlank {
                                                "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/icon/default.png"
                                            },
                                            apkName = obj.getString("url"),
                                        )
                                    )
                                }
                            }
                        } catch (e2: Throwable) {
                            Logger.log("Failed to parse $repo as LNReader repo")
                            Logger.log(e2)
                        }
                    }
                } catch (e: Throwable) {
                    Logger.log("Failed to get novel extensions from $repo")
                    Logger.log(e)
                }
                found.toList()
            }.flatten().distinctByPkg { it.pkgName }
        }
    }

    private fun List<ExtensionJsonObject>.toNovelExtensions(repository: String): List<NovelExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return filter { !it.apk.isNullOrBlank() && !it.pkg.isNullOrBlank() && it.apk.endsWith(".apk", ignoreCase = true) }
            .mapNotNull { extension ->
                val sources = extension.sources?.map { source ->
                    ExtensionSourceJsonObject(
                        source.id,
                        source.lang,
                        source.name,
                        source.baseUrl,
                    )
                }
                NovelExtension.Available(
                    extension.name,
                    extension.pkg,
                    extension.apk,
                    extension.code,
                    repository,
                    sources?.toNovelSources() ?: emptyList(),
                    extension.iconUrl ?: "$cleanRepo/icon/${extension.pkg}.png",
                )
            }
    }

    private fun List<ExtensionSourceJsonObject>.toNovelSources(): List<AvailableNovelSources> {
        return map { source ->
            AvailableNovelSources(
                source.id,
                source.lang,
                source.name,
                source.baseUrl,
            )
        }
    }

    fun getNovelApkUrl(extension: NovelExtension.Available): String {
        return "${cleanRepoUrl(extension.repository)}/apk/${extension.pkgName.removePrefix("/")}.apk"
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        var fallbackRepoUrl = "https://gcore.jsdelivr.net/gh/"
        val strippedRepoUrl = cleanRepoUrl(repoUrl)
            .removePrefix("https://")
            .removePrefix("http://")
        val repoUrlParts = strippedRepoUrl.split("/")
        if (repoUrlParts.size < 3) {
            return null
        }
        val repoOwner = repoUrlParts[1]
        val repoName = repoUrlParts[2]
        fallbackRepoUrl += "$repoOwner/$repoName"
        val repoBranch = if (repoUrlParts.size > 3) {
            repoUrlParts[3]
        } else {
            "main"
        }
        fallbackRepoUrl += "@$repoBranch"
        return fallbackRepoUrl
    }
}

object LongOrStringSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongOrString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return try {
            decoder.decodeLong()
        } catch (_: Throwable) {
            decoder.decodeString().toLongOrNull() ?: 0L
        }
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) {
            element.longOrNull ?: element.content.toLongOrNull() ?: 0L
        } else {
            0L
        }
    }
}

object IntOrStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntOrString", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return try {
            decoder.decodeInt()
        } catch (_: Throwable) {
            decoder.decodeString().toIntOrNull() ?: 0
        }
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) {
            element.intOrNull ?: element.content.toIntOrNull() ?: 0
        } else {
            0
        }
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "all",
    @Serializable(with = LongOrStringSerializer::class)
    val code: Long = 0,
    val version: String = "1.0",
    @Serializable(with = IntOrStringSerializer::class)
    val nsfw: Int = 0,
    @Serializable(with = IntOrStringSerializer::class)
    val hasReadme: Int = 0,
    @Serializable(with = IntOrStringSerializer::class)
    val hasChangelog: Int = 0,
    val sources: List<ExtensionSourceJsonObject>? = null,
    @JsonNames("icon", "iconUrl")
    val iconUrl: String? = null,
    val extensionLib: String? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    @Serializable(with = LongOrStringSerializer::class)
    val id: Long = 0L,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)

private fun ExtensionJsonObject.extractLibVersion(): Double {
    extensionLib?.toDoubleOrNull()?.let { return it }
    val parts = version.split('.')
    if (parts.size >= 2) {
        val major = parts[0].toDoubleOrNull() ?: return 1.0
        // New anime ext-lib scheme uses plain majors (12, 13...) — keep them as-is
        if (major >= 10.0) return major
        return "${parts[0]}.${parts[1]}".toDoubleOrNull() ?: major
    }
    return version.toDoubleOrNull() ?: 1.0
}
