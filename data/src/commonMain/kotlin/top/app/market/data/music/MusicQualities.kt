package top.app.market.data.music

import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSong

/**
 * 下载降级候选顺序（移植自 HotDownloader 的「自动降级」）：
 * 先试目标音质，取链接失败后按歌曲音质列表顺序（码率从高到低）尝试其余档位。
 */
internal fun downloadQualityCandidates(target: MusicQuality, song: MusicSong): List<MusicQuality> =
    listOf(target) + song.qualities.filter { it.filename != target.filename }
