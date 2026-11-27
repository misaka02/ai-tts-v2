package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderReadingMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
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
    val focusRequester = remember { FocusRequester() }
    val pageTurnChannel = remember { Channel<Int>(capacity = 4) }
    val pageTurnEvents = remember(pageTurnChannel) { pageTurnChannel.receiveAsFlow() }
    var lightboxBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val colors = rememberReaderColors(readerSettings.paperTheme)
    val appDefaultIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    ReaderSystemWindowEffect(readerSettings, state.showChrome, appDefaultIsDark)

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    val book = state.book
    val chapter = state.chapter
    val index = state.index
    val pub = state.readiumPublication
    val activeTtsForBook = ttsState.bookId == book?.id && (ttsState.isPlaying || ttsState.isSynthesizing)
    val activeTtsBlock = if (activeTtsForBook && ttsState.chapterIndex == chapter?.chapterIndex) ttsState.activeBlockIndex else -1

    val showJumpBackChip by remember(activeTtsBlock, chapter) {
        derivedStateOf {
            if (activeTtsBlock < 0 || chapter == null) false else {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val canPage = readerSettings.readingMode == ReaderReadingMode.PAGED && readerSettings.volumeKeyPaging
                if (!canPage || state.activeSheet != null || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.VolumeUp -> { pageTurnChannel.trySend(-1); true }
                    Key.VolumeDown -> { pageTurnChannel.trySend(1); true }
                    else -> false
                }
            }
    ) {
        val ready = book != null && (pub != null || (chapter != null && index != null))
        when {
            state.isLoading && !ready -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.errorMessage != null && !ready -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(state.errorMessage ?: "无法打开书籍", color = colors.text)
                Button(onClick = onBack) { Text("返回书架") }
            }
            ready && book != null -> {
                if (pub != null) {
                    ReadiumEpubViewport(
                        bookId = book.id,
                        publication = pub,
                        readiumEngine = viewModel.readiumEngine,
                        initialLocator = state.readiumInitialLocator,
                        readerSettings = readerSettings,
                        colors = colors,
                        highlights = book.highlights,
                        activeTtsSentence = if (activeTtsForBook) ttsState.activeSentenceText else "",
                        pageTurnEvents = pageTurnEvents,
                        goToLocatorEvents = viewModel.goToLocatorEvents,
                        onLocatorChanged = viewModel::onReadiumLocatorChanged,
                        onTapCenter = viewModel::toggleChrome,
                        onCreateHighlight = viewModel::createHighlight,
                        onSpeakSelection = viewModel.ttsSearch::startTtsFromReadium,
                        onSearchSelection = { q ->
                            viewModel.openSheet(ReaderSheet.SEARCH)
                            viewModel.ttsSearch.runBookSearch(q)
                        }
                    )
                } else if (chapter != null && index != null) {
                    if (readerSettings.readingMode == ReaderReadingMode.PAGED) {
                        PagedReaderViewport(
                            chapter = chapter,
                            totalChapters = index.chapters.size,
                            bookProgressPercent = book.progress.progressPercent,
                            readerSettings = readerSettings,
                            colors = colors,
                            activeTtsBlockIndex = activeTtsBlock,
                            pendingScrollBlockIndex = state.pendingScrollBlockIndex,
                            pendingScrollCharOffset = state.pendingScrollCharOffset,
                            pendingLandOnLastPage = state.pendingLandOnLastPage,
                            pageTurnEvents = pageTurnEvents,
                            onConsumedPendingScroll = viewModel::consumePendingScroll,
                            onVisibleBlockChanged = viewModel::onVisibleBlockChanged,
                            onTapCenter = viewModel::toggleChrome,
                            onLongPressSpeak = viewModel.ttsSearch::startTtsFromBlock,
                            onPrevChapter = { landLast -> viewModel.stepChapter(-1, landLast) },
                            onNextChapter = { viewModel.stepChapter(1, false) },
                            loadImage = viewModel::loadBookImage,
                            onImageClick = { lightboxBitmap = it }
                        )
                    } else {
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
                            onLongPressSpeak = viewModel.ttsSearch::startTtsFromBlock,
                            onPrevChapter = { viewModel.stepChapter(-1) },
                            onNextChapter = { viewModel.stepChapter(1) },
                            loadImage = viewModel::loadBookImage,
                            onImageClick = { lightboxBitmap = it }
                        )
                    }
                }

                val isNativeScroll = pub == null && readerSettings.readingMode == ReaderReadingMode.SCROLL
                if (isNativeScroll && showJumpBackChip && chapter != null) {
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

                val totalChaps = pub?.readingOrder?.size ?: index?.chapters?.size ?: 1
                AnimatedVisibility(
                    visible = state.showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val isBookmarked = book.bookmarks.any {
                        if (state.currentLocatorJson.isNotBlank() && it.locatorJson.isNotBlank()) {
                            it.chapterIndex == state.currentChapterIndex && it.locatorJson == state.currentLocatorJson
                        } else {
                            it.chapterIndex == state.currentChapterIndex && it.blockIndex == state.visibleBlockIndex
                        }
                    }
                    ReaderTopBar(
                        bookTitle = book.title,
                        chapterTitle = state.currentChapterTitle.ifBlank { book.title },
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
                        chapterIndex = state.currentChapterIndex,
                        totalChapters = totalChaps,
                        bookProgression = state.currentProgression,
                        isPlaying = ttsState.bookId == book.id && ttsState.isPlaying,
                        isSynthesizing = ttsState.bookId == book.id && ttsState.isSynthesizing,
                        chromeBg = chromeBg,
                        chromeText = colors.text,
                        onPrevChapter = { viewModel.stepChapter(-1) },
                        onNextChapter = { viewModel.stepChapter(1) },
                        onSeekProgression = viewModel::seekByProgression,
                        onTogglePlay = viewModel::togglePlayPause,
                        onOpenToc = { viewModel.openSheet(ReaderSheet.TOC) },
                        onOpenAppearance = { viewModel.openSheet(ReaderSheet.APPEARANCE) },
                        onOpenTtsSheet = { viewModel.openSheet(ReaderSheet.TTS) }
                    )
                }
            }
        }
    }

    ReaderModalHost(
        viewModel = viewModel,
        state = state,
        readerSettings = readerSettings,
        ttsState = ttsState,
        globalSettings = globalSettings,
        providers = providers,
        activeProvider = activeProvider,
        sleepActive = sleepActive,
        sleepSec = sleepSec,
        lightboxBitmap = lightboxBitmap,
        onDismissLightbox = { lightboxBitmap = null }
    )
}
