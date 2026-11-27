package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.audio.StreamingAudioTrackPlayer
import com.aitts.v2.data.AppPreferencesRepository
import com.aitts.v2.engine.synthesis.SynthesisInput
import com.aitts.v2.engine.synthesis.SynthesisOrchestrator
import com.aitts.v2.engine.synthesis.SynthesisSink
import com.aitts.v2.engine.text.TextPipeline
import com.aitts.v2.model.SentenceSegment
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.service.FloatingSubtitleOverlay
import com.aitts.v2.service.PlaybackNotificationController
import com.aitts.v2.service.SleepTimerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ReaderTtsState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val activeBlockIndex: Int = -1,
    val activeSentenceText: String = "",
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Coordinates continuous chapter-by-chapter book playback through SynthesisOrchestrator,
 * maintaining zero-drift paragraph highlight state and auto-advancing across chapters.
 */
class ReaderTtsController(
    context: Context,
    private val orchestrator: SynthesisOrchestrator,
    private val previewPlayer: StreamingAudioTrackPlayer,
    private val preferences: AppPreferencesRepository,
    private val bookLibrary: BookLibraryRepository,
    private val sleepTimer: SleepTimerController,
    private val subtitleOverlay: FloatingSubtitleOverlay,
    private val onBeforeStart: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stateFlow = MutableStateFlow(ReaderTtsState())
    val stateFlow: StateFlow<ReaderTtsState> = _stateFlow.asStateFlow()

    private var activeJob: Job? = null
    private var activeSessionId: String? = null
    private val generationCounter = AtomicLong(0L)

    init {
        scope.launch {
            sleepTimer.expiredFlow.collect {
                if (_stateFlow.value.isPlaying || _stateFlow.value.isSynthesizing) {
                    stop()
                    _stateFlow.update { it.copy(errorMessage = "睡眠定时结束，已停止听书") }
                }
            }
        }
    }

    fun startReading(
        record: BookRecord,
        index: BookIndex,
        initialChapter: ChapterContent,
        startBlockIndex: Int
    ) {
        onBeforeStart()
        val generation = generationCounter.incrementAndGet()
        val previousJob = stopInternal(clearBookContext = false)

        activeJob = scope.launch {
            previousJob?.cancelAndJoin()
            if (generation != generationCounter.get()) return@launch
            runChapterLoop(generation, record, index, initialChapter, startBlockIndex)
        }
    }

    fun stop() {
        generationCounter.incrementAndGet()
        stopInternal(clearBookContext = true)
    }

    fun clearError() {
        _stateFlow.update { it.copy(errorMessage = null) }
    }

    private fun stopInternal(clearBookContext: Boolean): Job? {
        activeSessionId?.let { orchestrator.cancelSession(it) }
        activeSessionId = null
        val previous = activeJob
        previous?.cancel()
        activeJob = null
        previewPlayer.stop()
        PlaybackNotificationController.cancelImmediately(appContext)
        _stateFlow.update {
            if (clearBookContext) {
                ReaderTtsState()
            } else {
                it.copy(isSynthesizing = false, isPlaying = false, errorMessage = null)
            }
        }
        return previous
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.runChapterLoop(
        generation: Long,
        record: BookRecord,
        index: BookIndex,
        initialChapter: ChapterContent,
        startBlockIndex: Int
    ) {
        val parser = BookFormatRegistry.parserFor(record.format)
        val bookFile = bookLibrary.bookFile(record)
        var chapIdx = initialChapter.chapterIndex
        var chapContent = initialChapter
        var blkStart = startBlockIndex.coerceAtLeast(0)

        while (isActive && generation == generationCounter.get()) {
            val segments = buildTaggedSegments(chapContent, blkStart)
            if (segments.isEmpty()) {
                val autoNext = bookLibrary.settingsFlow.value.autoNextChapterOnTts
                if (autoNext && chapIdx + 1 < index.chapters.size) {
                    chapIdx++
                    blkStart = 0
                    chapContent = parser.loadChapter(bookFile, index.chapters[chapIdx])
                    continue
                }
                if (generation == generationCounter.get()) {
                    _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false) }
                }
                break
            }

            val completed = executeSingleChapter(generation, record, index, chapIdx, chapContent.title, segments)
            if (!completed || generation != generationCounter.get()) break

            val autoNext = bookLibrary.settingsFlow.value.autoNextChapterOnTts
            if (autoNext && chapIdx + 1 < index.chapters.size) {
                chapIdx++
                blkStart = 0
                chapContent = parser.loadChapter(bookFile, index.chapters[chapIdx])
                continue
            }

            scheduleNotificationDismiss()
            _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false, activeSentenceText = "") }
            break
        }
    }

    private suspend fun executeSingleChapter(
        generation: Long,
        record: BookRecord,
        index: BookIndex,
        chapIdx: Int,
        chapterTitle: String,
        segments: List<SentenceSegment>
    ): Boolean {
        if (generation == generationCounter.get()) {
            _stateFlow.update {
                ReaderTtsState(
                    bookId = record.id,
                    bookTitle = record.title,
                    chapterIndex = chapIdx,
                    chapterTitle = chapterTitle,
                    activeBlockIndex = segments.first().sourceIndex,
                    isSynthesizing = true,
                    isPlaying = false
                )
            }
        }

        val sessionId = "book_${UUID.randomUUID().toString().take(6)}"
        activeSessionId = sessionId
        var completed = false
        var failedError: Throwable? = null
        val sink = createBookSink(
            generation = generation,
            record = record,
            totalChapters = index.chapters.size,
            chapterIndex = chapIdx,
            chapterTitle = chapterTitle,
            onComplete = { completed = true },
            onFailure = { err -> failedError = err }
        )

        try {
            orchestrator.execute(sessionId, buildSynthesisInput(segments), sink)
        } catch (_: CancellationException) {
            if (generation == generationCounter.get()) {
                previewPlayer.stop()
                _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false) }
            }
            return false
        }

        if (generation != generationCounter.get()) return false
        if (failedError != null) {
            PlaybackNotificationController.cancelImmediately(appContext)
            _stateFlow.update {
                it.copy(
                    isSynthesizing = false,
                    isPlaying = false,
                    errorMessage = "听书合成失败: ${failedError?.message ?: "未知错误"}"
                )
            }
            return false
        }
        return completed
    }

    private fun buildTaggedSegments(chapter: ChapterContent, startBlockIndex: Int): List<SentenceSegment> {
        val settings = preferences.settingsFlow.value
        val rules = preferences.rulesFlow.value
        val result = mutableListOf<SentenceSegment>()
        for (block in chapter.blocks) {
            if (block.index < startBlockIndex) continue
            val text = block.speakableText?.takeIf { it.isNotBlank() } ?: continue
            val pieces = TextPipeline.process(text, rules, settings)
            for (piece in pieces) {
                if (piece.text.isNotBlank()) {
                    result.add(piece.copy(sourceIndex = block.index))
                }
            }
        }
        return result
    }

    private fun buildSynthesisInput(segments: List<SentenceSegment>): SynthesisInput {
        val active = preferences.getActiveProvider()
        return SynthesisInput(
            rawText = "",
            settings = preferences.settingsFlow.value,
            activeConfig = active,
            allProviders = preferences.providersFlow.value,
            rules = preferences.rulesFlow.value,
            credentialsLookup = { id -> preferences.getCredentials(id) },
            fadeGainProvider = { sleepTimer.getFadeVolumeFactor() },
            preSegmented = segments
        )
    }

    private fun createBookSink(
        generation: Long,
        record: BookRecord,
        totalChapters: Int,
        chapterIndex: Int,
        chapterTitle: String,
        onComplete: () -> Unit,
        onFailure: (Throwable) -> Unit
    ): SynthesisSink {
        val active = preferences.getActiveProvider()
        val labelTitle = "《${record.title}》"
        return object : SynthesisSink {
            override fun onStart(sampleRate: Int): Boolean {
                if (generation != generationCounter.get()) return false
                previewPlayer.openStream(sampleRate)
                return true
            }

            override fun onSegmentMeta(segmentIndex: Int, totalSegments: Int, segment: SentenceSegment) {
                if (generation != generationCounter.get()) return
                val blkIdx = if (segment.sourceIndex >= 0) segment.sourceIndex else 0
                _stateFlow.update {
                    it.copy(activeBlockIndex = blkIdx, activeSentenceText = segment.text)
                }
                bookLibrary.updateProgress(record.id, chapterIndex, blkIdx, totalChapters)
                val settings = preferences.settingsFlow.value
                if (settings.isFloatingSubtitleEnabled) {
                    subtitleOverlay.updateText(segment.text)
                }
                if (settings.playbackNotificationEnabled) {
                    PlaybackNotificationController.show(
                        context = appContext,
                        providerName = labelTitle,
                        voiceId = "$chapterTitle · ${active.name}",
                        sentence = segment.text,
                        isSubtitleVisible = settings.isFloatingSubtitleEnabled
                    )
                }
            }

            override fun onPcmChunk(pcm16Bytes: ByteArray) {
                if (generation != generationCounter.get()) return
                if (_stateFlow.value.isSynthesizing || !_stateFlow.value.isPlaying) {
                    _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = true) }
                }
                previewPlayer.writeChunk(pcm16Bytes)
            }

            override fun onDone() {
                if (generation == generationCounter.get()) {
                    previewPlayer.finishStream()
                    onComplete()
                }
            }

            override fun onError(error: Throwable) {
                if (generation == generationCounter.get()) {
                    previewPlayer.stop()
                    onFailure(error)
                }
            }
        }
    }

    private fun scheduleNotificationDismiss() {
        PlaybackNotificationController.scheduleDelayedDismiss(
            context = appContext,
            onDismissOverlay = {
                val cur = preferences.settingsFlow.value
                if (cur.isFloatingSubtitleEnabled) {
                    subtitleOverlay.hide()
                }
            }
        )
    }
}
