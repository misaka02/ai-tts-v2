package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class CloudProvidersMockTest {

    private lateinit var server: MockWebServer
    private lateinit var httpManager: HttpClientManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpManager = HttpClientManager()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `gemini provider normalizes models suffix, uses header auth, and parses rate`() = runTest {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=16000","data":"$b64"}}]}}]}""")
        )

        val provider = GeminiTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "gem",
            type = ProviderType.GEMINI,
            name = "Gemini",
            baseUrl = server.url("/v1beta/models").toString(),
            modelName = "gemini-2.5-flash-preview-tts",
            voiceId = "Puck"
        )
        val res = provider.synthesize(ProviderSynthesisRequest("你好", cfg, ProviderCredentials("secret-key")))
        assertTrue(res.isSuccess)
        val audio = res.getOrThrow()
        assertEquals(16000, audio.sampleRate)
        assertEquals('R'.code.toByte(), audio.bytes[0]) // Wrapped as WAV

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", recorded.path)
        assertEquals("secret-key", recorded.getHeader("x-goog-api-key"))
        assertFalse(recorded.path!!.contains("secret-key"))
        assertFalse(recorded.path!!.contains("/models/models/"))
    }

    @Test
    fun `openai compatible streaming aligns odd-byte PCM chunks`() = runTest {
        val rawPcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        val buf = Buffer().write(rawPcm)
        server.enqueue(MockResponse().setResponseCode(200).setBody(buf).setBodyDelay(0, java.util.concurrent.TimeUnit.MILLISECONDS))

        val provider = OpenAiCompatibleProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "oai",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "OAI",
            baseUrl = server.url("/v1/audio/speech").toString(),
            isStreamingEnabled = true
        )
        val streamed = mutableListOf<Byte>()
        val res = provider.synthesizeStream(ProviderSynthesisRequest("test", cfg, ProviderCredentials("k"))) { chunk ->
            assertEquals(0, chunk.pcm16Bytes.size % 2)
            chunk.pcm16Bytes.forEach { streamed.add(it) }
        }
        assertTrue(res.isSuccess)
        assertArrayEquals(rawPcm, streamed.toByteArray())
    }

    @Test
    fun `doubao rejects missing appId instead of using hardcoded third-party id`() = runTest {
        val provider = DoubaoTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "doubao",
            type = ProviderType.DOUBAO,
            name = "Doubao",
            baseUrl = server.url("/api/v1/tts").toString()
        )
        val res = provider.synthesize(ProviderSynthesisRequest("hello", cfg, ProviderCredentials("token")))
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("AppID") == true)
    }

    @Test
    fun `mimo streaming emits SSE PCM chunks without sending conflicting speed field`() = runTest {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val sseBody = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"$b64\"}}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))

        val provider = MimoTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "mimo",
            type = ProviderType.MIMO,
            name = "MiMo",
            baseUrl = server.url("/v1/chat/completions").toString(),
            speed = 1.6f,
            isStreamingEnabled = true
        )
        val chunks = mutableListOf<ByteArray>()
        val res = provider.synthesizeStream(ProviderSynthesisRequest("hello", cfg, ProviderCredentials("k"))) {
            chunks.add(it.pcm16Bytes)
        }
        assertTrue(res.isSuccess)
        assertArrayEquals(pcm, chunks.single())

        val reqBody = server.takeRequest().body.readUtf8()
        assertFalse(reqBody.contains("\"speed\""))
    }

    @Test
    fun `custom http extracts alternative json base64 keys cleanly`() = runTest {
        val audioBytes = byteArrayOf(9, 8, 7, 6)
        val b64 = Base64.getEncoder().encodeToString(audioBytes)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"audio_base64":"$b64"}"""))

        val provider = CustomHttpProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "custom",
            type = ProviderType.CUSTOM_HTTP,
            name = "Custom",
            baseUrl = server.url("/tts").toString()
        )
        val res = provider.synthesize(ProviderSynthesisRequest("你好\"世界\"", cfg))
        assertTrue(res.isSuccess)
        assertArrayEquals(audioBytes, res.getOrThrow().bytes)
    }
}
