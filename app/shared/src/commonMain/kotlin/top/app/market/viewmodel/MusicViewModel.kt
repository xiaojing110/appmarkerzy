package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.music.MusicDownloadRequest
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSong
import top.app.market.domain.repository.MusicDownloadRepository
import top.app.market.domain.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class MusicUiState(
    val keyword: String = "",
    /** 当前结果所属的关键词（可能与输入框里的 [keyword] 不同步）。 */
    val activeKeyword: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val errorMessage: String = "",
    val showNoResults: Boolean = false,
    val songs: List<MusicSong> = emptyList(),
    val hotKeywords: List<String> = emptyList(),
    /** 已加载到的页数（从 1 开始）。 */
    val page: Int = 0,
    val hasMore: Boolean = false,
    /** 每次搜索自增，用于复位滚动位置。 */
    val searchEpoch: Int = 0,
)

@Immutable
data class MusicDownloadUiState(
    val phase: Phase = Phase.DOWNLOADING,
    val bytesRead: Long = 0,
    val totalBytes: Long = -1,
    /** DONE 时为落盘路径，FAILED 时为错误信息。 */
    val message: String = "",
) {
    enum class Phase { DOWNLOADING, DONE, FAILED }
}

/** 音乐页 ViewModel：搜索、热搜与按音质下载（移植自 HotDownloader 的使用流程）。 */
class MusicViewModel(
    private val music: MusicRepository,
    private val downloads: MusicDownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, MusicDownloadUiState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, MusicDownloadUiState>> = _downloadStates.asStateFlow()

    private var searchJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refreshHotKeywords()
    }

    fun refreshHotKeywords() {
        viewModelScope.launch {
            val keywords = runCatching { music.hotKeywords() }.getOrDefault(emptyList())
            _uiState.update { it.copy(hotKeywords = keywords) }
        }
    }

    /** 输入框内容变化；清空输入同时清掉结果与分页，避免无限滚动误翻旧关键词的下一页。 */
    fun setKeyword(value: String) {
        if (value.isBlank()) {
            searchJob?.cancel()
            searchJob = null
            _uiState.update {
                it.copy(
                    keyword = value,
                    loading = false,
                    loadingMore = false,
                    errorMessage = "",
                    showNoResults = false,
                    songs = emptyList(),
                    activeKeyword = "",
                    page = 0,
                    hasMore = false,
                )
            }
        } else {
            _uiState.update { it.copy(keyword = value) }
        }
    }

    fun runSearch() = searchWith(_uiState.value.keyword)

    fun searchWith(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                keyword = trimmed,
                activeKeyword = trimmed,
                loading = true,
                loadingMore = false,
                errorMessage = "",
                showNoResults = false,
                songs = emptyList(),
                page = 0,
                hasMore = false,
                searchEpoch = it.searchEpoch + 1,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val page = music.searchSongs(trimmed, page = 1)
                _uiState.update {
                    it.copy(
                        loading = false,
                        songs = page.songs,
                        page = 1,
                        hasMore = page.hasMore,
                        showNoResults = page.songs.isEmpty(),
                    )
                }
                fetchMissingCovers(page.songs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.message ?: "Search failed")
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.hasMore || state.activeKeyword.isEmpty()) return
        val nextPage = state.page + 1
        _uiState.update { it.copy(loadingMore = true) }
        searchJob = viewModelScope.launch {
            try {
                val page = music.searchSongs(state.activeKeyword, page = nextPage)
                _uiState.update { current ->
                    val known = current.songs.mapTo(mutableSetOf()) { it.id }
                    current.copy(
                        loadingMore = false,
                        songs = current.songs + page.songs.filter { it.id !in known },
                        page = nextPage,
                        hasMore = page.hasMore,
                    )
                }
                fetchMissingCovers(page.songs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(loadingMore = false, errorMessage = error.message ?: "Search failed")
                }
            }
        }
    }

    /** 下载指定音质；同曲进行中的任务不会重复发起，失败后可重试。 */
    fun download(song: MusicSong, quality: MusicQuality) {
        if (downloadJobs[song.id]?.isActive == true) return
        val job = viewModelScope.launch {
            _downloadStates.update {
                it + (song.id to MusicDownloadUiState(phase = MusicDownloadUiState.Phase.DOWNLOADING))
            }
            try {
                val url = music.downloadLink(song, quality)
                val path = downloads.download(MusicDownloadRequest(song, quality, url)) { read, total ->
                    _downloadStates.update {
                        it + (song.id to MusicDownloadUiState(
                            phase = MusicDownloadUiState.Phase.DOWNLOADING,
                            bytesRead = read,
                            totalBytes = total,
                        ))
                    }
                }
                _downloadStates.update {
                    it + (song.id to MusicDownloadUiState(
                        phase = MusicDownloadUiState.Phase.DONE,
                        message = path,
                    ))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _downloadStates.update {
                    it + (song.id to MusicDownloadUiState(
                        phase = MusicDownloadUiState.Phase.FAILED,
                        message = error.message ?: "Download failed",
                    ))
                }
            }
        }
        downloadJobs[song.id] = job
    }

    /** 弹过提示后清除终态，让按钮回到「下载」。 */
    fun consumeDownloadResult(songId: String) {
        _downloadStates.update { states ->
            val current = states[songId] ?: return@update states
            if (current.phase == MusicDownloadUiState.Phase.DOWNLOADING) states
            else states - songId
        }
    }

    /** 搜索结果里的封面可能缺失（酷我搜索不带封面），按需补齐。 */
    private fun fetchMissingCovers(songs: List<MusicSong>) {
        songs.filter { it.coverUrl.isEmpty() }.forEach { song ->
            viewModelScope.launch {
                val cover = runCatching { music.coverUrl(song.id) }.getOrNull() ?: return@launch
                if (cover.isEmpty()) return@launch
                _uiState.update { state ->
                    state.copy(songs = state.songs.map {
                        if (it.id == song.id) it.copy(coverUrl = cover) else it
                    })
                }
            }
        }
    }
}
