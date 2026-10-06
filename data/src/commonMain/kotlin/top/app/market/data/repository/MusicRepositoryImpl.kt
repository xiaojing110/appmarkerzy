package top.app.market.data.repository

import top.app.market.data.music.downloadQualityCandidates
import top.app.market.data.remote.music.KuwoMusicApi
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSearchPage
import top.app.market.domain.model.music.MusicSong
import top.app.market.domain.repository.MusicRepository
import kotlinx.coroutines.CancellationException

/**
 * 音乐源仓库实现。当前聚合酷我音源；后续接其他音源时在此合并结果并归并分页。
 *
 * 取下载直链带「自动降级」（对齐 HotDownloader）：目标音质缺失或取链失败时，
 * 按歌曲音质列表顺序尝试其余档位，直到取得链接或候选耗尽。
 */
internal class MusicRepositoryImpl(
    private val kuwo: KuwoMusicApi,
) : MusicRepository {
    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): MusicSearchPage =
        kuwo.searchSongs(keyword, page, limit)

    override suspend fun hotKeywords(): List<String> = kuwo.hotKeywords()

    override suspend fun coverUrl(songId: String): String? = kuwo.coverUrl(songId)

    override suspend fun downloadLink(song: MusicSong, quality: MusicQuality): String {
        var firstError: Throwable? = null
        for (candidate in downloadQualityCandidates(quality, song)) {
            try {
                return kuwo.downloadLink(song, candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
            }
        }
        throw firstError ?: MarketException("该歌曲没有可用音质")
    }
}
