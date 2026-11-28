package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.audio.StreamingAudioTrackPlayer
import com.aitts.v2.data.AppPreferencesRepository
import com.aitts.v2.engine.audio.AudioEnhancer
import com.aitts.v2.engine.synthesis.SynthesisInput
import com.aitts.v2.engine.synthesis.SynthesisOrchestrator
import com.aitts.v2.engine.synthesis.SynthesisSink
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.SentenceSegment
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.parser.BookFormatParser
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.service.FloatingSubtitleOverlay
import com.aitts.v2.service.PlaybackNotificationController
import com.aitts.v2.service.SleepTimerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Continuous cross-chapter book TTS controller using pre-decoded 24kHz PCM lookahead,
 * single-AudioTrack gapless transitions across chapters, and per-chunk voice/speed reload.
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
            runContinuousQueue(generation, record, index, initialChapter, startBlockIndex)
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
            if (clearBookContext) ReaderTtsState()
            else it.copy(isSynthesizing = false, isPlaying = false, errorMessage = null)
        }
        return previous
    }

    private suspend fun runContinuousQueue(
        generation: Long,
        record: BookRecord,
        index: BookIndex,
        initialChapter: ChapterContent,
        startBlockIndex: Int
    ) {
        val sessionId = "book_${UUID.randomUUID().toString().take(6)}"
        activeSessionId = sessionId
        val parser = BookFormatRegistry.parserFor(record.format)
        val bookFile = bookLibrary.bookFile(record)
        val initReader = bookLibrary.settingsFlow.value
        val initRef = index.chapters.getOrNull(initialChapter.chapterIndex)
        val initHref = initRef?.locator.orEmpty()
        val resolvedInit = if (parser is com.aitts.v2.reader.parser.EpubBookParser && !initReader.ttsSmartRubyReading && initRef != null) {
            parser.loadChapter(bookFile, initRef, smartRubyReading = false)
        } else initialChapter
        val queue = ArrayList<BookTtsChunk>()
        queue.addAll(chunkFromChapter(resolvedInit, initHref, startBlockIndex.coerceAtLeast(0), initReader))
        var nextChapIdx = resolvedInit.chapterIndex + 1

        while (queue.isEmpty() && bookLibrary.settingsFlow.value.autoNextChapterOnTts && nextChapIdx < index.chapters.size) {
            queue.addAll(
                ReaderTtsChunker.loadAndChunkChapter(
                    parser, bookFile, index, nextChapIdx++, bookLibrary.settingsFlow.value,
                    preferences.rulesFlow.value, preferences.settingsFlow.value
                )
            )
        }
        if (queue.isEmpty()) {
            if (generation == generationCounter.get()) _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false) }
            return
        }

        previewPlayer.ensureStreamOpen(TARGET_RATE)
        try {
            coroutineScope { runQueueLoop(this, generation, sessionId, record, index, parser, bookFile, queue, nextChapIdx) }
            if (generation == generationCounter.get()) {
                previewPlayer.finishStream(); scheduleNotificationDismiss()
                _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false, activeSentenceText = "", highlightAnchor = "") }
            }
        } catch (_: CancellationException) {
            if (generation == generationCounter.get()) {
                previewPlayer.stop(); _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = false) }
            }
        } catch (e: Throwable) {
            if (generation == generationCounter.get()) {
                previewPlayer.stop(); PlaybackNotificationController.cancelImmediately(appContext)
                _stateFlow.update {
                    it.copy(isSynthesizing = false, isPlaying = false, errorMessage = "听书合成失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    private suspend fun runQueueLoop(
        workScope: CoroutineScope,
        generation: Long,
        sessionId: String,
        record: BookRecord,
        index: BookIndex,
        parser: BookFormatParser,
        bookFile: File,
        queue: ArrayList<BookTtsChunk>,
        startNextChapIdx: Int
    ) {
        val prefetched = HashMap<Int, Deferred<Result<ByteArray>>>()
        var nextChapIdx = startNextChapIdx
        var lastFingerprint = ""
        var lastChunkSig = ""
        var cursor = 0
        while (cursor < queue.size && generation == generationCounter.get()) {
            val liveProvider = preferences.getActiveProvider()
            val liveGlobal = preferences.settingsFlow.value
            val liveReader = bookLibrary.settingsFlow.value
            val fp = ReaderTtsChunker.configFingerprint(liveProvider, liveGlobal)
            val chunkSig = ReaderTtsChunker.chunkFingerprint(liveReader)
            if (lastChunkSig.isNotEmpty() && lastChunkSig != chunkSig && cursor > 0) {
                prefetched.values.forEach { it.cancel() }; prefetched.clear()
                nextChapIdx = ReaderTtsChunker.rebuildRemainingChapterQueue(
                    queue, cursor - 1, parser, bookFile, index, liveReader, preferences.rulesFlow.value, liveGlobal
                )
            }
            if (lastFingerprint.isNotEmpty() && lastFingerprint != fp) {
                prefetched.values.forEach { it.cancel() }; prefetched.clear()
            }
            lastFingerprint = fp; lastChunkSig = chunkSig

            val lookahead = liveReader.ttsPreloadCount.coerceIn(1, 4)
            while (liveReader.autoNextChapterOnTts && (queue.size - cursor) <= lookahead + 1 && nextChapIdx < index.chapters.size) {
                queue.addAll(
                    ReaderTtsChunker.loadAndChunkChapter(
                        parser, bookFile, index, nextChapIdx++, liveReader, preferences.rulesFlow.value, liveGlobal
                    )
                )
            }
            val input = buildSynthesisInput(liveProvider, liveGlobal)
            schedulePrefetchWindow(workScope, queue, cursor, lookahead, prefetched, liveProvider, input, sessionId)
            playSingleChunk(
                generation, record, index.chapters.size, queue[cursor],
                liveProvider, input, sessionId, cursor, prefetched.remove(cursor)
            )
            if (generation == generationCounter.get() && liveGlobal.sentencePauseMs > 0 && cursor + 1 < queue.size) {
                val silenceBytes = ((TARGET_RATE * 2) * (liveGlobal.sentencePauseMs / 1000.0)).toInt() and 0x7FFFFFFE
                if (silenceBytes > 0) previewPlayer.writeChunk(ByteArray(silenceBytes))
            }
            cursor++
        }
    }

    private fun schedulePrefetchWindow(
        workScope: CoroutineScope,
        queue: List<BookTtsChunk>,
        cursor: Int,
        lookahead: Int,
        prefetched: MutableMap<Int, Deferred<Result<ByteArray>>>,
        liveProvider: TtsProviderConfig,
        input: SynthesisInput,
        sessionId: String
    ) {
        val endPrefetch = minOf(cursor + 1 + lookahead, queue.size)
        for (k in (cursor + 1) until endPrefetch) {
            if (!prefetched.containsKey(k)) {
                val ch = queue[k]
                val creds = ReaderTtsChunker.rotateCredentials(preferences.getCredentials(liveProvider.id), k)
                prefetched[k] = workScope.async {
                    orchestrator.synthesizeSegmentPcm(ch.text, liveProvider, creds, input, sessionId, TARGET_RATE)
                }
            }
        }
    }

    private suspend fun playSingleChunk(
        generation: Long,
        record: BookRecord,
        totalChapters: Int,
        chunk: BookTtsChunk,
        provider: TtsProviderConfig,
        input: SynthesisInput,
        sessionId: String,
        chunkIndex: Int,
        prefetchedPcm: Deferred<Result<ByteArray>>?
    ) {
        if (generation != generationCounter.get()) return
        onChunkStarting(record, totalChapters, chunk, provider.name, prefetchedPcm == null)
        val gain = sleepTimer.getFadeVolumeFactor().coerceIn(0f, 1.0f)
        val creds = ReaderTtsChunker.rotateCredentials(preferences.getCredentials(provider.id), chunkIndex)
        if (prefetchedPcm != null) {
            val rawPcm = prefetchedPcm.await().getOrElse { throw it }
            emitPcmToTrack(generation, rawPcm, gain)
            return
        }
        var streamError: Throwable? = null
        val sink = object : SynthesisSink {
            override fun onStart(sampleRate: Int) = generation == generationCounter.get()
            override fun onPcmChunk(pcm16Bytes: ByteArray) {
                if (generation != generationCounter.get()) return
                markPlayingIfNeeded()
                previewPlayer.writeChunk(pcm16Bytes)
            }
            override fun onDone() = Unit
            override fun onError(error: Throwable) { streamError = error }
        }
        orchestrator.streamSegmentToSink(
            SentenceSegment(chunk.text, chunk.blockIndex), provider, creds, input, gain, sessionId, sink
        )
        streamError?.let { throw it }
    }

    private fun emitPcmToTrack(generation: Long, rawPcm: ByteArray, gain: Float) {
        if (generation != generationCounter.get() || rawPcm.isEmpty()) return
        markPlayingIfNeeded()
        val finalPcm = if (gain < 0.999f) AudioEnhancer().processPcm(rawPcm, 1, gain, trimSilence = false) else rawPcm
        var off = 0
        while (off < finalPcm.size && generation == generationCounter.get()) {
            val len = minOf(2048, finalPcm.size - off)
            previewPlayer.writeChunk(finalPcm.copyOfRange(off, off + len))
            off += len
        }
    }

    private fun markPlayingIfNeeded() {
        val st = _stateFlow.value
        if (st.isSynthesizing || !st.isPlaying) _stateFlow.update { it.copy(isSynthesizing = false, isPlaying = true) }
    }

    private fun onChunkStarting(
        record: BookRecord,
        totalChapters: Int,
        chunk: BookTtsChunk,
        providerName: String,
        synthesizing: Boolean
    ) {
        _stateFlow.update { cur ->
            ReaderTtsState(
                bookId = record.id, bookTitle = record.title, chapterIndex = chunk.chapterIndex,
                chapterHref = chunk.chapterHref, chapterTitle = chunk.chapterTitle,
                activeBlockIndex = chunk.blockIndex, activeSentenceText = chunk.text,
                highlightAnchor = chunk.highlightAnchor,
                isSynthesizing = synthesizing && !cur.isPlaying, isPlaying = cur.isPlaying
            )
        }
        bookLibrary.updateProgress(record.id, chunk.chapterIndex, chunk.blockIndex, totalChapters)
        val settings = preferences.settingsFlow.value
        if (settings.isFloatingSubtitleEnabled) subtitleOverlay.updateText(chunk.text)
        if (settings.playbackNotificationEnabled) {
            PlaybackNotificationController.show(
                context = appContext, providerName = "《${record.title}》",
                voiceId = "${chunk.chapterTitle} · $providerName", sentence = chunk.text,
                isSubtitleVisible = settings.isFloatingSubtitleEnabled
            )
        }
    }

    private fun chunkFromChapter(content: ChapterContent, href: String, startBlk: Int, rs: ReaderSettings) =
        ReaderTtsChunker.buildChunks(
            content.chapterIndex, content.title, href, content.blocks, startBlk,
            rs.ttsChunkMode, rs.ttsTargetChunkChars, preferences.rulesFlow.value, preferences.settingsFlow.value
        )

    private fun buildSynthesisInput(active: TtsProviderConfig, settings: GlobalSettings) = SynthesisInput(
        "", settings, active, preferences.providersFlow.value, preferences.rulesFlow.value,
        credentialsLookup = { preferences.getCredentials(it) }, fadeGainProvider = { sleepTimer.getFadeVolumeFactor() }
    )

    private fun scheduleNotificationDismiss() = PlaybackNotificationController.scheduleDelayedDismiss(
        context = appContext,
        onDismissOverlay = { if (preferences.settingsFlow.value.isFloatingSubtitleEnabled) subtitleOverlay.hide() }
    )

    companion object {
        private const val TARGET_RATE = 24000
    }
}
