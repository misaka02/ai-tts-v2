package com.aitts.v2.ui.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.parser.BookFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderSheet { TOC, SEARCH, APPEARANCE, TTS }

data class ResolvedReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val highlightBg: Color,
    val accent: Color
)

data class ReaderUiState(
    val book: BookRecord? = null,
    val index: BookIndex? = null,
    val chapter: ChapterContent? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val visibleBlockIndex: Int = 0,
    val pendingScrollBlockIndex: Int? = null,
    val showChrome: Boolean = false,
    val activeSheet: ReaderSheet? = null,
    val searchQuery: String = "",
    val searchHits: List<BookSearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val statusMessage: String? = null
)

class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val readerSettingsFlow: StateFlow<ReaderSettings> = container.bookLibrary.settingsFlow
    val ttsStateFlow: StateFlow<ReaderTtsState> = container.readerTts.stateFlow
    val settingsFlow: StateFlow<GlobalSettings> = container.preferences.settingsFlow
    val providersFlow: StateFlow<List<TtsProviderConfig>> = container.preferences.providersFlow
    val activeProviderFlow: StateFlow<TtsProviderConfig> = combine(
        container.preferences.settingsFlow,
        container.preferences.providersFlow
    ) { _, _ -> container.preferences.getActiveProvider() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), container.preferences.getActiveProvider())

    val sleepActiveFlow: StateFlow<Boolean> = container.sleepTimer.isActiveFlow
    val sleepRemainingFlow: StateFlow<Int> = container.sleepTimer.remainingSecondsFlow

    private var lastSavedBlock = -1
    private var lastSavedTimeMs = 0L
    private var lastSyncedTtsChapter = -1
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val record = container.bookLibrary.getBook(bookId)
            if (record == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "书籍不存在或已被删除") }
                return@launch
            }
            val loadedIndex = withContext(Dispatchers.IO) {
                container.bookLibrary.loadBookIndex(bookId) ?: run {
                    val file = container.bookLibrary.bookFile(record)
                    BookFormatRegistry.parserFor(record.format).indexBook(file, record.title).also {
                        container.bookLibrary.saveBookIndex(bookId, it)
                    }
                }
            }
            _uiState.update { it.copy(book = record, index = loadedIndex) }
            val startChap = record.progress.chapterIndex.coerceIn(0, (loadedIndex.chapters.size - 1).coerceAtLeast(0))
            val startBlock = record.progress.blockIndex.coerceAtLeast(0)
            loadChapterInternal(record, loadedIndex, startChap, startBlock)
        }
        viewModelScope.launch {
            container.bookLibrary.booksFlow.collect { books ->
                val updated = books.firstOrNull { it.id == bookId } ?: return@collect
                _uiState.update { it.copy(book = updated) }
            }
        }
        viewModelScope.launch {
            container.readerTts.stateFlow.collect { tts ->
                tts.errorMessage?.let { msg ->
                    if (tts.bookId == bookId || tts.bookId == null) {
                        _uiState.update { it.copy(statusMessage = msg) }
                        container.readerTts.clearError()
                    }
                }
                if (tts.bookId != bookId || (!tts.isPlaying && !tts.isSynthesizing)) {
                    lastSyncedTtsChapter = -1
                    return@collect
                }
                val state = _uiState.value
                val rec = state.book ?: return@collect
                val idx = state.index ?: return@collect
                val curChap = state.chapter?.chapterIndex ?: return@collect
                if (tts.chapterIndex != curChap && tts.chapterIndex != lastSyncedTtsChapter) {
                    lastSyncedTtsChapter = tts.chapterIndex
                    loadChapterInternal(rec, idx, tts.chapterIndex, tts.activeBlockIndex.coerceAtLeast(0))
                } else if (tts.chapterIndex == curChap) {
                    lastSyncedTtsChapter = curChap
                }
            }
        }
    }

    fun goToChapter(chapterIndex: Int, targetBlockIndex: Int = 0) {
        val state = _uiState.value
        val record = state.book ?: return
        val index = state.index ?: return
        if (chapterIndex !in index.chapters.indices) return
        viewModelScope.launch {
            loadChapterInternal(record, index, chapterIndex, targetBlockIndex)
            container.bookLibrary.updateProgress(bookId, chapterIndex, targetBlockIndex, index.chapters.size)
        }
    }

    fun stepChapter(delta: Int) {
        val cur = _uiState.value.chapter?.chapterIndex ?: return
        goToChapter(cur + delta, 0)
    }

    private suspend fun loadChapterInternal(
        record: BookRecord,
        index: BookIndex,
        chapterIndex: Int,
        targetBlockIndex: Int
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val parser = BookFormatRegistry.parserFor(record.format)
                val file = container.bookLibrary.bookFile(record)
                parser.loadChapter(file, index.chapters[chapterIndex])
            }
        }
        res.fold(
            onSuccess = { loaded ->
                lastSavedBlock = targetBlockIndex
                _uiState.update {
                    it.copy(
                        chapter = loaded,
                        isLoading = false,
                        visibleBlockIndex = targetBlockIndex,
                        pendingScrollBlockIndex = targetBlockIndex,
                        activeSheet = null
                    )
                }
            },
            onFailure = { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "章节加载失败: ${err.message}")
                }
            }
        )
    }

    fun consumePendingScroll() {
        _uiState.update { it.copy(pendingScrollBlockIndex = null) }
    }

    fun onVisibleBlockChanged(blockIndex: Int) {
        val chapIdx = _uiState.value.chapter?.chapterIndex ?: return
        val totalChapters = _uiState.value.index?.chapters?.size ?: 1
        _uiState.update { it.copy(visibleBlockIndex = blockIndex) }
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(blockIndex - lastSavedBlock) >= 3 || now - lastSavedTimeMs >= 2000L) {
            lastSavedBlock = blockIndex
            lastSavedTimeMs = now
            container.bookLibrary.updateProgress(bookId, chapIdx, blockIndex, totalChapters)
        }
    }

    fun toggleChrome() {
        _uiState.update { it.copy(showChrome = !it.showChrome) }
    }

    fun openSheet(sheet: ReaderSheet?) {
        _uiState.update { it.copy(activeSheet = sheet) }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun toggleBookmark() {
        val state = _uiState.value
        val chapter = state.chapter ?: return
        val blkIdx = state.visibleBlockIndex
        val snippet = chapter.blocks.firstOrNull { it.index >= blkIdx && !it.speakableText.isNullOrBlank() }
            ?.speakableText
            ?: chapter.title
        val added = container.bookLibrary.toggleBookmark(bookId, chapter.chapterIndex, blkIdx, chapter.title, snippet)
        _uiState.update { it.copy(statusMessage = if (added) "已添加书签" else "已取消当前位置书签") }
    }

    fun removeBookmark(bookmarkId: String) {
        container.bookLibrary.removeBookmark(bookId, bookmarkId)
    }

    fun togglePlayPause() {
        val tts = container.readerTts.stateFlow.value
        if ((tts.isPlaying || tts.isSynthesizing) && tts.bookId == bookId) {
            container.readerTts.stop()
            return
        }
        val state = _uiState.value
        startTtsFromBlock(state.visibleBlockIndex)
    }

    fun startTtsFromBlock(blockIndex: Int) {
        val state = _uiState.value
        val record = state.book ?: return
        val index = state.index ?: return
        val chapter = state.chapter ?: return
        lastSyncedTtsChapter = chapter.chapterIndex
        container.readerTts.startReading(record, index, chapter, blockIndex.coerceAtLeast(0))
    }

    fun stopTts() {
        container.readerTts.stop()
    }

    fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(container.bookLibrary.settingsFlow.value)
        container.bookLibrary.updateSettings(next)
    }

    fun selectProvider(providerId: String) {
        container.preferences.setActiveProviderId(providerId)
    }

    fun updateActiveVoice(speed: Float? = null, pitch: Float? = null) {
        val cur = container.preferences.getActiveProvider()
        val acoustic = ProviderDescriptorRegistry.descriptorFor(cur.type).acousticSpec
        val speedRange = acoustic.speedSliderRange
        val nextSpeed = speed?.coerceIn(speedRange.start, speedRange.endInclusive) ?: cur.speed
        val nextPitch = if (pitch != null && AcousticMapper.supportsNativePitch(cur.type, cur.voiceId)) {
            val pr = acoustic.pitchRange
            if (pr != null) pitch.coerceIn(pr.start, pr.endInclusive) else cur.pitch
        } else {
            cur.pitch
        }
        container.preferences.upsertProvider(cur.copy(speed = nextSpeed, pitch = nextPitch))
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) container.sleepTimer.stop() else container.sleepTimer.start(minutes)
    }

    fun toggleFloatingSubtitle(context: Context, enabled: Boolean) {
        val perms = PermissionHelper.check(context)
        if (enabled && !perms.overlayGranted) {
            PermissionHelper.openOverlayPermission(context)
            return
        }
        val cur = container.preferences.settingsFlow.value
        container.preferences.updateSettings(cur.copy(isFloatingSubtitleEnabled = enabled))
        if (enabled) container.subtitleOverlay.show() else container.subtitleOverlay.hide()
    }

    fun runBookSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(searchHits = emptyList(), isSearching = false) }
            return
        }
        val record = _uiState.value.book ?: return
        val chapters = _uiState.value.index?.chapters ?: return
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) {
                BookFormatRegistry.searchBook(container.bookLibrary.bookFile(record), record.format, chapters, trimmed)
            }
            _uiState.update { it.copy(searchHits = hits, isSearching = false) }
        }
    }

    suspend fun loadBookImage(resourcePath: String): Bitmap? {
        val record = _uiState.value.book ?: return null
        return container.epubImageLoader.loadBookResource(record, resourcePath)
    }

    class Factory(
        private val container: AppContainer,
        private val bookId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(container, bookId) as T
    }
}
