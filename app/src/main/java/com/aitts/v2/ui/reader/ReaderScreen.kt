package com.aitts.v2.ui.reader

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.reader.model.ReaderPaperTheme
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettingsFlow.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsStateFlow.collectAsStateWithLifecycle()
    val globalSettings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProviderFlow.collectAsStateWithLifecycle()
    val sleepActive by viewModel.sleepActiveFlow.collectAsStateWithLifecycle()
    val sleepSec by viewModel.sleepRemainingFlow.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val colors = rememberReaderColors(readerSettings.paperTheme)
    val appDefaultIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val useDarkBg = if (readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP) {
                appDefaultIsDark
            } else {
                readerSettings.paperTheme.isDark
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkBg
        }
    }

    DisposableEffect(appDefaultIsDark) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !appDefaultIsDark
        }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    val book = state.book
    val chapter = state.chapter
    val index = state.index
    val activeTtsForChapter = ttsState.bookId == book?.id &&
        (ttsState.isPlaying || ttsState.isSynthesizing) &&
        ttsState.chapterIndex == chapter?.chapterIndex
    val activeTtsBlock = if (activeTtsForChapter) ttsState.activeBlockIndex else -1

    val showJumpBackChip by remember(activeTtsBlock, chapter) {
        derivedStateOf {
            if (activeTtsBlock < 0 || chapter == null) {
                false
            } else {
                val targetItemIdx = chapter.blocks.indexOfFirst { it.index == activeTtsBlock }.let { if (it < 0) -1 else it + 1 }
                targetItemIdx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == targetItemIdx }
            }
        }
    }

    val chromeBg = if (readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    } else {
        Color(readerSettings.paperTheme.bgHex).copy(alpha = 0.97f)
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        when {
            state.isLoading && chapter == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.errorMessage != null && chapter == null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.errorMessage ?: "无法打开书籍", color = colors.text)
                    Button(onClick = onBack) { Text("返回书架") }
                }
            }
            chapter != null && index != null && book != null -> {
                ReaderContentViewport(
                    chapter = chapter,
                    totalChapters = index.chapters.size,
                    readerSettings = readerSettings,
                    colors = colors,
                    listState = listState,
                    activeTtsBlockIndex = activeTtsBlock,
                    pendingScrollBlockIndex = state.pendingScrollBlockIndex,
                    onConsumedPendingScroll = viewModel::consumePendingScroll,
                    onVisibleBlockChanged = viewModel::onVisibleBlockChanged,
                    onTapBody = viewModel::toggleChrome,
                    onLongPressSpeak = viewModel::startTtsFromBlock,
                    onPrevChapter = { viewModel.stepChapter(-1) },
                    onNextChapter = { viewModel.stepChapter(1) },
                    loadImage = viewModel::loadBookImage
                )

                if (showJumpBackChip) {
                    Surface(
                        onClick = {
                            coroutineScope.launch {
                                val pos = chapter.blocks.indexOfFirst { it.index == activeTtsBlock }
                                if (pos >= 0) listState.animateScrollToItem(pos + 1, scrollOffset = -120)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.GraphicEq, contentDescription = null)
                            Text("回到朗读处", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val isBookmarked = book.bookmarks.any {
                        it.chapterIndex == chapter.chapterIndex && it.blockIndex == state.visibleBlockIndex
                    }
                    ReaderTopBar(
                        bookTitle = book.title,
                        chapterTitle = chapter.title,
                        isBookmarked = isBookmarked,
                        chromeBg = chromeBg,
                        chromeText = colors.text,
                        onBack = onBack,
                        onToggleBookmark = viewModel::toggleBookmark,
                        onOpenSearch = { viewModel.openSheet(ReaderSheet.SEARCH) }
                    )
                }

                AnimatedVisibility(
                    visible = state.showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ReaderBottomBar(
                        chapterIndex = chapter.chapterIndex,
                        totalChapters = index.chapters.size,
                        isPlaying = ttsState.bookId == book.id && ttsState.isPlaying,
                        isSynthesizing = ttsState.bookId == book.id && ttsState.isSynthesizing,
                        chromeBg = chromeBg,
                        chromeText = colors.text,
                        onPrevChapter = { viewModel.stepChapter(-1) },
                        onNextChapter = { viewModel.stepChapter(1) },
                        onTogglePlay = viewModel::togglePlayPause,
                        onOpenToc = { viewModel.openSheet(ReaderSheet.TOC) },
                        onOpenAppearance = { viewModel.openSheet(ReaderSheet.APPEARANCE) },
                        onOpenTtsSheet = { viewModel.openSheet(ReaderSheet.TTS) }
                    )
                }
            }
        }
    }

    if (chapter != null && index != null && book != null) {
        when (state.activeSheet) {
            ReaderSheet.TOC -> ReaderTocSheet(
                chapters = index.chapters,
                currentChapterIndex = chapter.chapterIndex,
                bookmarks = book.bookmarks,
                onSelectChapter = { viewModel.goToChapter(it, 0) },
                onSelectBookmark = { viewModel.goToChapter(it.chapterIndex, it.blockIndex) },
                onRemoveBookmark = viewModel::removeBookmark,
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.SEARCH -> ReaderSearchSheet(
                query = state.searchQuery,
                hits = state.searchHits,
                isSearching = state.isSearching,
                onQueryChange = viewModel::runBookSearch,
                onSelectHit = { viewModel.goToChapter(it.chapterIndex, it.blockIndex) },
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.APPEARANCE -> ReaderAppearanceSheet(
                settings = readerSettings,
                onUpdate = viewModel::updateReaderSettings,
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.TTS -> ReaderTtsSheet(
                activeProvider = activeProvider,
                providers = providers,
                isPlayingOrSynthesizing = ttsState.bookId == book.id && (ttsState.isPlaying || ttsState.isSynthesizing),
                sleepActive = sleepActive,
                sleepRemainingSec = sleepSec,
                floatingSubtitleEnabled = globalSettings.isFloatingSubtitleEnabled,
                onSelectProvider = viewModel::selectProvider,
                onSpeedChange = { viewModel.updateActiveVoice(speed = it) },
                onPitchChange = { viewModel.updateActiveVoice(pitch = it) },
                onSetSleepTimer = viewModel::setSleepTimer,
                onToggleSubtitle = { viewModel.toggleFloatingSubtitle(context, it) },
                onStopTts = viewModel::stopTts,
                onDismiss = { viewModel.openSheet(null) }
            )
            null -> Unit
        }
    }
}
