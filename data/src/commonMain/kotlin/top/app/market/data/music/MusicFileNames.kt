package top.app.market.data.music

/** 生成「标题 - 歌手.格式」的下载文件名，并清理各平台文件系统的非法字符。 */
internal fun musicFileName(title: String, artist: String, format: String): String {
    val base = listOf(title.trim(), artist.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" - ")
        .ifBlank { "music" }
        .replace(INVALID_FILE_NAME, "_")
        .trim()
    val extension = format.lowercase().ifBlank { "mp3" }
    return "$base.$extension"
}

private val INVALID_FILE_NAME = Regex("[\\\\/:*?\"<>|\\r\\n]")
