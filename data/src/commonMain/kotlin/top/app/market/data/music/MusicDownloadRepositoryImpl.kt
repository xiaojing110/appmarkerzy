package top.app.market.data.music

import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.music.MusicDownloadRequest
import top.app.market.domain.repository.MusicDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/** 音频下载实现：Ktor 流式拉取直链并经 [MusicFileStore] 落盘。 */
internal class MusicDownloadRepositoryImpl(
    private val client: HttpClient,
    private val fileStore: MusicFileStore,
) : MusicDownloadRepository {
    override suspend fun download(
        request: MusicDownloadRequest,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): String {
        val fileName = musicFileName(request.song.title, request.song.artist, request.quality.format)
        val mimeType = mimeTypeForFormat(request.quality.format)
        return try {
            client.prepareGet(request.url) {
                header(HttpHeaders.UserAgent, "okhttp/4.10.0")
                header(HttpHeaders.Referrer, "http://www.kuwo.cn/")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw MarketException("下载失败: HTTP ${response.status.value}")
                }
                val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: request.quality.sizeBytes.takeIf { it > 0 }
                    ?: -1L
                val channel = response.bodyAsChannel()
                val sink = fileStore.create(fileName, mimeType, total)
                try {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        sink.write(buffer, count)
                        read += count
                        onProgress(read, total)
                    }
                    if (read == 0L) throw MarketException("下载失败: 响应为空")
                    sink.commit()
                } catch (error: Throwable) {
                    sink.abort()
                    throw error
                }
            }
        } catch (error: MarketException) {
            throw error
        } catch (error: Throwable) {
            throw MarketException("下载失败: $error")
        }
    }

    private fun mimeTypeForFormat(format: String): String = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "mflac" -> "audio/ogg"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
