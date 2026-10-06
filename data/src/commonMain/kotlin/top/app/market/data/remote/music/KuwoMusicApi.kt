package top.app.market.data.remote.music

import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSearchPage
import top.app.market.domain.model.music.MusicSong
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * 酷我音源 API 客户端。
 *
 * 逻辑移植自 HotDownloader（<https://github.com/lerdb/HotDownloader>，Apache-2.0）
 * 的 `crates/hotdownloader-core/src/platforms/kuwo`，仅保留免登录可用的
 * 搜索 / 热搜 / 封面 / 下载直链能力；加密格式（mflac / mgg）因需要 ekey 解密与
 * QMC 解密链路而在解析阶段直接过滤，只保留明文音质。
 *
 * 与原仓库的唯一差异：全部走 https（原仓库为 http）。Android 网络安全策略默认
 * 禁止明文流量，酷我各站（接口 / 下载 CDN / 封面 CDN）均已验证支持 https。
 */
internal class KuwoMusicApi(
    private val client: HttpClient,
    private val json: Json,
) {
    /** 关键词搜索歌曲；[page] 从 1 开始，对应酷我从 0 开始的偏移量 `pn`。 */
    suspend fun searchSongs(keyword: String, page: Int, limit: Int): MusicSearchPage {
        val params = buildList {
            add("client" to "kt")
            add("all" to keyword)
            add("ft" to "music")
            add("encoding" to "utf8")
            add("rformat" to "json")
            add("pcjson" to "1")
            add("itemset" to "web_2013")
            add("vipver" to "1")
            add("newver" to "1")
            add("mobi" to "1")
            add("pn" to (page - 1).coerceAtLeast(0).toString())
            add("rn" to limit.toString())
        }
        val text = fetchText(
            host = "search.kuwo.cn",
            path = "/r.s",
            params = params,
            userAgent = "kwplayer_ar_9.2.2.1",
            errorLabel = "酷我搜索",
        )
        val root = parseObject(text, "酷我搜索")
        val items = root["abslist"] as? JsonArray
            ?: throw MarketException("未找到歌曲列表（abslist 字段缺失）")
        val songs = items.mapNotNull { parseKuwoSong(it) }
        // 酷我 `TOTAL` 是匹配总数，`pn*rn` 为当前偏移；偏移 + 本次返回数 < TOTAL 时还有下一页。
        val total = numberValue(root["TOTAL"])
        val offset = (page - 1).coerceAtLeast(0).toLong() * limit
        return MusicSearchPage(songs = songs, hasMore = offset + songs.size < total)
    }

    /** 热搜关键词（最多 30 个）。 */
    suspend fun hotKeywords(): List<String> {
        val text = fetchText(
            host = "hotword.kuwo.cn",
            path = "/hotword.s",
            params = listOf(
                "prod" to "kwplayer_ar_9.3.0.1",
                "corp" to "kuwo",
                "newver" to "2",
                "vipver" to "9.3.0.1",
                "source" to "kwplayer_ar_9.3.0.1_40.apk",
                "p2p" to "1",
                "notrace" to "0",
                "uid" to "0",
                "plat" to "kwplayer_ar",
                "rformat" to "json",
                "encoding" to "utf8",
                "tabid" to "1",
            ),
            userAgent = "okhttp/4.10.0",
            errorLabel = "酷我热搜",
        )
        val root = parseObject(text, "酷我热搜")
        val items = root["tagvalue"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item -> (item as? JsonObject)?.string("key") }.take(30)
    }

    /** 歌曲封面 URL（纯文本响应），获取失败返回 null。 */
    suspend fun coverUrl(songId: String): String? {
        val text = runCatching {
            fetchText(
                host = "artistpicserver.kuwo.cn",
                path = "/pic.web",
                params = listOf(
                    "corp" to "kuwo",
                    "type" to "rid_pic",
                    "pictype" to "500",
                    "size" to "500",
                    "rid" to songId,
                ),
                userAgent = null,
                referer = KUWO_REFERER,
                errorLabel = "酷我封面",
            )
        }.getOrNull() ?: return null
        val trimmed = text.trim().upgradeToHttps()
        return trimmed.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /**
     * 解析指定音质的下载直链。
     *
     * 酷我下载接口以 `rid` + `br` + `format` 定位资源，`br` 形如 `320kmp3`；
     * 响应需与请求的码率、格式一致，否则视为该音质不可用。
     */
    suspend fun downloadLink(song: MusicSong, quality: MusicQuality): String {
        val rid = song.id.toLongOrNull()?.takeIf { it > 0 }
            ?: throw MarketException("无效的歌曲 ID: ${song.id}")
        val user = Random.nextLong(0, 0xFFFF_FFFFL).toString()
        val androidId = Random.nextBytes(8).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val text = fetchText(
            host = "mobi.kuwo.cn",
            path = "/mobi.s",
            params = listOf(
                "f" to "web",
                "user" to user,
                "android_id" to androidId,
                "source" to "kwplayer_ar_5.1.0.0_B_jiakong_vh.apk",
                "type" to "convert_url_with_sign",
                "from" to "PC",
                "rid" to rid.toString(),
                "br" to "${quality.bitrate}k${quality.format}",
                "format" to quality.format,
            ),
            userAgent = "okhttp/4.10.0",
            referer = KUWO_REFERER,
            errorLabel = "酷我下载链接",
        )
        val root = parseObject(text, "酷我下载链接")
        val code = numberValue(root["code"])
        if (code != 200L) {
            val msg = root["msg"].asString().orEmpty()
            throw MarketException("酷我接口错误: code=$code, msg=$msg")
        }
        val data = root["data"] as? JsonObject ?: throw MarketException("响应缺少 data 字段")
        val responseBitrate = numberValue(data["bitrate"])
        if (responseBitrate != quality.bitrate.toLong()) {
            throw MarketException(
                "bitrate 不匹配: 请求 ${quality.bitrate}，响应 $responseBitrate（可能该音质不可用）",
            )
        }
        val responseFormat = data["format"].asString().orEmpty()
        if (responseFormat != quality.format) {
            throw MarketException("format 不匹配: 请求 ${quality.format}，响应 $responseFormat")
        }
        val url = data["url"].asString().orEmpty()
        if (url.isEmpty()) throw MarketException("下载链接为空")
        return url.upgradeToHttps()
    }

    private fun parseObject(text: String, errorLabel: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw MarketException("$errorLabel 解析响应失败")

    private suspend fun fetchText(
        host: String,
        path: String,
        params: List<Pair<String, String>>,
        userAgent: String?,
        referer: String? = null,
        errorLabel: String,
    ): String {
        val query = params.joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val response = try {
            client.get("https://$host$path?$query") {
                header(HttpHeaders.UserAgent, userAgent ?: DEFAULT_USER_AGENT)
                referer?.let { header(HttpHeaders.Referrer, it) }
            }
        } catch (error: Throwable) {
            throw MarketException("$errorLabel 网络错误: $error")
        }
        if (!response.status.isSuccess()) {
            throw MarketException("$errorLabel 请求失败: HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }
}

private const val KUWO_REFERER = "http://www.kuwo.cn/"

/** 原仓库共享 CLIENT 的默认 UA；未单独指定 UA 的请求（如封面）使用它。 */
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"

/** Android 禁止明文流量，接口返回的 http 直链统一升级为 https（酷我 CDN 已验证支持）。 */
private fun String.upgradeToHttps(): String =
    if (startsWith("http://")) "https://" + removePrefix("http://") else this

private const val ARTIST_SEPARATOR = "、"

/** 加密格式需要 ekey + QMC 解密，不在移植范围内。 */
private val ENCRYPTED_FORMATS = setOf("mflac", "mgg")

private val QUALITY_PATTERN = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")
private val SIZE_PATTERN = Regex("(?i)^([\\d.]+)([kmg]?b)$")

/** 码率 → 音质标识（沿用 lx-music / HotDownloader 的通行叫法）。 */
private val QUALITY_LABELS = mapOf(
    4000 to "Hi-Res",
    2000 to "FLAC",
    320 to "320K MP3",
    300 to "300K OGG",
    192 to "192K OGG",
    128 to "128K MP3",
    100 to "100K OGG",
    48 to "48K AAC",
)

/** 解析搜索 / 歌单接口返回的单曲条目；缺少数字歌曲 ID 时返回 null。 */
internal fun parseKuwoSong(element: JsonElement): MusicSong? {
    val item = element as? JsonObject ?: return null
    // 优先歌单接口返回的小写 `id`，回退搜索接口的 `MUSICRID`（形如 MUSIC_6802907）。
    val id = item.string("id")?.toLongOrNull()
        ?: item.string("MUSICRID", "musicrid")
            ?.removePrefix("MUSIC_")
            ?.toLongOrNull()
        ?: return null
    if (id <= 0) return null
    // 搜索接口用大写字段，歌单 / 详情接口用小写字段（对齐 HotDownloader 的字段归一化）。
    val title = item.string("SONGNAME", "NAME")
        ?: item.string("FSONGNAME")?.trim('"')
        ?: item.string("name")
        ?: ""
    val rawArtist = item.string("ARTIST", "AARTIST", "artist") ?: ""
    val artist = rawArtist.split('&').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(ARTIST_SEPARATOR)
    val album = item.string("ALBUM", "album").orEmpty()
    val duration = item.number("DURATION", "duration").toInt()
    val coverUrl = item.string("albumpic").takeUnless { it.isNullOrBlank() }
        ?: pictureUrl(item["web_albumpic_short"], "albumcover").takeIf { it.isNotEmpty() }
        ?: pictureUrl(item["web_artistpic_short"], "starheads")
    val info = item.string("N_MINFO") ?: item.string("MINFO").orEmpty()
    return MusicSong(
        id = id.toString(),
        title = title,
        artist = artist,
        album = album,
        durationSeconds = duration,
        coverUrl = coverUrl,
        qualities = parseKuwoQualities(info),
    )
}

/**
 * 解析 `N_MINFO` 音质串：`level:xxx,bitrate:NNN,format:xxx,size:NNN` 以分号分隔。
 *
 * 同码率取首次出现的条目；加密格式（mflac / mgg）与未知码率直接跳过——
 * 移植范围不含 ekey 解密与 QMC 解密链路。
 */
internal fun parseKuwoQualities(info: String): List<MusicQuality> {
    if (info.isEmpty()) return emptyList()
    val seen = mutableSetOf<Int>()
    val qualities = QUALITY_PATTERN.findAll(info).mapNotNull { match ->
        val bitrate = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        if (!seen.add(bitrate)) return@mapNotNull null
        val format = match.groupValues[3].lowercase()
        if (format in ENCRYPTED_FORMATS) return@mapNotNull null
        val label = QUALITY_LABELS[bitrate] ?: return@mapNotNull null
        MusicQuality(
            label = label,
            bitrate = bitrate,
            format = format,
            sizeBytes = parseKuwoSize(match.groupValues[4]),
        )
    }.toList()
    return qualities.sortedByDescending { it.bitrate }
}

/** `21.93Mb` 之类的体积转字节数，解析失败返回 0。 */
internal fun parseKuwoSize(raw: String): Long {
    val match = SIZE_PATTERN.matchEntire(raw.trim()) ?: return 0
    val number = match.groupValues[1].toDoubleOrNull() ?: return 0
    val multiplier = when (match.groupValues[2].lowercase()) {
        "kb" -> 1024.0
        "mb" -> 1024.0 * 1024.0
        "gb" -> 1024.0 * 1024.0 * 1024.0
        else -> 1.0
    }
    return (number * multiplier).toLong()
}

/** 酷我图片短路径（形如 `120/40/64/2063938955.jpg`）转 300×300 图片地址。 */
private fun pictureUrl(value: JsonElement?, directory: String): String {
    val raw = value.asString() ?: return ""
    val slash = raw.indexOf('/')
    if (slash <= 0) return ""
    val size = raw.substring(0, slash)
    val path = raw.substring(slash + 1)
    if (path.isEmpty() || size.toIntOrNull() == null) return ""
    return "https://img4.kuwo.cn/star/$directory/300/$path"
}

private fun JsonElement?.asString(): String? =
    this?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

/** 按顺序取第一个非空字符串字段。 */
private fun JsonObject.string(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key].asString()?.takeIf { it.isNotBlank() } }

/** 兼容字符串 / 数字两种 JSON 类型的整数读取。 */
private fun JsonObject.number(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { this[it].asString()?.toLongOrNull() } ?: 0

/** 兼容字符串 / 数字两种 JSON 类型的整数读取。 */
private fun numberValue(element: JsonElement?): Long =
    element.asString()?.toLongOrNull() ?: 0
