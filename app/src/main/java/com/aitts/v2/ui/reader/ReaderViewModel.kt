package com.aitts.v2.ui.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.importCustomFont
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.parser.BookFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator

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

    private val goToLocatorChannel = Channel<Locator>(capacity = 8)
    val goToLocatorEvents = goToLocatorChannel.receiveAsFlow()
    val readiumEngine get() = container.readiumEngine
    val localFontServer get() = container.localFontServer
    internal val ttsSearch = ReaderTtsSearchHelper(container, bookId, viewModelScope, _uiState)
    internal val media = ReaderMediaHelper(container, viewModelScope, _uiState)
    internal val annotations = ReaderAnnotationHelper(container, bookId, _uiState)

    private var lastSavedChapter = -1
    private var lastSavedBlock = -1
    private var lastSavedCharOffset = 0
    private var lastSavedTimeMs = 0L

    init {
        viewModelScope.launch { openInitialBook() }
        viewModelScope.launch {
            container.bookLibrary.booksFlow.collect { books ->
                books.firstOrNull { it.id == bookId }?.let { updated -> _uiState.update { it.copy(book = updated) } }
            }
        }
        viewModelScope.launch {
            ttsSearch.observeTtsSync(
                onReadiumChapterJump = { chapIdx ->
                    _uiState.value.readiumPublication?.let { pub ->
                        container.readiumEngine.locatorFromChapter(pub, chapIdx)?.let { goToLocatorChannel.trySend(it) }
                    }
                },
                onNativeChapterLoad = { rec, idx, chapIdx, blk -> loadNativeChapter(rec, idx, chapIdx, blk) }
            )
        }
    }

    private suspend fun openInitialBook() {
        val record = container.bookLibrary.getBook(bookId) ?: run {
            _uiState.update { it.copy(isLoading = false, errorMessage = "书籍不存在或已被删除") }; return
        }
        val bookFile = container.bookLibrary.bookFile(record)
        val loadedIndex = withContext(Dispatchers.IO) {
            container.bookLibrary.loadBookIndex(bookId) ?: BookFormatRegistry.parserFor(record.format)
                .indexBook(bookFile, record.title).also { container.bookLibrary.saveBookIndex(bookId, it) }
        }
        if (record.format == BookFormat.EPUB) {
            container.readiumEngine.openPublication(bookFile).fold(
                onSuccess = { pub ->
                    val toc = container.readiumEngine.extractTocItems(pub).map {
                        ChapterRef(it.index, it.title, it.locatorJson, depth = it.depth)
                    }
                    val savedLoc = container.readiumEngine.parseLocator(record.progress.locatorJson)
                        ?: container.readiumEngine.locatorFromChapter(pub, record.progress.chapterIndex)
                    val imgs = container.bookImageExporter.listBookImages(record, pub)
                    _uiState.update {
                        it.copy(
                            book = record, index = loadedIndex, readiumPublication = pub,
                            readiumInitialLocator = savedLoc, readiumToc = toc, bookImages = imgs,
                            currentChapterIndex = record.progress.chapterIndex,
                            currentChapterTitle = toc.firstOrNull()?.title ?: record.title,
                            currentLocatorJson = savedLoc?.toJSON()?.toString().orEmpty(),
                            currentProgression = record.progress.totalProgression, isLoading = false
                        )
                    }
                    val positions = container.readiumEngine.loadPositions(pub)
                    _uiState.update { it.copy(readiumPositions = positions) }
                },
                onFailure = { err ->
                    val imgs = container.bookImageExporter.listBookImages(record, null)
                    _uiState.update { it.copy(book = record, index = loadedIndex, bookImages = imgs) }
                    loadNativeChapter(record, loadedIndex, record.progress.chapterIndex, record.progress.blockIndex)
                    _uiState.update { it.copy(statusMessage = "已回退兼容模式: ${err.message}") }
                }
            )
            return
        }
        _uiState.update { it.copy(book = record, index = loadedIndex) }
        val startChap = record.progress.chapterIndex.coerceIn(0, (loadedIndex.chapters.size - 1).coerceAtLeast(0))
        loadNativeChapter(record, loadedIndex, startChap, record.progress.blockIndex, record.progress.charOffset)
    }

    fun onReadiumLocatorChanged(chapterIdx: Int, locatorJson: String, totalProg: Float, chapterTitle: String, posIdx: Int) {
        val total = _uiState.value.readiumPublication?.readingOrder?.size ?: _uiState.value.index?.chapters?.size ?: 1
        _uiState.update {
            it.copy(
                currentChapterIndex = chapterIdx, currentChapterTitle = chapterTitle,
                currentProgression = totalProg, currentLocatorJson = locatorJson, currentPositionIndex = posIdx
            )
        }
        val now = System.currentTimeMillis()
        if (chapterIdx != lastSavedChapter || now - lastSavedTimeMs >= 1500L) {
            lastSavedChapter = chapterIdx; lastSavedTimeMs = now
            container.bookLibrary.updateReadiumProgress(bookId, chapterIdx, locatorJson, totalProg, total)
        }
    }

    fun goToChapter(chapterIndex: Int, targetBlockIndex: Int = 0, landOnLastPage: Boolean = false) {
        val st = _uiState.value
        val pub = st.readiumPublication
        if (pub != null) {
            val targetLoc = st.readiumToc.getOrNull(chapterIndex)?.locator?.let { container.readiumEngine.parseLocator(it) }
                ?: container.readiumEngine.locatorFromChapter(pub, chapterIndex)
            if (targetLoc != null) { goToLocatorChannel.trySend(targetLoc); _uiState.update { it.copy(activeSheet = null) } }
            return
        }
        val record = st.book ?: return
        val index = st.index ?: return
        if (chapterIndex !in index.chapters.indices) return
        viewModelScope.launch {
            loadNativeChapter(record, index, chapterIndex, targetBlockIndex, 0, landOnLastPage)
            if (!landOnLastPage) {
                lastSavedChapter = chapterIndex; lastSavedBlock = targetBlockIndex; lastSavedCharOffset = 0
                lastSavedTimeMs = System.currentTimeMillis()
                container.bookLibrary.updateProgress(bookId, chapterIndex, targetBlockIndex, index.chapters.size, 0)
            } else lastSavedChapter = -1
        }
    }

    fun stepChapter(delta: Int, landOnLastPage: Boolean = false) {
        val st = _uiState.value
        val pub = st.readiumPublication
        if (pub != null) {
            val nextIdx = (st.currentChapterIndex + delta).coerceIn(0, (pub.readingOrder.size - 1).coerceAtLeast(0))
            container.readiumEngine.locatorFromChapter(pub, nextIdx)?.let { goToLocatorChannel.trySend(it) }
            return
        }
        st.chapter?.chapterIndex?.let { goToChapter(it + delta, 0, landOnLastPage) }
    }

    fun seekByProgression(fraction: Float) {
        val st = _uiState.value
        val pub = st.readiumPublication
        if (pub != null) {
            container.readiumEngine.locatorForProgression(pub, st.readiumPositions, fraction.toDouble())
                ?.let { goToLocatorChannel.trySend(it) }
            return
        }
        val chapters = st.index?.chapters ?: return
        if (chapters.isNotEmpty()) {
            val idx = (fraction.coerceIn(0f, 0.999f) * chapters.size).toInt().coerceIn(0, chapters.lastIndex)
            goToChapter(idx, 0)
        }
    }

    fun jumpToLocatorJson(locatorJson: String, fallbackChapter: Int, fallbackBlock: Int) {
        val loc = if (_uiState.value.readiumPublication != null) container.readiumEngine.parseLocator(locatorJson) else null
        if (loc != null) {
            goToLocatorChannel.trySend(loc)
            _uiState.update { it.copy(activeSheet = null) }
        } else goToChapter(fallbackChapter, fallbackBlock)
    }

    private suspend fun loadNativeChapter(
        record: BookRecord,
        index: BookIndex,
        chapterIndex: Int,
        targetBlockIndex: Int,
        targetCharOffset: Int = 0,
        landOnLastPage: Boolean = false
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val res = withContext(Dispatchers.IO) {
            runCatching {
                BookFormatRegistry.parserFor(record.format)
                    .loadChapter(container.bookLibrary.bookFile(record), index.chapters[chapterIndex])
            }
        }
        res.fold(
            onSuccess = { loaded ->
                lastSavedBlock = targetBlockIndex; lastSavedCharOffset = targetCharOffset
                val total = index.chapters.size.coerceAtLeast(1)
                _uiState.update {
                    it.copy(
                        chapter = loaded, currentChapterIndex = loaded.chapterIndex, currentChapterTitle = loaded.title,
                        currentProgression = ((loaded.chapterIndex + 1).toFloat() / total).coerceIn(0f, 1f),
                        isLoading = false, visibleBlockIndex = targetBlockIndex, pendingScrollBlockIndex = targetBlockIndex,
                        pendingScrollCharOffset = targetCharOffset, pendingLandOnLastPage = landOnLastPage, activeSheet = null
                    )
                }
            },
            onFailure = { err -> _uiState.update { it.copy(isLoading = false, errorMessage = "章节加载失败: ${err.message}") } }
        )
    }

    fun consumePendingScroll() =
        _uiState.update { it.copy(pendingScrollBlockIndex = null, pendingScrollCharOffset = 0, pendingLandOnLastPage = false) }

    fun onVisibleBlockChanged(blockIndex: Int, charOffset: Int = 0) {
        val chapIdx = _uiState.value.chapter?.chapterIndex ?: return
        val total = _uiState.value.index?.chapters?.size ?: 1
        _uiState.update { it.copy(visibleBlockIndex = blockIndex) }
        val now = System.currentTimeMillis()
        val changed = chapIdx != lastSavedChapter || kotlin.math.abs(blockIndex - lastSavedBlock) >= 3 ||
            (blockIndex == lastSavedBlock && charOffset != lastSavedCharOffset && now - lastSavedTimeMs >= 800L)
        if (changed || now - lastSavedTimeMs >= 2000L) {
            lastSavedChapter = chapIdx; lastSavedBlock = blockIndex; lastSavedCharOffset = charOffset; lastSavedTimeMs = now
            container.bookLibrary.updateProgress(bookId, chapIdx, blockIndex, total, charOffset)
        }
    }

    fun toggleChrome() = _uiState.update { it.copy(showChrome = !it.showChrome) }
    fun openSheet(sheet: ReaderSheet?) = _uiState.update { it.copy(activeSheet = sheet) }
    fun setParagraphMenu(pair: Pair<Int, String>?) = _uiState.update { it.copy(longPressParagraph = pair) }
    fun consumeStatusMessage() = _uiState.update { it.copy(statusMessage = null) }

    fun togglePlayPause() {
        val tts = container.readerTts.stateFlow.value
        if ((tts.isPlaying || tts.isSynthesizing) && tts.bookId == bookId) container.readerTts.stop()
        else ttsSearch.startTtsFromBlock(_uiState.value.visibleBlockIndex)
    }

    fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) =
        container.bookLibrary.updateSettings(transform(container.bookLibrary.settingsFlow.value))

    fun resetReaderSettings() {
        container.bookLibrary.updateSettings(ReaderSettings.Default)
        _uiState.update { it.copy(statusMessage = "已恢复默认排版设置") }
    }

    fun toggleDayNightQuick(appIsDark: Boolean) {
        val cur = container.bookLibrary.settingsFlow.value
        val curDark = when {
            cur.customBgHex != 0L ->
                androidx.core.graphics.ColorUtils.calculateLuminance(cur.customBgHex.toInt()) < 0.45
            cur.paperTheme == ReaderPaperTheme.FOLLOW_APP -> appIsDark
            else -> cur.paperTheme.isDark
        }
        val nextTheme = if (curDark) ReaderPaperTheme.CREAM else ReaderPaperTheme.NIGHT
        updateReaderSettings { it.copy(paperTheme = nextTheme, customBgHex = 0L, customTextHex = 0L) }
    }

    fun importCustomFont(uri: Uri) = container.bookLibrary.importCustomFont(uri).fold(
        onSuccess = { path ->
            updateReaderSettings { it.copy(customFontPath = path) }
            _uiState.update { it.copy(statusMessage = "已应用自定义字体") }
        },
        onFailure = { err -> _uiState.update { it.copy(statusMessage = "字体导入失败: ${err.message}") } }
    )

    suspend fun loadBookImage(resourcePath: String) =
        _uiState.value.book?.let { container.epubImageLoader.loadBookResource(it, resourcePath) }

    override fun onCleared() { runCatching { _uiState.value.readiumPublication?.close() }; super.onCleared() }

    class Factory(private val container: AppContainer, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(container, bookId) as T
    }
}
