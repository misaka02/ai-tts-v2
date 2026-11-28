package com.aitts.v2.ui.reader

import android.content.Context
import com.aitts.v2.di.AppContainer
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.parser.BookFormatRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates TTS voice/speed/pitch/subtitle controls and full-text search
 * for ReaderViewModel to keep ViewModel focused on state and navigation.
 */
internal class ReaderTtsSearchHelper(
    private val container: AppContainer,
    private val bookId: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ReaderUiState>
) {
    private var lastSyncedTtsChapter = -1
    private var searchJob: Job? = null

    fun resetSyncedChapter(chapIdx: Int) {
        lastSyncedTtsChapter = chapIdx
    }

    fun startTtsFromReadium(quote: String, locatorJson: String) {
        val st = state.value
        val record = st.book ?: return
        val index = st.index ?: return
        val pub = st.readiumPublication ?: return
        val loc = container.readiumEngine.parseLocator(locatorJson)
        val rawHref = loc?.href?.toString().orEmpty().substringBefore('#').trimStart('/')
        val resolvedIdx = if (rawHref.isNotBlank()) {
            val exact = index.chapters.indexOfFirst { it.locator.substringBefore('#').trimStart('/') == rawHref }
            if (exact >= 0) exact else index.chapters.indexOfFirst {
                val c = it.locator.substringBefore('#').trimStart('/')
                c.endsWith("/$rawHref") || rawHref.endsWith("/$c")
            }
        } else -1
        val chapIdx = if (resolvedIdx in index.chapters.indices) resolvedIdx else {
            (loc?.let { container.readiumEngine.resolveChapterIndex(pub, it) } ?: st.currentChapterIndex)
                .coerceIn(0, (index.chapters.size - 1).coerceAtLeast(0))
        }
        val ref = index.chapters.getOrNull(chapIdx) ?: return
        val smartRuby = container.bookLibrary.settingsFlow.value.ttsSmartRubyReading
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bookFile = container.bookLibrary.bookFile(record)
                val parser = BookFormatRegistry.parserFor(record.format)
                if (parser is com.aitts.v2.reader.parser.EpubBookParser) parser.loadChapter(bookFile, ref, smartRuby)
                else parser.loadChapter(bookFile, ref)
            }
            val needle = quote.trim().take(16)
            val matchIdx = if (needle.isNotBlank()) {
                loaded.blocks.firstOrNull { it.speakableText?.contains(needle) == true }?.index ?: 0
            } else {
                val prog = (loc?.locations?.progression ?: 0.0).toFloat()
                (prog * loaded.blocks.size).toInt().coerceIn(0, (loaded.blocks.size - 1).coerceAtLeast(0))
            }
            lastSyncedTtsChapter = chapIdx
            container.readerTts.startReading(record, index, loaded, matchIdx)
        }
    }

    fun startTtsFromBlock(blockIndex: Int) {
        val st = state.value
        val record = st.book ?: return
        val index = st.index ?: return
        if (st.readiumPublication != null) {
            startTtsFromReadium("", st.currentLocatorJson)
            return
        }
        val chapter = st.chapter ?: return
        lastSyncedTtsChapter = chapter.chapterIndex
        container.readerTts.startReading(record, index, chapter, blockIndex.coerceAtLeast(0))
    }

    suspend fun observeTtsSync(
        onReadiumChapterJump: (Int, String) -> Unit,
        onNativeChapterLoad: suspend (BookRecord, BookIndex, Int, Int) -> Unit
    ) {
        container.readerTts.stateFlow.collect { tts ->
            tts.errorMessage?.let { msg ->
                if (tts.bookId == bookId || tts.bookId == null) {
                    state.update { it.copy(statusMessage = msg) }
                    container.readerTts.clearError()
                }
            }
            if (tts.bookId != bookId || (!tts.isPlaying && !tts.isSynthesizing)) {
                lastSyncedTtsChapter = -1
                return@collect
            }
            val st = state.value
            if (st.readiumPublication != null) {
                val curHref = container.readiumEngine.parseLocator(st.currentLocatorJson)?.href?.toString().orEmpty()
                val sameHref = tts.chapterHref.isNotBlank() && curHref.isNotBlank() &&
                    (curHref.substringBefore('#').trimStart('/') == tts.chapterHref.substringBefore('#').trimStart('/'))
                if (!sameHref && tts.chapterIndex != st.currentChapterIndex && tts.chapterIndex != lastSyncedTtsChapter) {
                    lastSyncedTtsChapter = tts.chapterIndex
                    onReadiumChapterJump(tts.chapterIndex, tts.chapterHref)
                }
                return@collect
            }
            val rec = st.book ?: return@collect
            val idx = st.index ?: return@collect
            val curChap = st.chapter?.chapterIndex ?: return@collect
            if (tts.chapterIndex != curChap && tts.chapterIndex != lastSyncedTtsChapter) {
                lastSyncedTtsChapter = tts.chapterIndex
                onNativeChapterLoad(rec, idx, tts.chapterIndex, tts.activeBlockIndex.coerceAtLeast(0))
            }
        }
    }

    fun updateActiveVoice(speed: Float? = null, pitch: Float? = null) {
        val cur = container.preferences.getActiveProvider()
        val acoustic = ProviderDescriptorRegistry.descriptorFor(cur.type).acousticSpec
        val sr = acoustic.speedSliderRange
        val nextSpeed = speed?.coerceIn(sr.start, sr.endInclusive) ?: cur.speed
        val nextPitch = if (pitch != null && AcousticMapper.supportsNativePitch(cur.type, cur.voiceId)) {
            acoustic.pitchRange?.let { pitch.coerceIn(it.start, it.endInclusive) } ?: cur.pitch
        } else cur.pitch
        container.preferences.upsertProvider(cur.copy(speed = nextSpeed, pitch = nextPitch))
    }

    fun toggleFloatingSubtitle(context: Context, enabled: Boolean) {
        if (enabled && !PermissionHelper.check(context).overlayGranted) {
            PermissionHelper.openOverlayPermission(context)
            return
        }
        val cur = container.preferences.settingsFlow.value
        container.preferences.updateSettings(cur.copy(isFloatingSubtitleEnabled = enabled))
        if (enabled) container.subtitleOverlay.show() else container.subtitleOverlay.hide()
    }

    fun stopTts() = container.readerTts.stop()
    fun selectProvider(providerId: String) = container.preferences.setActiveProviderId(providerId)
    fun setSleepTimer(minutes: Int) =
        if (minutes <= 0) container.sleepTimer.stop() else container.sleepTimer.start(minutes)

    fun runBookSearch(query: String) {
        state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            state.update { it.copy(searchHits = emptyList(), searchLocators = emptyList(), isSearching = false) }
            return
        }
        val st = state.value
        val record = st.book ?: return
        state.update { it.copy(isSearching = true) }
        searchJob = scope.launch {
            val pub = st.readiumPublication
            if (pub != null) {
                val entries = container.readiumEngine.searchPublication(pub, trimmed)
                state.update {
                    it.copy(
                        searchHits = entries.map { e -> e.hit },
                        searchLocators = entries.map { e -> e.locatorJson },
                        isSearching = false
                    )
                }
            } else {
                val chapters = st.index?.chapters ?: return@launch
                val hits = withContext(Dispatchers.IO) {
                    BookFormatRegistry.searchBook(container.bookLibrary.bookFile(record), record.format, chapters, trimmed)
                }
                state.update { it.copy(searchHits = hits, searchLocators = emptyList(), isSearching = false) }
            }
        }
    }
}
