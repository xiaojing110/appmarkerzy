package top.app.market.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.app.market.domain.model.music.MusicQuality
import top.app.market.domain.model.music.MusicSong
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.music_choose_quality
import top.app.market.resources.music_download
import top.app.market.resources.music_download_done
import top.app.market.resources.music_download_failed
import top.app.market.resources.music_downloaded
import top.app.market.resources.music_downloading
import top.app.market.resources.music_empty_tips
import top.app.market.resources.music_hot_keywords
import top.app.market.resources.music_search_hint
import top.app.market.resources.nav_music
import top.app.market.resources.no_results
import top.app.market.resources.retry
import top.app.market.ui.component.AdaptiveTopAppBar
import top.app.market.ui.component.AppAsyncImage
import top.app.market.ui.component.AppCompactButton
import top.app.market.ui.component.LoadingBox
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.ui.component.blur.BlurredBar
import top.app.market.ui.component.blur.rememberBlurBackdrop
import top.app.market.ui.util.formatSize
import top.app.market.viewmodel.MusicDownloadUiState
import top.app.market.viewmodel.MusicUiState
import top.app.market.viewmodel.MusicViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 音乐页：搜索 / 热搜试听下载入口（移植自 HotDownloader 的音乐下载能力）。
 *
 * 底部导航中的「音乐」页签由设置里的开关控制（默认开启）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicTab(
    viewModel: MusicViewModel,
    bottomPadding: Dp,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 无限滚动：进入底部区域触发加载更多；distinctUntilChanged 防止一次滚动重复触发。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) viewModel.loadMore() }
    }
    // 每次搜索完成后回到列表顶部。
    LaunchedEffect(state.searchEpoch) {
        if (state.searchEpoch > 0) listState.scrollToItem(0)
    }

    val uiPlatform = koinInject<UiPlatform>()
    val donePrefix = stringResource(Res.string.music_download_done)
    val failedPrefix = stringResource(Res.string.music_download_failed)
    // 终态弹一次提示并复位按钮（toast 文案在 composable 侧取，ViewModel 不持有本地化文案）。
    LaunchedEffect(downloadStates) {
        downloadStates.filterValues { it.phase != MusicDownloadUiState.Phase.DOWNLOADING }
            .forEach { (songId, download) ->
                when (download.phase) {
                    MusicDownloadUiState.Phase.DONE ->
                        uiPlatform.showToast("$donePrefix ${download.message}")

                    MusicDownloadUiState.Phase.FAILED ->
                        uiPlatform.showToast("$failedPrefix: ${download.message}")

                    MusicDownloadUiState.Phase.DOWNLOADING -> Unit
                }
                viewModel.consumeDownloadResult(songId)
            }
    }

    var qualityTarget by remember { mutableStateOf<MusicSong?>(null) }
    QualityPickerDialog(
        song = qualityTarget,
        onDismiss = { qualityTarget = null },
        onPick = { song, quality ->
            qualityTarget = null
            viewModel.download(song, quality)
        },
    )

    val scrollBehavior = MiuixScrollBehavior()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val searchActive = isFocused || state.keyword.isNotEmpty()
    val cancelSearch: () -> Unit = {
        viewModel.setKeyword("")
        // 焦点残留会让「取消」按钮常驻，清空后主动收起输入法交给系统处理
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(Res.string.nav_music),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    bottomContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InputField(
                                query = state.keyword,
                                onQueryChange = viewModel::setKeyword,
                                onSearch = {
                                    viewModel.runSearch()
                                },
                                expanded = searchActive,
                                onExpandedChange = { },
                                label = stringResource(Res.string.music_search_hint),
                                interactionSource = interactionSource,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                            )
                            if (searchActive) {
                                Text(
                                    text = stringResource(Res.string.cancel),
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 4.dp, end = 16.dp)
                                        .padding(bottom = 6.dp)
                                        .clickable(
                                            interactionSource = null,
                                            indication = null,
                                            onClick = cancelSearch,
                                        ),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        val backdropModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        val contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = innerPadding.calculateTopPadding() + PageVerticalPadding,
            bottom = bottomPadding + PageVerticalPadding,
        )
        Box(Modifier.fillMaxHeight()) {
            Crossfade(
                targetState = state.loading && state.songs.isEmpty(),
                modifier = Modifier.fillMaxSize().then(backdropModifier),
                label = "music",
            ) { fullScreenLoading ->
                if (fullScreenLoading) {
                    LoadingBox(Modifier.fillMaxSize().padding(contentPadding))
                } else {
                    MusicResultList(
                        state = state,
                        downloadStates = downloadStates,
                        listState = listState,
                        contentPadding = contentPadding,
                        scrollBehavior = scrollBehavior,
                        onHotKeyword = { viewModel.searchWith(it) },
                        onDownloadClick = { song ->
                            val qualities = song.downloadableQualities()
                            if (qualities.size <= 1) {
                                viewModel.download(song, qualities.first())
                            } else {
                                qualityTarget = song
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 可下载音质：解析不到档位时兜底 320K MP3，由下载接口校验该档位是否真实存在。 */
private fun MusicSong.downloadableQualities(): List<MusicQuality> =
    qualities.ifEmpty { listOf(MusicQuality("320K MP3", 320, "mp3", 0)) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicResultList(
    state: MusicUiState,
    downloadStates: Map<String, MusicDownloadUiState>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onHotKeyword: (String) -> Unit,
    onDownloadClick: (MusicSong) -> Unit,
) {
    val noResultsText = stringResource(Res.string.no_results)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = contentPadding,
    ) {
        if (state.loading && state.songs.isNotEmpty()) {
            item(key = "loading") { LoadingBox() }
        }
        if (state.errorMessage.isNotEmpty()) {
            item(key = "error") {
                Text(
                    state.errorMessage,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else if (state.showNoResults) {
            item(key = "empty") {
                Text(
                    noResultsText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (state.songs.isEmpty() && state.keyword.isBlank()) {
            item(key = "hot") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.hotKeywords.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.music_hot_keywords),
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.hotKeywords.forEach { keyword ->
                                Box(
                                    modifier = Modifier
                                        .squircleSurface(
                                            color = MiuixTheme.colorScheme.surfaceContainer,
                                            cornerRadius = 14.dp,
                                        )
                                        .clickable { onHotKeyword(keyword) },
                                ) {
                                    Text(
                                        keyword,
                                        style = MiuixTheme.textStyles.body1,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(Res.string.music_empty_tips),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        items(state.songs, key = { it.id }) { song ->
            MusicRow(
                song = song,
                downloadState = downloadStates[song.id],
                onDownloadClick = { onDownloadClick(song) },
                modifier = Modifier.animateItem(placementSpec = null),
            )
        }

        if (state.loadingMore) {
            item(key = "loadmore") { LoadingBox() }
        }
    }
}

@Composable
private fun MusicRow(
    song: MusicSong,
    downloadState: MusicDownloadUiState?,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qualities = song.downloadableQualities()
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(all = 13.dp),
    ) {        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (song.coverUrl.isNotEmpty()) {
                AppAsyncImage(
                    url = song.coverUrl,
                    contentDescription = song.title,
                    modifier = Modifier
                        .size(52.dp)
                        .squircleClip(13.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .squircleSurface(
                            color = MiuixTheme.colorScheme.surfaceContainer,
                            cornerRadius = 13.dp,
                        ),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOf(song.artist, song.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { song.id },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    song.metaLine(qualities),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            val buttonLabel = when (downloadState?.phase) {
                MusicDownloadUiState.Phase.DOWNLOADING -> {
                    val percent = if (downloadState.totalBytes > 0) {
                        (downloadState.bytesRead * 100 / downloadState.totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }
                    percent?.let { "$it%" } ?: stringResource(Res.string.music_downloading)
                }

                MusicDownloadUiState.Phase.DONE -> stringResource(Res.string.music_downloaded)
                MusicDownloadUiState.Phase.FAILED -> stringResource(Res.string.retry)
                null -> stringResource(Res.string.music_download)
            }
            AppCompactButton(
                text = buttonLabel,
                onClick = onDownloadClick,
                enabled = downloadState?.phase != MusicDownloadUiState.Phase.DOWNLOADING,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** 「时长 · 音质档位」元信息行。 */
private fun MusicSong.metaLine(qualities: List<MusicQuality>): String = buildList {
    if (durationSeconds > 0) add(formatDuration(durationSeconds))
    if (qualities.isNotEmpty()) add(qualities.joinToString(" / ") { it.label })
}.joinToString(" · ")

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

@Composable
private fun QualityPickerDialog(
    song: MusicSong?,
    onDismiss: () -> Unit,
    onPick: (MusicSong, MusicQuality) -> Unit,
) {
    val title = stringResource(Res.string.music_choose_quality)
    WindowDialog(
        show = song != null,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        val target = song ?: return@WindowDialog
        val qualities = target.downloadableQualities()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            qualities.forEach { quality ->
                val sizeText = if (quality.sizeBytes > 0) " · ${formatSize(quality.sizeBytes)}" else ""
                Text(
                    text = "${quality.label}$sizeText",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(target, quality) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}
