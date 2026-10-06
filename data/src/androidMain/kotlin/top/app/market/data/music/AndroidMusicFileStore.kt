package top.app.market.data.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Android 音频落盘：Q+ 写入系统下载目录 `Download/AppMarket/Music`（MediaStore pending 流程），
 * Q 以下退回应用专属下载目录，避免申请存储权限。
 */
internal class AndroidMusicFileStore(
    private val context: Context,
) : MusicFileStore {
    override suspend fun create(fileName: String, mimeType: String, expectedBytes: Long): MusicFileSink =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_ROOT)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create music download entry")
                MediaStoreSink(context, uri, fileName)
            } else {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("External downloads directory is unavailable")
                val directory = File(base, "AppMarket/Music").apply { mkdirs() }
                val file = uniqueFile(directory, fileName)
                FileSink(file)
            }
        }

    private class MediaStoreSink(
        private val context: Context,
        private val uri: Uri,
        private val fileName: String,
    ) : MusicFileSink {
        private val output: OutputStream =
            (context.contentResolver.openOutputStream(uri, "w") ?: run {
                context.contentResolver.delete(uri, null, null)
                error("Unable to write music download entry")
            }).buffered()

        override fun write(buffer: ByteArray, length: Int) {
            output.write(buffer, 0, length)
        }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            output.close()
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            "$RELATIVE_ROOT/$fileName"
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { output.close() }
            context.contentResolver.delete(uri, null, null)
            Unit
        }
    }

    private class FileSink(
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
        val RELATIVE_ROOT = "${Environment.DIRECTORY_DOWNLOADS}/AppMarket/Music"

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
