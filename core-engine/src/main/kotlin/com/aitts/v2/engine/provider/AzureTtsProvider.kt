package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class AzureTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.AZURE
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.edgeVoices

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        listOf("azure-neural", "azure-hd")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 Azure Speech 订阅密钥"))
            }
            val cfg = request.config
            val url = cfg.baseUrl.trim().ifBlank {
                "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1"
            }
            val voice = cfg.voiceId.ifBlank { "zh-CN-XiaoxiaoNeural" }
            val xmlLang = extractXmlLang(voice)
            val ratePct = ((cfg.speed - 1.0f) * 100).toInt()
            val pitchPct = ((cfg.pitch - 1.0f) * 100).toInt()
            val rateStr = if (ratePct >= 0) "+$ratePct%" else "$ratePct%"
            val pitchStr = if (pitchPct >= 0) "+$pitchPct%" else "$pitchPct%"

            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$xmlLang'>" +
                "<voice name='$voice'><prosody rate='$rateStr' pitch='$pitchStr'>" +
                escapeXml(request.text) + "</prosody></voice></speak>"
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

    private fun extractXmlLang(voiceId: String): String {
        val parts = voiceId.split("-")
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "zh-CN"
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
