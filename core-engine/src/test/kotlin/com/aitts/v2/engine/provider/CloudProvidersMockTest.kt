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

    @Test
    fun `azure discovers styles from voices list and emits express-as ssml`() = runTest {
        val voicesJson = """
            [
              {
                "ShortName": "zh-CN-XiaoxiaoNeural",
                "LocalName": "晓晓",
                "Gender": "Female",
                "Locale": "zh-CN",
                "StyleList": ["cheerful", "sad"]
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(voicesJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody("mp3-bytes"))

        val provider = AzureTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "az",
            type = ProviderType.AZURE,
            name = "Azure",
            baseUrl = server.url("/cognitiveservices/v1").toString(),
            voiceId = "zh-CN-XiaoxiaoNeural",
            extraParams = mapOf("style" to "cheerful", "styleDegree" to "1.5")
        )
        val creds = ProviderCredentials("azure-key")
        val catalog = provider.discoverCatalog(cfg, creds)
        assertTrue(catalog.fromRemote)
        assertEquals(listOf("cheerful", "sad"), catalog.stylesByVoice["zh-CN-XiaoxiaoNeural"])

        val res = provider.synthesize(ProviderSynthesisRequest("你好", cfg, creds))
        assertTrue(res.isSuccess)
        val listReq = server.takeRequest()
        assertEquals("/cognitiveservices/voices/list", listReq.path)
        val synthReq = server.takeRequest()
        val ssmlBody = synthReq.body.readUtf8()
        assertTrue(ssmlBody.contains("<mstts:express-as style='cheerful' styledegree='1.5'>"))
    }

    @Test
    fun `minimax posts get_voice catalog and sends emotion inside voice_setting`() = runTest {
        val getVoiceJson = """
            {
              "system_voice": [{"voice_id": "male-qn-qingse", "voice_name": "青涩青年"}],
              "voice_cloning": [{"voice_id": "clone-1", "voice_name": "自建"}]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(getVoiceJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"base_resp":{"status_code":0},"data":{"audio":"01020304"}}"""))

        val provider = MiniMaxTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "mm",
            type = ProviderType.MINIMAX,
            name = "MiniMax",
            baseUrl = server.url("/v1/t2a_v2").toString(),
            voiceId = "clone-1",
            extraParams = mapOf("emotion" to "happy")
        )
        val creds = ProviderCredentials("mm-key")
        val catalog = provider.discoverCatalog(cfg, creds)
        assertTrue(catalog.fromRemote)
        assertEquals(2, catalog.voices.size)
        assertEquals("clone-1", catalog.voices.first().id)

        val res = provider.synthesize(ProviderSynthesisRequest("测试", cfg, creds))
        assertTrue(res.isSuccess)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), res.getOrThrow().bytes)
        val discoveryReq = server.takeRequest()
        assertEquals("POST", discoveryReq.method)
        assertEquals("/v1/get_voice", discoveryReq.path)
        val synthReq = server.takeRequest()
        assertTrue(synthReq.body.readUtf8().contains("\"emotion\":\"happy\""))
    }

    @Test
    fun `openai compatible discovers models and voices and sends instructions`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"gpt-4o-mini-tts"},{"id":"gpt-4o"}]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"result":[{"uri":"v1","customName":"Alex"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("mp3-data"))

        val provider = OpenAiCompatibleProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "oai",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "OpenAI",
            baseUrl = server.url("/v1/audio/speech").toString(),
            modelName = "gpt-4o-mini-tts",
            promptInstruction = "用温柔沉稳的旁白语气"
        )
        val creds = ProviderCredentials("oai-key")
        val catalog = provider.discoverCatalog(cfg, creds)
        assertTrue(catalog.fromRemote)
        assertEquals(listOf("gpt-4o-mini-tts"), catalog.models)
        assertEquals("v1", catalog.voices.first().id)

        val res = provider.synthesize(ProviderSynthesisRequest("旁白测试", cfg, creds))
        assertTrue(res.isSuccess)
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/v1/audio/voice/list", server.takeRequest().path)
        val synthBody = server.takeRequest().body.readUtf8()
        assertTrue(synthBody.contains("\"instructions\":\"用温柔沉稳的旁白语气\""))
    }

    @Test
    fun `fish audio resolves dynamic non-hardcoded host for voice discovery`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"items":[{"_id":"fish-custom-1","title":"解说音色"}]}""")
        )

        val provider = FishAudioTtsProvider(httpManager)
        val cfg = TtsProviderConfig(
            id = "fish",
            type = ProviderType.FISH_AUDIO,
            name = "Fish",
            baseUrl = server.url("/v1/tts").toString()
        )
        val catalog = provider.discoverCatalog(cfg, ProviderCredentials("fish-key"))
        assertTrue(catalog.fromRemote)
        assertEquals(listOf("fish-custom-1"), catalog.voices.map { it.id })
        val req = server.takeRequest()
        assertEquals("/model?page_size=30", req.path)
    }

    @Test
    fun `acoustic boundaries clamp minimax, azure, doubao bigtts, gemini, mimo, and openai tts-1`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"base_resp":{"status_code":0},"data":{"audio":"0102"}}"""))
        val mmCfg = TtsProviderConfig(
            id = "mm",
            type = ProviderType.MINIMAX,
            name = "MM",
            baseUrl = server.url("/v1/t2a_v2").toString(),
            speed = 2.8f,
            pitch = 0.5f
        )
        assertTrue(MiniMaxTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("a", mmCfg, ProviderCredentials("k"))).isSuccess)
        val mmReq = server.takeRequest().body.readUtf8()
        assertTrue(mmReq.contains("\"speed\":2.0"))
        assertTrue(mmReq.contains("\"pitch\":-12"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("mp3"))
        val azCfg = TtsProviderConfig(
            id = "az",
            type = ProviderType.AZURE,
            name = "Az",
            baseUrl = server.url("/cognitiveservices/v1").toString(),
            speed = 0.25f,
            pitch = 2.0f
        )
        assertTrue(AzureTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("a", azCfg, ProviderCredentials("k"))).isSuccess)
        val azReq = server.takeRequest().body.readUtf8()
        assertTrue(azReq.contains("rate='-50%'"))
        assertTrue(azReq.contains("pitch='+50%'"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":3000,"data":"AQI="}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":3000,"data":"AQI="}"""))
        val dbBigCfg = TtsProviderConfig(
            id = "db1",
            type = ProviderType.DOUBAO,
            name = "DB",
            baseUrl = server.url("/api/v1/tts").toString(),
            voiceId = "zh_female_shuangkuaisisi_moon_bigtts",
            speed = 2.5f,
            pitch = 1.9f,
            extraParams = mapOf("appId" to "12345")
        )
        assertTrue(DoubaoTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("a", dbBigCfg, ProviderCredentials("k"))).isSuccess)
        val dbBigReq = server.takeRequest().body.readUtf8()
        assertTrue(dbBigReq.contains("\"speed_ratio\":2.0"))
        assertFalse(dbBigReq.contains("\"pitch_ratio\""))

        val dbStdCfg = dbBigCfg.copy(voiceId = "BV001_streaming", speed = 1.2f, pitch = 1.3f)
        assertTrue(DoubaoTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("a", dbStdCfg, ProviderCredentials("k"))).isSuccess)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"pitch_ratio\":1.3"))

        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"$pcm"}}]}}]}""")
        )
        val gemCfg = TtsProviderConfig(
            id = "g",
            type = ProviderType.GEMINI,
            name = "G",
            baseUrl = server.url("/v1beta").toString(),
            speed = 1.8f,
            pitch = 1.5f,
            promptInstruction = ""
        )
        assertTrue(GeminiTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("原文", gemCfg, ProviderCredentials("k"))).isSuccess)
        val gemReq = server.takeRequest().body.readUtf8()
        assertFalse(gemReq.contains("rapid"))
        assertFalse(gemReq.contains("higher, brighter"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"audio":{"data":"AQI="}}}]}"""))
        val mimoCfg = TtsProviderConfig(
            id = "m",
            type = ProviderType.MIMO,
            name = "M",
            baseUrl = server.url("/v1/chat/completions").toString(),
            speed = 1.8f,
            promptInstruction = "温柔朗读"
        )
        assertTrue(MimoTtsProvider(httpManager).synthesize(ProviderSynthesisRequest("测试", mimoCfg, ProviderCredentials("k"))).isSuccess)
        val mimoReq = server.takeRequest().body.readUtf8()
        assertFalse(mimoReq.contains("\"speed\""))
        assertTrue(mimoReq.contains("温柔朗读"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("mp3"))
        val oaiCfg = TtsProviderConfig(
            id = "o",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "O",
            baseUrl = server.url("/v1/audio/speech").toString(),
            modelName = "tts-1",
            promptInstruction = "should-ignore"
        )
        assertTrue(OpenAiCompatibleProvider(httpManager).synthesize(ProviderSynthesisRequest("hi", oaiCfg, ProviderCredentials("k"))).isSuccess)
        assertFalse(server.takeRequest().body.readUtf8().contains("\"instructions\""))
    }
}
