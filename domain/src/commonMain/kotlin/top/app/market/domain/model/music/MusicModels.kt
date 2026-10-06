package top.app.market.domain.model.music

/**
 * 音质档位。逻辑移植自 HotDownloader（Apache-2.0）的酷我品质解析。
 *
 * [filename] 沿用 HotDownloader 的任务文件名契约「{bitrate}.{format}」，
 * 下载接口只需 bitrate 与 format 两个参数即可定位资源。
 */
data class MusicQuality(
    /** 展示名（行业通用音质标识，如 320K MP3 / FLAC / Hi-Res）。 */
    val label: String,
    /** 码率档位数字（如 320、2000），并非真实比特率。 */
    val bitrate: Int,
    /** 容器格式（mp3 / flac / ogg / aac …）。 */
    val format: String,
    /** 文件大小（字节），来源接口未知时为 0。 */
    val sizeBytes: Long,
) {
    /** HotDownloader 任务文件名契约，作为下载接口的 filename 参数。 */
    val filename: String get() = "$bitrate.$format"
}

/** 单曲信息（搜索结果条目）。 */
data class MusicSong(
    /** 平台内数字歌曲 ID。 */
    val id: String,
    val title: String,
    /** 多歌手以「、」拼接的展示串。 */
    val artist: String,
    val album: String,
    /** 时长（秒），未知为 0。 */
    val durationSeconds: Int,
    val coverUrl: String,
    /** 可下载的音质档位，按码率从高到低。 */
    val qualities: List<MusicQuality>,
)

/** 搜索结果页。 */
data class MusicSearchPage(
    val songs: List<MusicSong>,
    val hasMore: Boolean,
)

/** 音频下载请求：目标音质与已解析出的直链。 */
data class MusicDownloadRequest(
    val song: MusicSong,
    val quality: MusicQuality,
    val url: String,
)
