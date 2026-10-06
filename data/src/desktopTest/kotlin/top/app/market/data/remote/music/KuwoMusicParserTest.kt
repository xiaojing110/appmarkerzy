package top.app.market.data.remote.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class KuwoMusicParserTest {

    @Test
    fun qualitiesParseBitrateFormatSizeAndSkipEncrypted() {
        val info = "level:high,bitrate:2000,format:flac,size:21.93Mb;" +
            "level:high,bitrate:320,format:mp3,size:8.5Mb;" +
            "level:master,bitrate:20900,format:mflac,size:40Mb;" +
            "level:high,bitrate:320,format:mp3,size:8.5Mb;" +
            "level:low,bitrate:777,format:mp3,size:1Mb"
        val qualities = parseKuwoQualities(info)

        // 20900 为加密格式（mflac）被过滤；777 为未知码率被过滤；320 去重后仅一条。
        assertEquals(listOf(2000, 320), qualities.map { it.bitrate })
        assertEquals(listOf("FLAC", "320K MP3"), qualities.map { it.label })
        assertEquals(listOf("2000.flac", "320.mp3"), qualities.map { it.filename })
        // 21.93Mb = 21.93 × 1024 × 1024 = 22995271 字节（1024 进制，截断小数）
        assertEquals(22995271L, qualities[0].sizeBytes)
    }

    @Test
    fun sizeParsesUnits() {
        assertEquals(2048L, parseKuwoSize("2Kb"))
        assertEquals(1024L * 1024L, parseKuwoSize("1Mb"))
        assertEquals(0L, parseKuwoSize("garbage"))
    }

    @Test
    fun songParsesMusicRidAndArtistSeparator() {
        val item = JsonObject(
            mapOf(
                "MUSICRID" to JsonPrimitive("MUSIC_6802907"),
                "SONGNAME" to JsonPrimitive("七里香"),
                "ARTIST" to JsonPrimitive("周杰伦&费玉清"),
                "ALBUM" to JsonPrimitive("七里香"),
                "DURATION" to JsonPrimitive("299"),
                "web_albumpic_short" to JsonPrimitive("120/40/64/2063938955.jpg"),
                "N_MINFO" to JsonPrimitive("level:high,bitrate:320,format:mp3,size:8.5Mb"),
            ),
        )
        val song = assertNotNull(parseKuwoSong(item))
        assertEquals("6802907", song.id)
        assertEquals("七里香", song.title)
        assertEquals("周杰伦、费玉清", song.artist)
        assertEquals("七里香", song.album)
        assertEquals(299, song.durationSeconds)
        assertEquals("https://img4.kuwo.cn/star/albumcover/300/40/64/2063938955.jpg", song.coverUrl)
        assertEquals(listOf(320), song.qualities.map { it.bitrate })
    }

    @Test
    fun numericIdFieldWinsOverMusicRid() {
        val item = JsonObject(
            mapOf(
                "id" to JsonPrimitive("42"),
                "MUSICRID" to JsonPrimitive("MUSIC_6802907"),
                "name" to JsonPrimitive("歌单曲目"),
                "artist" to JsonPrimitive("某歌手"),
            ),
        )
        val song = assertNotNull(parseKuwoSong(item))
        assertEquals("42", song.id)
        assertEquals("歌单曲目", song.title)
    }

    @Test
    fun songWithoutNumericIdIsDropped() {
        assertNull(parseKuwoSong(JsonObject(mapOf("SONGNAME" to JsonPrimitive("无 ID 歌曲")))))
        assertNull(parseKuwoSong(JsonPrimitive("not an object")))
    }
}
