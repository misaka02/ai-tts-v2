package com.aitts.v2.engine.provider

import com.aitts.v2.engine.audio.AudioFormatDetector
import com.aitts.v2.engine.audio.PcmFrameAligner
import com.aitts.v2.engine.audio.WavCodec
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.network.SseReader
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/**
 * Google Gemini native TTS provider:
 * - Normalizes base URL without duplicating `/models/models/...`.
 * - Sends API key ONLY via `x-goog-api-key` header (never in URL query string).
 * - Parses actual sample rate from response `inlineData.mimeType` (e.g. `audio/L16;rate=24000`).
 */
class GeminiTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.GEMINI
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = true,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = true,
        supportsDirectorPrompt = true,
        supportsVoiceDiscovery = false,
        supportsModelDiscovery = true
    )

    private val rateRegex = Regex("rate=(\\d+)")

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.geminiVoices

    override suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = withContext(Dispatchers.IO) {
        val fallback = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")
        val apiKey = credentials.apiKey.trim()
        if (apiKey.isBlank()) return@withContext fallback
        val rootBase = normalizeBaseRoot(config.baseUrl)
        val req = Request.Builder()
            .url("$rootBase/models")
            .addHeader("x-goog-api-key", apiKey)
            .build()
        runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked fallback
                val root = HttpCallSupport.json.decodeFromString<JsonObject>(resp.body?.string().orEmpty())
                root["models"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/") }
                    ?.filter { it.contains("tts", ignoreCase = true) || it.contains("2.5-flash", ignoreCase = true) }
                    ?.ifEmpty { fallback }
                    ?: fallback
            }
        }.getOrDefault(fallback)
    }

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 Google Gemini API Key"))
            }
            val cfg = request.config
            val model = cfg.modelName.ifBlank { "gemini-2.5-flash-preview-tts" }
            val url = resolveGenerateUrl(cfg.baseUrl, model, streaming = false)
            val httpReq = buildRequest(url, apiKey, cfg, request.text, acceptSse = false)

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("Gemini HTTP ${resp.code}: ${extractErrorMessage(bodyStr)}")
                    }
                    val root = HttpCallSupport.json.decodeFromString<JsonObject>(bodyStr)
                    root["error"]?.let { throw IOException("Gemini 错误: ${extractErrorMessage(bodyStr)}") }
                    val (mimeType, audioBytes) = extractInlineAudio(root)
                        ?: throw IOException("Gemini 未返回 AUDIO inlineData")
                    val rate = extractSampleRate(mimeType, cfg.sampleRate.coerceAtLeast(24000))
                    val isRawPcm = mimeType.contains("pcm", ignoreCase = true) ||
                        mimeType.contains("L16", ignoreCase = true) ||
                        !AudioFormatDetector.isContainer(audioBytes)
                    if (isRawPcm) {
                        SynthesizedAudio(
                            bytes = WavCodec.encodeToWav(audioBytes, sampleRate = rate, channels = 1),
                            sampleRate = rate,
                            isRawPcm = false
                        )
                    } else {
                        SynthesizedAudio(bytes = audioBytes, sampleRate = rate, isRawPcm = false)
                    }
                }
            }
        }

    override suspend fun synthesizeStream(
        request: ProviderSynthesisRequest,
        onChunk: suspend (PcmStreamChunk) -> Unit
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val apiKey = request.credentials.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IOException("请先填写 Google Gemini API Key"))
        }
        val cfg = request.config
        val model = cfg.modelName.ifBlank { "gemini-2.5-flash-preview-tts" }
        val url = resolveGenerateUrl(cfg.baseUrl, model, streaming = true)
        val httpReq = buildRequest(url, apiKey, cfg, request.text, acceptSse = true)
        val aligner = PcmFrameAligner()
        val collector = ByteArrayOutputStream()
        var detectedRate = cfg.sampleRate.coerceAtLeast(24000)

        runCatching {
            HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                val body = resp.body ?: throw IOException("Gemini 流式响应为空")
                if (!resp.isSuccessful) {
                    throw IOException("Gemini 流式 HTTP ${resp.code}: ${extractErrorMessage(body.string())}")
                }
                val sawSse = SseReader.readDataEvents(body) { payloadJson ->
                    val root = runCatching {
                        HttpCallSupport.json.decodeFromString<JsonObject>(payloadJson)
                    }.getOrNull() ?: return@readDataEvents
                    val (mimeType, audioBytes) = extractInlineAudio(root) ?: return@readDataEvents
                    detectedRate = extractSampleRate(mimeType, detectedRate)
                    val aligned = aligner.push(audioBytes)
                    if (aligned.isNotEmpty()) {
                        collector.write(aligned)
                        onChunk(PcmStreamChunk(aligned, detectedRate))
                    }
                }
                if (!sawSse) throw IOException("Gemini 未返回有效 SSE 流")
            }
            val full = collector.toByteArray()
            if (full.isEmpty()) throw IOException("Gemini 流式未收到音频分块")
            SynthesizedAudio(bytes = full, sampleRate = detectedRate, isRawPcm = true)
        }
    }

    internal fun resolveGenerateUrl(rawBase: String, model: String, streaming: Boolean): String {
        val root = normalizeBaseRoot(rawBase)
        val trimmed = rawBase.trim().trimEnd('/')
        val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return when {
            trimmed.contains(":generateContent") ->
                if (streaming) trimmed.replace(":generateContent", ":streamGenerateContent") + "&alt=sse" else trimmed
            trimmed.contains(":streamGenerateContent") ->
                if (streaming) (if (trimmed.contains("alt=sse")) trimmed else "$trimmed?alt=sse")
                else trimmed.replace(":streamGenerateContent", ":generateContent")
            else -> "$root/models/$model:$action"
        }
    }

    private fun normalizeBaseRoot(rawBase: String): String {
        var root = rawBase.trim().trimEnd('/')
        if (root.isBlank()) return "https://generativelanguage.googleapis.com/v1beta"
        if (root.endsWith("/models")) root = root.removeSuffix("/models")
        return root
    }

    private fun buildRequest(
        url: String,
        apiKey: String,
        cfg: TtsProviderConfig,
        text: String,
        acceptSse: Boolean
    ): Request {
        val prompt = buildPrompt(cfg)
        val combinedText = if (prompt.isNotBlank()) "$prompt\n\n$text" else text
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", combinedText) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", cfg.voiceId.ifBlank { "Puck" })
                        })
                    })
                })
            })
        }.toString()

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (acceptSse) "text/event-stream" else "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .build()
    }

    private fun extractInlineAudio(root: JsonObject): Pair<String, ByteArray>? {
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
        for (part in parts) {
            val obj = part.jsonObject
            val inline = obj["inlineData"]?.jsonObject ?: obj["inline_data"]?.jsonObject ?: continue
            val mime = inline["mimeType"]?.jsonPrimitive?.content
                ?: inline["mime_type"]?.jsonPrimitive?.content ?: ""
            val b64 = inline["data"]?.jsonPrimitive?.content ?: ""
            if (b64.isNotBlank()) {
                return mime to Base64.getMimeDecoder().decode(b64)
            }
        }
        return null
    }

    private fun extractSampleRate(mimeType: String, fallback: Int): Int {
        return rateRegex.find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    private fun extractErrorMessage(bodyStr: String): String = runCatching {
        HttpCallSupport.json.decodeFromString<JsonObject>(bodyStr)["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: bodyStr.take(300)

    private fun buildPrompt(cfg: TtsProviderConfig): String {
        val parts = mutableListOf<String>()
        cfg.promptInstruction.trim().takeIf { it.isNotBlank() }?.let { parts.add(it) }
        parts.add(
            when {
                cfg.speed <= 0.65f -> "Speak at an extremely slow, calm pace."
                cfg.speed <= 0.85f -> "Speak at a slower, measured pace."
                cfg.speed <= 1.10f -> "Speak at a natural, fluent pace."
                cfg.speed <= 1.35f -> "Speak at a brisk, lively pace."
                else -> "Speak at a rapid, agile pace."
            }
        )
        if (cfg.pitch <= 0.85f) parts.add("Use a lower, deeper tone.")
        else if (cfg.pitch >= 1.15f) parts.add("Use a higher, brighter tone.")
        return "Instruction: ${parts.joinToString(" ")}"
    }
}
