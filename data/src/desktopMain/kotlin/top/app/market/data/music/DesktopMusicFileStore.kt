package top.app.market.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * 桌面端音频落盘：写入用户下载目录 `~/Downloads/AppMarket/Music`。
 */
internal class DesktopMusicFileStore : MusicFileStore {
    override suspend fun create(fileName: String, mimeType: String, expectedBytes: Long): MusicFileSink =
        withContext(Dispatchers.IO) {
            val base = File(System.getProperty("user.home"), "Downloads/AppMarket/Music")
            base.mkdirs()
            DesktopSink(uniqueFile(base, fileName))
        }

    private class DesktopSink(
        private val file: File,
    ) : MusicFileSink {
        private val output: OutputStream = file.outputStream().buffered()

        override fun write(buffer: ByteArray, length: Int) {
            output.write(buffer, 0, length)
        }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            output.close()
            file.absolutePath
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { output.close() }
            file.delete()
            Unit
        }
    }

    private companion object {
        /** 同名文件追加序号，避免覆盖用户已有下载。 */
        fun uniqueFile(directory: File, requestedName: String): File {
            val extensionIndex = requestedName.lastIndexOf('.')
            val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
            val suffix = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
            var candidate = File(directory, requestedName)
            var index = 1
            while (candidate.exists()) {
                candidate = File(directory, "$base-$index$suffix")
                index += 1
            }
            return candidate
        }
    }
}
