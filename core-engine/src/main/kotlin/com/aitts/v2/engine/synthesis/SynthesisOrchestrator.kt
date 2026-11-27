package com.aitts.v2.engine.synthesis

import com.aitts.v2.engine.audio.AudioEnhancer
import com.aitts.v2.engine.audio.AudioPipeline
import com.aitts.v2.engine.audio.PcmResampler
import com.aitts.v2.engine.audio.SonicSpeedScaler
import com.aitts.v2.engine.audio.WavCodec
import com.aitts.v2.engine.cache.AudioDiskCache
import com.aitts.v2.engine.logging.EngineLogger
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.engine.provider.SynthesizedAudio
import com.aitts.v2.engine.provider.TtsProviderRegistry
import com.aitts.v2.engine.text.TextPipeline
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.SentenceSegment
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

interface SynthesisSink {
    fun onStart(sampleRate: Int): Boolean
    fun onSegmentStart(segmentIndex: Int, totalSegments: Int, text: String) {}
    fun onPcmChunk(pcm16Bytes: ByteArray)
    fun onDone()
    fun onError(error: Throwable)
}

data class SynthesisInput(
    val rawText: String,
    val settings: GlobalSettings,
    val activeConfig: TtsProviderConfig,
    val allProviders: List<TtsProviderConfig>,
    val rules: List<ReplacementRule>,
    val credentialsLookup: (String) -> ProviderCredentials,
    val systemSpeed: Float = 1.0f,
    val systemPitch: Float = 1.0f,
    val fadeGainProvider: () -> Float = { 1.0f }
)

/**
 * Session-isolated synthesis orchestrator with sliding-window prefetch,
 * single-layer retry, safe pre-first-byte fallback, and lossless-first audio pipeline.
 */
class SynthesisOrchestrator(
    private val registry: TtsProviderRegistry,
    private val audioPipeline: AudioPipeline,
    private val diskCache: AudioDiskCache,
    private val httpManager: HttpClientManager,
    private val logger: EngineLogger
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun cancelSession(sessionId: String) {
        if (sessionId.isBlank()) return
        activeJobs.remove(sessionId)?.cancel()
        httpManager.cancelSession(sessionId)
    }

    suspend fun execute(
        sessionId: String,
        input: SynthesisInput,
        sink: SynthesisSink
    ) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[sessionId] = job
        val targetRate = 24000

        try {
            val segments = TextPipeline.process(input.rawText, input.rules, input.settings)
            if (segments.isEmpty()) {
                if (sink.onStart(targetRate)) sink.onDone()
                return@withContext
            }
            if (!sink.onStart(targetRate)) {
                logger.warn("TTS_SVC", "SynthesisSink 初始化失败", sessionId = sessionId)
                return@withContext
            }

            val baseCfg = SynthesisHelpers.resolveSpeedAndPitch(input.activeConfig, input)
            val trace = "引擎=${baseCfg.name} 语速=${"%.2f".format(baseCfg.speed)}(sys=${"%.2f".format(input.systemSpeed)})"
            logger.info("TTS_SVC", "开始合成 (${segments.size} 段)", trace, sessionId = sessionId)

            val prefetchCache = ConcurrentHashMap<Int, Deferred<Result<SynthesizedAudio>>>()
            val windowSize = if (segments.size > 1) input.settings.preloadAheadCount.coerceIn(1, 3) else 1
            val activeCreds = input.credentialsLookup(baseCfg.id)
            val streamFirst = baseCfg.isStreamingEnabled && registry.get(baseCfg.type).capabilities.supportsNativePcmStreaming

            val startIdx = if (streamFirst) 1 else 0
            val initialEnd = minOf(startIdx + windowSize, segments.size)
            enqueuePrefetch(this, startIdx until initialEnd, segments, baseCfg, activeCreds, input, sessionId, prefetchCache)

            for (i in segments.indices) {
                currentCoroutineContext().ensureActive()
                val seg = segments[i]
                sink.onSegmentStart(i, segments.size, seg.text)

                if (segments.size > 1) {
                    val aheadEnd = minOf(i + windowSize + 1, segments.size)
                    enqueuePrefetch(this, (i + 1) until aheadEnd, segments, baseCfg, activeCreds, input, sessionId, prefetchCache)
                }

                val segCreds = SynthesisHelpers.rotateCredentials(activeCreds, i)
                val gain = input.fadeGainProvider().coerceIn(0f, 1.0f)
                val cap = registry.get(baseCfg.type).capabilities
                val useStream = baseCfg.isStreamingEnabled && cap.supportsNativePcmStreaming && !prefetchCache.containsKey(i)

                if (useStream) {
                    streamOrFallback(seg, baseCfg, segCreds, input, gain, sessionId, sink)
                } else {
                    val audio = (prefetchCache.remove(i) ?: async {
                        fetchBatch(seg.text, baseCfg, segCreds, input, sessionId)
                    }).await().getOrElse { throw it }
                    pushBatch(audio, baseCfg, input.settings, gain, targetRate, sink)
                }

                if (input.settings.sentencePauseMs > 0 && i < segments.size - 1) {
                    SynthesisHelpers.emitSilence(targetRate, input.settings.sentencePauseMs, sink)
                }
            }

            sink.onDone()
            logger.success("TTS_SVC", "合成播音完成", "共 ${segments.size} 段", sessionId = sessionId)
        } catch (ce: CancellationException) {
            logger.warn("TTS_SVC", "会话已取消", sessionId = sessionId)
            throw ce
        } catch (e: Throwable) {
            logger.error("TTS_SVC", "合成失败", e.message, sessionId = sessionId)
            sink.onError(e)
        } finally {
            activeJobs.remove(sessionId)
        }
    }

    private suspend fun streamOrFallback(
        seg: SentenceSegment,
        segCfg: TtsProviderConfig,
        segCreds: ProviderCredentials,
        input: SynthesisInput,
        gain: Float,
        sessionId: String,
        sink: SynthesisSink
    ) {
        val provider = registry.get(segCfg.type)
        if (input.settings.isAudioCacheEnabled) {
            diskCache.get(segCfg, seg.text)?.let { cached ->
                pushBatch(SynthesizedAudio(cached, segCfg.sampleRate, isRawPcm = false), segCfg, input.settings, gain, 24000, sink)
                return
            }
        }

        val enhancer = AudioEnhancer()
        val speedPlan = segCfg.resolveSpeed(isStreaming = true, capabilities = provider.capabilities)
        val scaler = if (speedPlan.needsClientSonic) {
            SonicSpeedScaler(24000, 1, speedPlan.clientSonicFactor, speedPlan.clientSonicPitch)
        } else {
            null
        }
        var emitted = false

        val streamRes = provider.synthesizeStream(ProviderSynthesisRequest(seg.text, segCfg, segCreds, sessionId)) { chunk ->
            currentCoroutineContext().ensureActive()
            val resampled = PcmResampler.resample(chunk.pcm16Bytes, chunk.sampleRate, 1, 24000, 1)
            val scaled = scaler?.processChunk(resampled) ?: resampled
            if (scaled.isNotEmpty()) {
                val processed = SynthesisHelpers.processChunkGain(enhancer, scaled, gain)
                if (processed.isNotEmpty()) {
                    emitted = true
                    SynthesisHelpers.emitInChunks(processed, sink)
                }
            }
        }

        if (streamRes.isSuccess) {
            scaler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                val processed = SynthesisHelpers.processChunkGain(enhancer, tail, gain)
                if (processed.isNotEmpty()) SynthesisHelpers.emitInChunks(processed, sink)
            }
            val rawFull = streamRes.getOrNull()?.bytes
            if (rawFull != null && rawFull.isNotEmpty() && input.settings.isAudioCacheEnabled) {
                val wav = WavCodec.encodeToWav(rawFull, streamRes.getOrNull()?.sampleRate ?: 24000, 1)
                diskCache.put(segCfg, seg.text, wav, input.settings.maxCacheSizeMb)
            }
            return
        }

        if (emitted) throw streamRes.exceptionOrNull() ?: IOException("流式传输中断")

        logger.warn("STREAM", "首包未到达即失败，回退批处理", streamRes.exceptionOrNull()?.message, sessionId = sessionId)
        val fallback = fetchBatch(seg.text, segCfg, segCreds, input, sessionId).getOrThrow()
        pushBatch(fallback, segCfg, input.settings, gain, 24000, sink)
    }

    private suspend fun fetchBatch(
        text: String,
        cfg: TtsProviderConfig,
        creds: ProviderCredentials,
        input: SynthesisInput,
        sessionId: String
    ): Result<SynthesizedAudio> {
        if (input.settings.isAudioCacheEnabled) {
            diskCache.get(cfg, text)?.let { cached ->
                return Result.success(SynthesizedAudio(cached, cfg.sampleRate, isRawPcm = false))
            }
        }

        val provider = registry.get(cfg.type)
        val attempts = if (input.settings.autoRetryOnFailure) 2 else 1
        var lastError: Throwable? = null

        for (attempt in 1..attempts) {
            currentCoroutineContext().ensureActive()
            val res = provider.synthesize(ProviderSynthesisRequest(text, cfg, creds, sessionId))
            if (res.isSuccess) {
                val audio = res.getOrThrow()
                if (input.settings.isAudioCacheEnabled && audio.bytes.isNotEmpty()) {
                    val toCache = if (audio.isRawPcm) WavCodec.encodeToWav(audio.bytes, audio.sampleRate, 1) else audio.bytes
                    diskCache.put(cfg, text, toCache, input.settings.maxCacheSizeMb)
                }
                return Result.success(audio)
            }
            lastError = res.exceptionOrNull()
            if (attempt < attempts) delay(120L)
        }

        if (input.settings.autoFallbackOnFailure) {
            SynthesisHelpers.findFallbackProvider(cfg, input)?.let { fbCfg ->
                logger.warn("FALLBACK", "主引擎失败，降级至 ${fbCfg.name}", lastError?.message, sessionId = sessionId)
                val fbCreds = input.credentialsLookup(fbCfg.id)
                val fbRes = registry.get(fbCfg.type).synthesize(ProviderSynthesisRequest(text, fbCfg, fbCreds, sessionId))
                if (fbRes.isSuccess) return fbRes
            }
        }
        return Result.failure(lastError ?: IOException("合成失败"))
    }

    private fun pushBatch(
        audio: SynthesizedAudio,
        cfg: TtsProviderConfig,
        settings: GlobalSettings,
        gain: Float,
        targetRate: Int,
        sink: SynthesisSink
    ) {
        val pcm24k = audioPipeline.decodeToStandardPcm(audio.bytes, audio.sampleRate, targetRate).getOrThrow()
        val cap = registry.get(cfg.type).capabilities
        val finalPcm = audioPipeline.applyBatchPostProcessing(pcm24k, cfg, cap, settings, gain)
        SynthesisHelpers.emitInChunks(finalPcm, sink)
    }

    private fun enqueuePrefetch(
        scope: CoroutineScope,
        indices: IntRange,
        segments: List<SentenceSegment>,
        baseCfg: TtsProviderConfig,
        activeCreds: ProviderCredentials,
        input: SynthesisInput,
        sessionId: String,
        cache: ConcurrentHashMap<Int, Deferred<Result<SynthesizedAudio>>>
    ) {
        for (idx in indices) {
            if (idx !in segments.indices || cache.containsKey(idx)) continue
            val seg = segments[idx]
            val segCreds = SynthesisHelpers.rotateCredentials(activeCreds, idx)
            cache[idx] = scope.async { fetchBatch(seg.text, baseCfg, segCreds, input, sessionId) }
        }
    }
}
