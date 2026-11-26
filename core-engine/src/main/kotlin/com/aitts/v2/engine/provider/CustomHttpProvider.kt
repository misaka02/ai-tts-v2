package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * Generic configurable HTTP template provider for self-hosted nodes (GPT-SoVITS, CosyVoice, F5-TTS, etc.).
 */
class CustomHttpProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.CUSTOM_HTTP
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = listOf(
        VoiceModel(
            id = config.voiceId.ifBlank { "default" },
            name = "自定义音色 (${config.voiceId.ifBlank { "default" }})",
            gender = "Neutral",
            locale = "zh-CN"
        )
    )

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val cfg = request.config
            if (cfg.baseUrl.isBlank()) {
                return@withContext Result.failure(IOException("自定义 HTTP 服务 URL 不能为空"))
            }
            val apiKey = request.credentials.apiKey.trim()
            val url = applyTemplate(cfg.baseUrl, request.text, cfg, apiKey, urlEncode = true)
            val bodyTemplate = cfg.customPayloadTemplate.trim()
            val payload = applyTemplate(bodyTemplate, request.text, cfg, apiKey, urlEncode = false)

            val builder = Request.Builder().url(url)
            var hasAuthHeader = false

            if (cfg.customHeadersJson.isNotBlank() && cfg.customHeadersJson.trim().startsWith("{")) {
                runCatching {
                    val hdrs = HttpCallSupport.json.decodeFromString<JsonObject>(cfg.customHeadersJson)
                    for ((k, v) in hdrs) {
                        if (k.equals("Authorization", ignoreCase = true)) hasAuthHeader = true
                        builder.addHeader(k, applyTemplate(v.jsonPrimitive.content, request.text, cfg, apiKey, urlEncode = false))
                    }
                }
            }
            if (apiKey.isNotBlank() && !hasAuthHeader) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }

            if (payload.startsWith("{") || payload.startsWith("[")) {
                builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                builder.addHeader("Content-Type", "application/json")
            } else {
                builder.get()
            }

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, builder.build()) { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val contentType = resp.header("Content-Type").orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("自定义节点 HTTP ${resp.code}: ${String(bytes, Charsets.UTF_8).take(300)}")
                    }
                    if (bytes.isEmpty()) throw IOException("自定义节点返回空数据")

                    val isJsonPayload = cfg.responseAudioPath.isNotBlank() ||
                        contentType.contains("application/json", ignoreCase = true) ||
                        bytes.firstOrNull()?.toInt()?.toChar() == '{'

                    if (isJsonPayload) {
                        val root = HttpCallSupport.json.decodeFromString<JsonObject>(String(bytes, Charsets.UTF_8))
                        val b64 = extractJsonAudio(root, cfg.responseAudioPath)
                            ?: throw IOException("未能从自定义 JSON 响应中提取音频字段")
                        val cleaned = if (b64.contains(",")) b64.substringAfter(",") else b64
                        SynthesizedAudio(
                            bytes = Base64.getMimeDecoder().decode(cleaned),
                            sampleRate = cfg.sampleRate,
                            isRawPcm = false
                        )
                    } else {
                        SynthesizedAudio(bytes = bytes, sampleRate = cfg.sampleRate, isRawPcm = false)
                    }
                }
            }
        }

    private fun extractJsonAudio(root: JsonObject, configuredPath: String): String? {
        if (configuredPath.isNotBlank()) {
            return resolveDotPath(root, configuredPath.split("."))
        }
        for (candidate in listOf("audio", "audio_base64", "data", "data.audio_base64", "data.audio")) {
            resolveDotPath(root, candidate.split("."))?.let { return it }
        }
        return null
    }

    private fun resolveDotPath(root: JsonObject, parts: List<String>): String? {
        var current: JsonElement = root
        for (part in parts) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return (current as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private fun applyTemplate(
        template: String,
        text: String,
        cfg: TtsProviderConfig,
        apiKey: String,
        urlEncode: Boolean
    ): String {
        fun encodeVal(v: String): String =
            if (urlEncode) URLEncoder.encode(v, "UTF-8") else escapeJson(v)

        return template
            .replace("\${text}", encodeVal(text))
            .replace("\${prompt}", encodeVal(cfg.promptInstruction))
            .replace("\${speed}", cfg.speed.toString())
            .replace("\${pitch}", cfg.pitch.toString())
            .replace("\${volume}", cfg.volume.toString())
            .replace("\${model}", encodeVal(cfg.modelName))
            .replace("\${voice}", encodeVal(cfg.voiceId))
            .replace("\${sample_rate}", cfg.sampleRate.toString())
            .replace("\${format}", encodeVal(cfg.audioFormat))
            .replace("\${apiKey}", encodeVal(apiKey))
    }

    private fun escapeJson(str: String): String = str
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
