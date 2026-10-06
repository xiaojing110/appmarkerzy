package top.app.market.data.music

/**
 * 音频落盘抽象：Android 写入系统下载目录（MediaStore），桌面端写入用户下载目录。
 *
 * 共享实现在下载循环里完成读写与进度回调，平台只负责「开文件 / 提交 / 回滚」。
 */
internal interface MusicFileStore {
    /** 创建落盘目标；返回的 sink 必须以 [MusicFileSink.commit] 或 [MusicFileSink.abort] 收尾。 */
    suspend fun create(fileName: String, mimeType: String, expectedBytes: Long): MusicFileSink
}

internal interface MusicFileSink {
    fun write(buffer: ByteArray, length: Int)

    /** 提交并返回落盘位置的展示文本。 */
    suspend fun commit(): String

    /** 放弃本次写入并清理残留（半成品文件 / pending 条目）。 */
    suspend fun abort()
}
