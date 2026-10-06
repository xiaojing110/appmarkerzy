package top.app.market.data.music

import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSong
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicQualitiesTest {

    private fun song(qualities: List<MusicQuality>) = MusicSong(
        id = "1",
        title = "t",
        artist = "a",
        album = "",
        durationSeconds = 0,
        coverUrl = "",
        qualities = qualities,
    )

    @Test
    fun targetQualityIsTriedFirstThenRemainingOnesInOrder() {
        val flac = MusicQuality("FLAC", 2000, "flac", 0)
        val k320 = MusicQuality("320K MP3", 320, "mp3", 0)
        val k128 = MusicQuality("128K MP3", 128, "mp3", 0)
        val candidates = downloadQualityCandidates(k320, song(listOf(flac, k320, k128)))

        assertEquals(listOf(k320, flac, k128), candidates)
    }

    @Test
    fun targetOutsideSongQualitiesStillFirst() {
        val flac = MusicQuality("FLAC", 2000, "flac", 0)
        val fallback = MusicQuality("320K MP3", 320, "mp3", 0)
        val candidates = downloadQualityCandidates(fallback, song(listOf(flac)))

        assertEquals(listOf(fallback, flac), candidates)
    }
}
