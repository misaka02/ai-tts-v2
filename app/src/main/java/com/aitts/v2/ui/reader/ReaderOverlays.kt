package com.aitts.v2.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.text.format.Formatter
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.ReaderOrientationLock
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings

@Composable
fun ReaderSystemWindowEffect(
    readerSettings: ReaderSettings,
    showChrome: Boolean,
    appDefaultIsDark: Boolean
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window ?: return@SideEffect
            val useDarkBg = when {
                readerSettings.customBgHex != 0L -> ColorUtils.calculateLuminance(readerSettings.customBgHex.toInt()) < 0.45
                readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP -> appDefaultIsDark
                else -> readerSettings.paperTheme.isDark
            }
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !useDarkBg
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (readerSettings.fullscreenImmersive && !showChrome) {
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
            window.attributes = window.attributes.apply {
                screenBrightness = if (readerSettings.brightness < 0f) -1f else readerSettings.brightness.coerceIn(0.05f, 1f)
            }
            if (readerSettings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val targetOrient = when (readerSettings.orientationLock) {
                ReaderOrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ReaderOrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ReaderOrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (activity.requestedOrientation != targetOrient) activity.requestedOrientation = targetOrient
        }
    }

    DisposableEffect(appDefaultIsDark) {
        onDispose {
            val activity = view.context as? Activity ?: return@onDispose
            val window = activity.window ?: return@onDispose
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !appDefaultIsDark
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.attributes = window.attributes.apply { screenBrightness = -1f }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@Composable
fun ReaderModalHost(
    viewModel: ReaderViewModel,
    state: ReaderUiState,
    readerSettings: ReaderSettings,
    ttsState: ReaderTtsState,
    globalSettings: GlobalSettings,
    providers: List<TtsProviderConfig>,
    activeProvider: TtsProviderConfig,
    sleepActive: Boolean,
    sleepSec: Int
) {
    val context = LocalContext.current
    val curBook = state.book
    val totalChaps = state.readiumPublication?.readingOrder?.size ?: state.index?.chapters?.size ?: 1
    if (curBook != null) {
        val tocChapters = if (state.readiumToc.isNotEmpty()) state.readiumToc else state.index?.chapters.orEmpty()
        when (state.activeSheet) {
            ReaderSheet.TOC -> ReaderTocSheet(
                chapters = tocChapters,
                currentChapterIndex = state.currentChapterIndex,
                bookmarks = curBook.bookmarks,
                highlights = curBook.highlights,
                images = state.bookImages,
                isExportingImages = state.isExportingImages,
                imageExportProgress = state.imageExportProgress,
                loadThumbnail = viewModel.media::loadThumbnail,
                onSelectChapter = { viewModel.goToChapter(it, 0) },
                onSelectBookmark = { mark -> viewModel.jumpToLocatorJson(mark.locatorJson, mark.chapterIndex, mark.blockIndex) },
                onRemoveBookmark = viewModel.annotations::removeBookmark,
                onSelectHighlight = { h -> viewModel.jumpToLocatorJson(h.locatorJson, h.chapterIndex, h.blockIndex) },
                onEditHighlightNote = { h -> viewModel.annotations.handleHighlightAction(editNoteTarget = h) },
                onRemoveHighlight = { id -> viewModel.annotations.handleHighlightAction(removeId = id) },
                onSelectImage = { item -> viewModel.openSheet(null); viewModel.media.openGalleryItem(item) },
                onExportAllImages = viewModel.media::exportAllImages,
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.SEARCH -> ReaderSearchSheet(
                query = state.searchQuery,
                hits = state.searchHits,
                isSearching = state.isSearching,
                onQueryChange = viewModel.ttsSearch::runBookSearch,
                onSelectHit = { hitIdx, hit ->
                    val locJson = state.searchLocators.getOrNull(hitIdx).orEmpty()
                    viewModel.jumpToLocatorJson(locJson, hit.chapterIndex, hit.blockIndex)
                },
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.APPEARANCE -> ReaderAppearanceSheet(
                settings = readerSettings,
                isEpub = curBook.format == BookFormat.EPUB,
                onUpdate = viewModel::updateReaderSettings,
                onResetDefaults = { viewModel.updateReaderSettings { ReaderSettings.Default } },
                onImportFont = viewModel::importCustomFont,
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.TTS -> ReaderTtsSheet(
                activeProvider = activeProvider,
                providers = providers,
                readerSettings = readerSettings,
                isPlayingOrSynthesizing = ttsState.bookId == curBook.id && (ttsState.isPlaying || ttsState.isSynthesizing),
                sleepActive = sleepActive,
                sleepRemainingSec = sleepSec,
                floatingSubtitleEnabled = globalSettings.isFloatingSubtitleEnabled,
                onSelectProvider = viewModel.ttsSearch::selectProvider,
                onSpeedChange = { viewModel.ttsSearch.updateActiveVoice(speed = it) },
                onPitchChange = { viewModel.ttsSearch.updateActiveVoice(pitch = it) },
                onSetSleepTimer = viewModel.ttsSearch::setSleepTimer,
                onToggleSubtitle = { viewModel.ttsSearch.toggleFloatingSubtitle(context, it) },
                onUpdateReaderSettings = viewModel::updateReaderSettings,
                onStopTts = viewModel.ttsSearch::stopTts,
                onDismiss = { viewModel.openSheet(null) }
            )
            null -> Unit
        }

        if (state.showJumpDialog) {
            ReaderJumpDialog(
                currentChapter = state.currentChapterIndex,
                totalChapters = totalChaps,
                currentPercent = (state.currentProgression * 100f).toInt().coerceIn(0, 100),
                onJumpPercent = viewModel::seekByProgression,
                onJumpChapter = { ch -> viewModel.goToChapter(ch, 0) },
                onDismiss = { viewModel.setDialogFlags(jump = false) }
            )
        }

        if (state.showBookInfoDialog) {
            ReaderBookInfoDialog(
                book = curBook,
                chapterCount = totalChaps,
                imageCount = state.bookImages.size,
                formattedSize = Formatter.formatShortFileSize(context, curBook.fileSizeBytes),
                onExportNotesCopy = { viewModel.annotations.exportNotesToClipboard(context) },
                onExportNotesShare = { viewModel.annotations.shareNotes(context) },
                onReindexBook = viewModel::reindexBook,
                onDismiss = { viewModel.setDialogFlags(info = false) }
            )
        }
    }

    state.selectedHighlightMenu?.let { h ->
        ReaderHighlightActionDialog(
            highlight = h,
            onChangeColor = { hex -> viewModel.annotations.handleHighlightAction(colorUpdate = h.id to hex) },
            onEditNote = { viewModel.annotations.handleHighlightAction(editNoteTarget = h) },
            onCopyQuote = {
                viewModel.annotations.copyPlainText(context, h.quote)
                viewModel.annotations.handleHighlightAction(dismissMenu = true)
            },
            onDelete = { viewModel.annotations.handleHighlightAction(removeId = h.id) },
            onDismiss = { viewModel.annotations.handleHighlightAction(dismissMenu = true) }
        )
    }

    state.editingHighlight?.let { targetHighlight ->
        ReaderHighlightNoteDialog(
            highlight = targetHighlight,
            onSave = { note -> viewModel.annotations.handleHighlightAction(saveId = targetHighlight.id, saveNote = note) },
            onDismiss = { viewModel.annotations.handleHighlightAction(editNoteTarget = null) }
        )
    }

    state.longPressParagraph?.let { (blockIdx, text) ->
        ReaderParagraphActionDialog(
            text = text,
            onSpeak = { viewModel.setDialogFlags(clearPara = true); viewModel.ttsSearch.startTtsFromBlock(blockIdx) },
            onCopy = { viewModel.setDialogFlags(clearPara = true); viewModel.annotations.copyPlainText(context, text) },
            onBookmark = { viewModel.setDialogFlags(clearPara = true); viewModel.annotations.toggleBookmark(text.take(80), blockIdx) },
            onDismiss = { viewModel.setDialogFlags(clearPara = true) }
        )
    }

    state.activeLightboxImage?.let { loaded ->
        ReaderImageLightbox(
            loadedImage = loaded,
            galleryIndex = state.activeLightboxIndex,
            galleryTotal = state.bookImages.size,
            onNavigateGallery = if (state.bookImages.size > 1) viewModel.media::navigateGallery else null,
            onCopy = viewModel.media::copyActiveImage,
            onSave = viewModel.media::saveActiveImage,
            onShare = { viewModel.media.shareActiveImage(context) },
            onDismiss = viewModel.media::dismissLightbox
        )
    }
}
