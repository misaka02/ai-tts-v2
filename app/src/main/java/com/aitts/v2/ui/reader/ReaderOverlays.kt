package com.aitts.v2.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookFormat
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
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val useDarkBg = if (readerSettings.paperTheme == ReaderPaperTheme.FOLLOW_APP) {
                appDefaultIsDark
            } else {
                readerSettings.paperTheme.isDark
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
        }
    }

    DisposableEffect(appDefaultIsDark) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !appDefaultIsDark
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.attributes = window.attributes.apply { screenBrightness = -1f }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    sleepSec: Int,
    lightboxBitmap: Bitmap?,
    onDismissLightbox: () -> Unit
) {
    val context = LocalContext.current
    val curBook = state.book
    if (curBook != null) {
        val tocChapters = if (state.readiumToc.isNotEmpty()) state.readiumToc else state.index?.chapters.orEmpty()
        when (state.activeSheet) {
            ReaderSheet.TOC -> ReaderTocSheet(
                chapters = tocChapters,
                currentChapterIndex = state.currentChapterIndex,
                bookmarks = curBook.bookmarks,
                highlights = curBook.highlights,
                onSelectChapter = { viewModel.goToChapter(it, 0) },
                onSelectBookmark = { mark -> viewModel.jumpToLocatorJson(mark.locatorJson, mark.chapterIndex, mark.blockIndex) },
                onRemoveBookmark = viewModel::removeBookmark,
                onSelectHighlight = { h -> viewModel.jumpToLocatorJson(h.locatorJson, h.chapterIndex, h.blockIndex) },
                onEditHighlightNote = { h -> viewModel.handleHighlightAction(editNoteTarget = h) },
                onRemoveHighlight = { id -> viewModel.handleHighlightAction(null, removeId = id) },
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
                onImportFont = viewModel::importCustomFont,
                onDismiss = { viewModel.openSheet(null) }
            )
            ReaderSheet.TTS -> ReaderTtsSheet(
                activeProvider = activeProvider,
                providers = providers,
                isPlayingOrSynthesizing = ttsState.bookId == curBook.id && (ttsState.isPlaying || ttsState.isSynthesizing),
                sleepActive = sleepActive,
                sleepRemainingSec = sleepSec,
                floatingSubtitleEnabled = globalSettings.isFloatingSubtitleEnabled,
                onSelectProvider = viewModel.ttsSearch::selectProvider,
                onSpeedChange = { viewModel.ttsSearch.updateActiveVoice(speed = it) },
                onPitchChange = { viewModel.ttsSearch.updateActiveVoice(pitch = it) },
                onSetSleepTimer = viewModel.ttsSearch::setSleepTimer,
                onToggleSubtitle = { viewModel.ttsSearch.toggleFloatingSubtitle(context, it) },
                onStopTts = viewModel.ttsSearch::stopTts,
                onDismiss = { viewModel.openSheet(null) }
            )
            null -> Unit
        }
    }

    state.editingHighlight?.let { targetHighlight ->
        ReaderHighlightNoteDialog(
            highlight = targetHighlight,
            onSave = { note -> viewModel.handleHighlightAction(null, saveId = targetHighlight.id, saveNote = note) },
            onDismiss = { viewModel.handleHighlightAction(editNoteTarget = null) }
        )
    }

    lightboxBitmap?.let { bmp ->
        ReaderImageLightbox(bitmap = bmp, onDismiss = onDismissLightbox)
    }
}
