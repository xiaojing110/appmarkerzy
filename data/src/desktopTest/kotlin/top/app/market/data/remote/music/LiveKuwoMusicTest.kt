package top.app.market.data.remote.music

import top.app.market.data.music.DesktopMusicFileStore
import top.app.market.data.music.MusicDownloadRepositoryImpl
import top.app.market.data.platform.createHttpClient
import top.app.market.data.repository.MusicRepositoryImpl
import top.app.market.domain.model.music.MusicDownloadRequest
import top.app.market.domain.model.music.MusicQuality
import io.ktor.client.HttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * 酷我音源联网冒烟测试（仅在设置了 `KW_LIVE_TEST` 时执行，风格对齐 LiveXxxTest）。
 *
 * 覆盖真实链路：搜索 → 热搜 → 封面 → 取下载直链 → 流式落盘，全部走 https。
 */
class LiveKuwoMusicTest {

    @Test
    fun searchResolveAndDownloadThroughRealNetwork() = runBlocking {
        if (System.getenv("KW_LIVE_TEST")?.isNotBlank() != true) return@runBlocking
        val previousHome = System.getProperty("user.home")
        val cleanHome = Files.createTempDirectory("appmarket-kw-live-")
        System.setProperty("user.home", cleanHome.toString())
        val client: HttpClient = createHttpClient()
        try {
            val api = KuwoMusicApi(client, Json { ignoreUnknownKeys = true })

            // 1. 搜索（修复点：全部走 https，Android 不再触发 CLEARTEXT 限制）
            val page = api.searchSongs("七里香", page = 1, limit = 5)
            assertTrue(page.songs.isNotEmpty(), "搜索结果不应为空")
            val song = page.songs.first()
            assertTrue(song.title.isNotBlank(), "歌名不应为空")
            println("search -> ${song.title} / ${song.artist} / id=${song.id} / qualities=${song.qualities}")

            // 2. 热搜
            val hot = api.hotKeywords()
            assertTrue(hot.isNotEmpty(), "热搜词不应为空")

            // 3. 封面（接口返回 http 直链时应被升级为 https）
            val cover = api.coverUrl(song.id)
            if (cover != null) {
                assertTrue(cover.startsWith("https://"), "封面链接应为 https: $cover")
            }

            // 4. 取下载直链（含自动降级）：请求一个目标音质，缺失时由仓库按顺序降级
            val repository = MusicRepositoryImpl(api)
            val target = song.qualities.firstOrNull() ?: MusicQuality("320K MP3", 320, "mp3", 0)
            val url = repository.downloadLink(song, target)
            assertTrue(url.startsWith("https://"), "下载直链应为 https: $url")

            // 5. 流式落盘到临时目录（模拟真实下载）
            val store = DesktopMusicFileStore()
            val downloads = MusicDownloadRepositoryImpl(client, store)
            var lastProgress = 0L
            val path = downloads.download(MusicDownloadRequest(song, target, url)) { read, _ ->
                lastProgress = read
            }
            val file = java.io.File(path)
            assertTrue(file.isFile, "下载文件应存在: $path")
            assertTrue(file.length() > 0, "下载文件不应为空")
            assertEquals(file.length(), lastProgress, "进度字节数应与文件大小一致")
            println("download -> $path (${file.length()} bytes)")
        } finally {
            System.setProperty("user.home", previousHome)
            client.close()
            cleanHome.toFile().deleteRecursively()
        }
    }
}
