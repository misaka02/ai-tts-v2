package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Microsoft Azure Cognitive Services Speech TTS provider:
 * - Real-time discovery of regional voices and per-voice StyleList (`/cognitiveservices/voices/list`).
 * - SSML `<mstts:express-as style="..." styledegree="...">` rendering when style is configured.
 */
class AzureTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.AZURE
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true,
        supportsVoiceDiscovery = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = fetchVoicesInternal(config, credentials).first

    override suspend fun discoverCatalog(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): DiscoveryCatalog {
        val (voices, fromRemote) = fetchVoicesInternal(config, credentials)
        return DiscoveryCatalog(
            models = listOf("azure-neural", "azure-hd"),
            voices = voices,
            stylesByVoice = voices.filter { it.styles.isNotEmpty() }.associate { it.id to it.styles },
            resolvedEndpoint = config.baseUrl.trim().ifBlank { DEFAULT_SYNTH_URL },
            fromRemote = fromRemote
        )
    }

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        listOf("azure-neural", "azure-hd")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 Azure Speech 订阅密钥"))
            }
            val cfg = request.config
            val url = cfg.baseUrl.trim().ifBlank { DEFAULT_SYNTH_URL }
            val voice = cfg.voiceId.ifBlank { "zh-CN-XiaoxiaoNeural" }
            val ssml = buildSsml(voice, request.text, cfg)
            val outputFmt = if (cfg.audioFormat.contains("wav", ignoreCase = true)) {
                "riff-24khz-16bit-mono-pcm"
            } else {
                "audio-24khz-48kbitrate-mono-mp3"
            }

            val httpReq = Request.Builder()
                .url(url)
                .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                .addHeader("X-Microsoft-OutputFormat", outputFmt)
                .addHeader("User-Agent", "AiTtsEngineV2")
                .build()

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        throw IOException("Azure HTTP ${resp.code}: ${String(bytes, Charsets.UTF_8).take(300)}")
                    }
                    if (bytes.isEmpty()) throw IOException("Azure 返回音频为空")
                    SynthesizedAudio(bytes = bytes, sampleRate = 24000, isRawPcm = false)
                }
            }
        }

    private suspend fun fetchVoicesInternal(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): Pair<List<VoiceModel>, Boolean> = withContext(Dispatchers.IO) {
        val apiKey = credentials.apiKey.trim()
        if (apiKey.isBlank()) return@withContext PresetVoices.edgeVoices to false

        val voicesUrl = resolveVoicesUrl(config.baseUrl)
        val req = Request.Builder()
            .url(voicesUrl)
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .build()

        var fromRemote = false
        val fetched = runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked emptyList()
                val arr = HttpCallSupport.json.decodeFromString<JsonArray>(resp.body?.string().orEmpty())
                arr.mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val shortName = obj["ShortName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val localName = obj["LocalName"]?.jsonPrimitive?.content
                        ?: obj["DisplayName"]?.jsonPrimitive?.content ?: shortName
                    val gender = obj["Gender"]?.jsonPrimitive?.content ?: "Female"
                    val locale = obj["Locale"]?.jsonPrimitive?.content ?: "zh-CN"
                    val styles = (obj["StyleList"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    VoiceModel(
                        id = shortName,
                        name = "$localName ($locale)",
                        gender = gender,
                        locale = locale,
                        description = if (styles.isNotEmpty()) "${styles.size} 种风格" else "$locale · $gender",
                        styles = styles
                    )
                }.sortedByDescending { it.locale.startsWith("zh") }.also { if (it.isNotEmpty()) fromRemote = true }
            }
        }.getOrDefault(emptyList())
        fetched.ifEmpty { PresetVoices.edgeVoices } to fromRemote
    }

    private fun buildSsml(voice: String, text: String, cfg: TtsProviderConfig): String {
        val xmlLang = extractXmlLang(voice)
        val rateStr = AcousticMapper.azureSsmlRate(cfg.speed)
        val pitchStr = AcousticMapper.azureSsmlPitchPct(cfg.pitch)
        val inner = "<prosody rate='$rateStr' pitch='$pitchStr'>${escapeXml(text)}</prosody>"

        val style = cfg.extraParams["style"]?.trim().orEmpty()
        val body = if (style.isNotBlank()) {
            val deg = cfg.extraParams["styleDegree"]?.toFloatOrNull()?.coerceIn(0.01f, 2.0f) ?: 1.0f
            "<mstts:express-as style='${escapeXml(style)}' styledegree='$deg'>$inner</mstts:express-as>"
        } else {
            inner
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='$xmlLang'>" +
            "<voice name='$voice'>$body</voice></speak>"
    }

    private fun resolveVoicesUrl(rawBase: String): String {
        val clean = rawBase.trim().trimEnd('/')
        if (clean.isBlank()) return DEFAULT_VOICES_URL
        return when {
            clean.endsWith("/cognitiveservices/voices/list") -> clean
            clean.contains("/cognitiveservices/v1") -> clean.replace("/cognitiveservices/v1", "/cognitiveservices/voices/list")
            else -> "$clean/cognitiveservices/voices/list"
        }
    }

    private fun extractXmlLang(voiceId: String): String {
        val parts = voiceId.split("-")
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "zh-CN"
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        private const val DEFAULT_SYNTH_URL = "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1"
        private const val DEFAULT_VOICES_URL = "https://eastasia.tts.speech.microsoft.com/cognitiveservices/voices/list"
    }
}
