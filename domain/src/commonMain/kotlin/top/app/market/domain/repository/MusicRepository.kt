package top.app.market.domain.repository

import top.app.market.domain.model.music.MusicDownloadRequest
import top.app.market.domain.model.music.MusicSearchPage
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSong

/**
 * 音乐源仓库（移植自 HotDownloader 的酷我音源实现）。
 *
 * 只提供无需登录即可使用的公开接口：搜索、热搜、封面与下载直链。
 */
interface MusicRepository {
    /** 关键词搜索歌曲；[page] 从 1 开始。 */
    suspend fun searchSongs(keyword: String, page: Int, limit: Int = PAGE_SIZE): MusicSearchPage

    /** 热搜关键词。 */
    suspend fun hotKeywords(): List<String>

    /** 歌曲封面 URL；无法获取时返回 null。 */
    suspend fun coverUrl(songId: String): String?

    /** 解析 [song] 在 [quality] 档位下的下载直链。 */
    suspend fun downloadLink(song: MusicSong, quality: MusicQuality): String

    companion object {
        const val PAGE_SIZE = 20
    }
}

/** 音频文件下载仓库：把直链流式落盘到平台公共下载目录。 */
interface MusicDownloadRepository {
    /**
     * 下载 [request] 指定的音频，返回落盘位置的展示文本。
     * [onProgress] 报告（已读字节, 总字节），总字节未知时为 -1。
     */
    suspend fun download(
        request: MusicDownloadRequest,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String
}
