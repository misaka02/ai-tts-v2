package com.aitts.v2.engine.synthesis

import com.aitts.v2.engine.audio.AudioPipeline
import com.aitts.v2.engine.audio.DecodedPcm
import com.aitts.v2.engine.cache.AudioDiskCache
import com.aitts.v2.engine.logging.EngineLogger
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.provider.PcmStreamChunk
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.engine.provider.SynthesizedAudio
import com.aitts.v2.engine.provider.TtsProvider
import com.aitts.v2.engine.provider.TtsProviderRegistry
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SynthesisOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cancelling session A does not cancel concurrent session B`() = runTest {
        val http = HttpClientManager()
        val logger = EngineLogger()
        val cache = AudioDiskCache(tempFolder.newFolder("cache1"))
        val audioPipeline = AudioPipeline { bytes, rate -> Result.success(DecodedPcm(bytes, rate, 1)) }

        val slowProvider = object : TtsProvider {
            override val type = ProviderType.OFFLINE_SHERPA
            override val capabilities = ProviderCapabilities()
            override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> = emptyList()
            override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> {
                if (request.sessionId == "sessA") delay(5_000) else delay(50)
                return Result.success(SynthesizedAudio(byteArrayOf(1, 2, 3, 4), 24000, isRawPcm = true))
            }
        }
        val registry = TtsProviderRegistry(http, offlineProvider = slowProvider)
        val orchestrator = SynthesisOrchestrator(registry, audioPipeline, cache, http, logger)

        val cfg = TtsProviderConfig(id = "off", type = ProviderType.OFFLINE_SHERPA, name = "Offline")
        val input = SynthesisInput(
            rawText = "测试一句",
            settings = GlobalSettings(isSmartSegmentationEnabled = false, isAudioCacheEnabled = false),
            activeConfig = cfg,
            allProviders = listOf(cfg),
            rules = emptyList(),
            credentialsLookup = { ProviderCredentials() }
        )

        var bDone = false
        var aCancelled = false
        val jobA = async {
            try {
                orchestrator.execute("sessA", input, recordingSink {})
            } catch (_: CancellationException) {
                aCancelled = true
            }
        }
        val jobB = async {
            orchestrator.execute("sessB", input, recordingSink { bDone = true })
        }

        delay(20)
        orchestrator.cancelSession("sessA")
        jobA.await()
        jobB.await()

        assertTrue(aCancelled)
        assertTrue(bDone)
    }

    @Test
    fun `streaming failure before first byte falls back cleanly and honors autoRetryOnFailure`() = runTest {
        val http = HttpClientManager()
        val logger = EngineLogger()
        val cache = AudioDiskCache(tempFolder.newFolder("cache2"))
        val audioPipeline = AudioPipeline { bytes, rate -> Result.success(DecodedPcm(bytes, rate, 1)) }
        val batchCalls = AtomicInteger(0)

        val flakyStreamProvider = object : TtsProvider {
            override val type = ProviderType.OFFLINE_SHERPA
            override val capabilities = ProviderCapabilities(supportsNativePcmStreaming = true)
            override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> = emptyList()
            override suspend fun synthesizeStream(
                request: ProviderSynthesisRequest,
                onChunk: suspend (PcmStreamChunk) -> Unit
            ): Result<SynthesizedAudio> = Result.failure(IOException("stream connect failed"))

            override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> {
                batchCalls.incrementAndGet()
                return Result.success(SynthesizedAudio(byteArrayOf(5, 6, 7, 8), 24000, isRawPcm = true))
            }
        }

        val registry = TtsProviderRegistry(http, offlineProvider = flakyStreamProvider)
        val orchestrator = SynthesisOrchestrator(registry, audioPipeline, cache, http, logger)
        val cfg = TtsProviderConfig(id = "off", type = ProviderType.OFFLINE_SHERPA, name = "Offline", isStreamingEnabled = true)

        var done = false
        var errorCalled = false
        orchestrator.execute(
            "sess1",
            SynthesisInput(
                rawText = "回退测试",
                settings = GlobalSettings(isSmartSegmentationEnabled = false, autoRetryOnFailure = false, isAudioCacheEnabled = false),
                activeConfig = cfg,
                allProviders = listOf(cfg),
                rules = emptyList(),
                credentialsLookup = { ProviderCredentials() }
            ),
            object : SynthesisSink {
                override fun onStart(sampleRate: Int) = true
                override fun onPcmChunk(pcm16Bytes: ByteArray) {}
                override fun onDone() { done = true }
                override fun onError(error: Throwable) { errorCalled = true }
            }
        )

        assertTrue(done)
        assertFalse(errorCalled)
        assertEquals(1, batchCalls.get())
    }

    @Test
    fun `streaming synthesis completes and logs trace metrics`() = runTest {
        val http = HttpClientManager()
        val logger = EngineLogger()
        val cache = AudioDiskCache(tempFolder.newFolder("cache3"))
        val audioPipeline = AudioPipeline { bytes, rate -> Result.success(DecodedPcm(bytes, rate, 1)) }

        val streamProvider = object : TtsProvider {
            override val type = ProviderType.OFFLINE_SHERPA
            override val capabilities = ProviderCapabilities(supportsNativePcmStreaming = true)
            override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> = emptyList()
            override suspend fun synthesizeStream(
                request: ProviderSynthesisRequest,
                onChunk: suspend (PcmStreamChunk) -> Unit
            ): Result<SynthesizedAudio> {
                val pcm = ByteArray(1024)
                onChunk(PcmStreamChunk(pcm, 24000))
                return Result.success(SynthesizedAudio(pcm, 24000, isRawPcm = true))
            }
            override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
                Result.failure(IOException("unused"))
        }

        val registry = TtsProviderRegistry(http, offlineProvider = streamProvider)
        val orchestrator = SynthesisOrchestrator(registry, audioPipeline, cache, http, logger)
        val cfg = TtsProviderConfig(
            id = "stream_1",
            type = ProviderType.OFFLINE_SHERPA,
            name = "StreamEngine",
            voiceId = "v1",
            isStreamingEnabled = true
        )

        var done = false
        orchestrator.execute(
            "sess_stream",
            SynthesisInput(
                rawText = "流式播报验证",
                settings = GlobalSettings(isSmartSegmentationEnabled = false, isAudioCacheEnabled = false),
                activeConfig = cfg,
                allProviders = listOf(cfg),
                rules = emptyList(),
                credentialsLookup = { ProviderCredentials() }
            ),
            recordingSink { done = true }
        )

        assertTrue(done)
        assertTrue(logger.entriesFlow.value.any { it.sessionId == "sess_stream" && it.title.contains("合成播音完成") })
    }

    private fun recordingSink(onComplete: () -> Unit) = object : SynthesisSink {
        override fun onStart(sampleRate: Int) = true
        override fun onPcmChunk(pcm16Bytes: ByteArray) {}
        override fun onDone() { onComplete() }
        override fun onError(error: Throwable) {}
    }
}
