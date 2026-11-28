package com.aitts.v2.ui.reader

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderTapZoneMode
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
    var edgeBrightnessHud by remember { mutableStateOf<Float?>(null) }

    val colors = rememberReaderColors(readerSettings)
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

    val chromeBg = when {
        readerSettings.customBgHex != 0L -> Color(readerSettings.customBgHex).copy(alpha = 0.97f)
        readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP -> MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        else -> Color(readerSettings.paperTheme.bgHex).copy(alpha = 0.97f)
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
                val upStep = if (readerSettings.invertVolumeKeys) 1 else -1
                when (ev.key) {
                    Key.VolumeUp -> { pageTurnChannel.trySend(upStep); true }
                    Key.VolumeDown -> { pageTurnChannel.trySend(-upStep); true }
                    else -> false
                }
            }
    ) {
        val ready = book != null && (pub != null || (chapter != null && index != null))
        val errMsg = state.errorMessage
        when {
            state.isLoading && !ready -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            errMsg != null && !ready -> ReaderErrorPlaceholder(
                message = errMsg,
                textColor = colors.text,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
            book != null && (pub != null || (chapter != null && index != null)) -> {
                if (pub != null) {
                    ReadiumEpubViewport(
                        bookId = book.id,
                        publication = pub,
                        readiumEngine = viewModel.readiumEngine,
                        initialLocator = state.readiumInitialLocator,
                        readerSettings = readerSettings,
                        colors = colors,
                        highlights = book.highlights,
                        chapterTitle = state.currentChapterTitle,
                        positionIndex = state.currentPositionIndex,
                        positionsTotal = state.readiumPositions.size,
                        totalProgression = state.currentProgression,
                        isTtsActive = activeTtsForBook,
                        activeTtsSentence = if (activeTtsForBook) ttsState.activeSentenceText else "",
                        autoScrollActive = state.autoScrollActive,
                        autoScrollPaused = state.autoScrollPaused,
                        pageTurnEvents = pageTurnEvents,
                        goToLocatorEvents = viewModel.goToLocatorEvents,
                        onLocatorChanged = viewModel::onReadiumLocatorChanged,
                        onTapCenter = viewModel::toggleChrome,
                        onCreateHighlight = viewModel.annotations::createHighlight,
                        onHighlightTapped = { hid -> viewModel.annotations.handleHighlightAction(tappedId = hid) },
                        onImageTapped = viewModel.media::openImageLightbox,
                        onSpeakSelection = viewModel.ttsSearch::startTtsFromReadium,
                        onSearchSelection = { q -> viewModel.openSheet(ReaderSheet.SEARCH); viewModel.ttsSearch.runBookSearch(q) }
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
                            autoScrollActive = state.autoScrollActive,
                            autoScrollPaused = state.autoScrollPaused,
                            pageTurnEvents = pageTurnEvents,
                            onConsumedPendingScroll = viewModel::consumePendingScroll,
                            onVisibleBlockChanged = viewModel::onVisibleBlockChanged,
                            onTapCenter = viewModel::toggleChrome,
                            onLongPressBlock = { idx, txt -> viewModel.setDialogFlags(paragraph = idx to txt) },
                            onPrevChapter = { landLast -> viewModel.stepChapter(-1, landLast) },
                            onNextChapter = { viewModel.stepChapter(1, false) },
                            loadImage = viewModel::loadBookImage,
                            onImageClick = { path, _ -> viewModel.media.openImageLightbox(path) }
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
                            autoScrollActive = state.autoScrollActive,
                            autoScrollPaused = state.autoScrollPaused,
                            onConsumedPendingScroll = viewModel::consumePendingScroll,
                            onVisibleBlockChanged = viewModel::onVisibleBlockChanged,
                            onTapBody = viewModel::toggleChrome,
                            onLongPressBlock = { idx, txt -> viewModel.setDialogFlags(paragraph = idx to txt) },
                            onPrevChapter = { viewModel.stepChapter(-1) },
                            onNextChapter = { viewModel.stepChapter(1) },
                            loadImage = viewModel::loadBookImage,
                            onImageClick = { path, _ -> viewModel.media.openImageLightbox(path) }
                        )
                    }
                }

                val canShowFloatingOverlays = !state.showChrome && state.activeSheet == null
                if (readerSettings.leftEdgeBrightnessGesture && canShowFloatingOverlays) {
                    ReaderEdgeBrightnessStrip(
                        currentBrightness = readerSettings.brightness,
                        onBrightnessChange = { next -> viewModel.updateReaderSettings { it.copy(brightness = next) } },
                        onHudChange = { edgeBrightnessHud = it },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }

                edgeBrightnessHud?.let { b ->
                    ReaderBrightnessHud(brightnessFraction = b, modifier = Modifier.align(Alignment.Center))
                }

                val showJumpChip = pub == null && readerSettings.readingMode == ReaderReadingMode.SCROLL
                if (showJumpChip && showJumpBackChip && chapter != null) {
                    ReaderJumpBackChip(
                        onClick = {
                            coroutineScope.launch {
                                val pos = chapter.blocks.indexOfFirst { it.index == activeTtsBlock }
                                if (pos >= 0) listState.animateScrollToItem(pos + 1, scrollOffset = -120)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }

                if (state.autoScrollActive && !state.showChrome) {
                    ReaderAutoScrollOverlay(
                        speed = readerSettings.autoScrollSpeed,
                        isPaused = state.autoScrollPaused,
                        onSpeedDelta = { d -> viewModel.controlAutoScroll(speedDelta = d) },
                        onTogglePause = { viewModel.controlAutoScroll(paused = !state.autoScrollPaused) },
                        onStop = { viewModel.controlAutoScroll(active = false, paused = false) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                val showMiniTts = activeTtsForBook && !state.showChrome && state.activeSheet == null
                if (showMiniTts && !state.autoScrollActive) {
                    ReaderMiniTtsBar(
                        ttsState = ttsState,
                        speed = activeProvider.speed,
                        onCycleSpeed = {
                            val next = when {
                                activeProvider.speed < 1.15f -> 1.25f
                                activeProvider.speed < 1.45f -> 1.50f
                                activeProvider.speed < 1.85f -> 2.00f
                                else -> 1.00f
                            }
                            viewModel.ttsSearch.updateActiveVoice(speed = next)
                        },
                        onOpenConsole = { viewModel.openSheet(ReaderSheet.TTS) },
                        onStop = viewModel::togglePlayPause,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp)
                    )
                }

                val showCornerMenu = !state.showChrome &&
                    (readerSettings.tapZoneMode == ReaderTapZoneMode.ALL_NEXT ||
                        readerSettings.tapZoneMode == ReaderTapZoneMode.SWIPE_ONLY)
                if (showCornerMenu) {
                    ReaderCornerTuneButton(
                        chromeBg = chromeBg,
                        contentColor = colors.text,
                        onClick = viewModel::toggleChrome,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
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
                        } else it.chapterIndex == state.currentChapterIndex && it.blockIndex == state.visibleBlockIndex
                    }
                    ReaderTopBar(
                        bookTitle = book.title,
                        chapterTitle = state.currentChapterTitle.ifBlank { book.title },
                        isBookmarked = isBookmarked,
                        chromeBg = chromeBg,
                        chromeText = colors.text,
                        onBack = onBack,
                        onToggleBookmark = { viewModel.annotations.toggleBookmark() },
                        onOpenSearch = { viewModel.openSheet(ReaderSheet.SEARCH) },
                        onStartAutoScroll = { viewModel.controlAutoScroll(active = true, paused = false) },
                        onOpenJump = { viewModel.setDialogFlags(jump = true) },
                        onOpenBookInfo = { viewModel.setDialogFlags(info = true) }
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
                        onOpenJump = { viewModel.setDialogFlags(jump = true) },
                        onTogglePlay = viewModel::togglePlayPause,
                        onToggleDayNight = {
                            val curDark = when {
                                readerSettings.customBgHex != 0L -> ColorUtils.calculateLuminance(readerSettings.customBgHex.toInt()) < 0.45
                                readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP -> appDefaultIsDark
                                else -> readerSettings.paperTheme.isDark
                            }
                            val next = if (curDark) ReaderPaperTheme.CREAM else ReaderPaperTheme.NIGHT
                            viewModel.updateReaderSettings { it.copy(paperTheme = next, customBgHex = 0L, customTextHex = 0L) }
                        },
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
        sleepSec = sleepSec
    )
}
