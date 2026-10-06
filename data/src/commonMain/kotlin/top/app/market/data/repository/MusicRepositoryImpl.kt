package top.app.market.data.repository

import top.app.market.data.remote.music.KuwoMusicApi
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSearchPage
import top.app.market.domain.model.music.MusicSong
import top.app.market.domain.repository.MusicRepository

/**
 * 音乐源仓库实现。当前聚合酷我音源；后续接其他音源时在此合并结果并归并分页。
 */
internal class MusicRepositoryImpl(
    private val kuwo: KuwoMusicApi,
) : MusicRepository {
    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): MusicSearchPage =
        kuwo.searchSongs(keyword, page, limit)

    override suspend fun hotKeywords(): List<String> = kuwo.hotKeywords()

    override suspend fun coverUrl(songId: String): String? = kuwo.coverUrl(songId)

    override suspend fun downloadLink(song: MusicSong, quality: MusicQuality): String =
        kuwo.downloadLink(song, quality)
}
