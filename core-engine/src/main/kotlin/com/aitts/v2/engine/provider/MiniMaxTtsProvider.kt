package com.aitts.v2.engine.provider

import com.aitts.v2.engine.audio.PcmFrameAligner
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

class MiniMaxTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.MINIMAX
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = true,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.minimaxVoices

    override suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = listOf("speech-02-turbo", "speech-02", "speech-01-turbo", "speech-01-hd")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 MiniMax API Key"))
            }
            val cfg = request.config
            val format = if (cfg.audioFormat.equals("wav", ignoreCase = true)) "wav" else "mp3"
            val sampleRate = if (cfg.sampleRate > 0) cfg.sampleRate else 32000
            val httpReq = buildRequest(cfg, apiKey, request.text, stream = false, format = format, sampleRate = sampleRate)

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val bodyStr = String(bytes, Charsets.UTF_8)
                    if (!resp.isSuccessful) {
                        throw IOException("MiniMax HTTP ${resp.code}: ${bodyStr.take(300)}")
                    }
                    val root = HttpCallSupport.json.decodeFromString<JsonObject>(bodyStr)
                    val baseResp = root["base_resp"]?.jsonObject
                    val code = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0
                    if (code != 0) {
                        val msg = baseResp?.get("status_msg")?.jsonPrimitive?.content ?: "Error"
                        throw IOException("MiniMax 错误 ($code): $msg")
                    }
                    val audioStr = root["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content
                        ?: root["audio"]?.jsonPrimitive?.content
                        ?: throw IOException("MiniMax 响应中未找到音频载荷")
                    SynthesizedAudio(decodeAudioStr(audioStr), sampleRate = sampleRate, isRawPcm = false)
                }
            }
        }

    override suspend fun synthesizeStream(
        request: ProviderSynthesisRequest,
        onChunk: suspend (PcmStreamChunk) -> Unit
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val apiKey = request.credentials.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IOException("请先填写 MiniMax API Key"))
        }
        val cfg = request.config
        val sampleRate = if (cfg.sampleRate > 0) cfg.sampleRate else 32000
        val httpReq = buildRequest(cfg, apiKey, request.text, stream = true, format = "pcm", sampleRate = sampleRate)
        val aligner = PcmFrameAligner()
        val collector = ByteArrayOutputStream()

        runCatching {
            HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                val body = resp.body ?: throw IOException("MiniMax 流式响应为空")
                if (!resp.isSuccessful) {
                    throw IOException("MiniMax 流式 HTTP ${resp.code}: ${body.string().take(300)}")
                }
                val sawSse = SseReader.readDataEvents(body) { payloadJson ->
                    val root = runCatching {
                        HttpCallSupport.json.decodeFromString<JsonObject>(payloadJson)
                    }.getOrNull() ?: return@readDataEvents
                    val audioStr = root["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content
                        ?: root["audio"]?.jsonPrimitive?.content
                        ?: return@readDataEvents
                    val raw = decodeAudioStr(audioStr)
                    val aligned = aligner.push(raw)
                    if (aligned.isNotEmpty()) {
                        collector.write(aligned)
                        onChunk(PcmStreamChunk(aligned, sampleRate))
                    }
                }
                if (!sawSse) throw IOException("MiniMax 未返回有效 SSE 流")
            }
            val full = collector.toByteArray()
            if (full.isEmpty()) throw IOException("MiniMax 流式未收到音频分块")
            SynthesizedAudio(bytes = full, sampleRate = sampleRate, isRawPcm = true)
        }
    }

    private fun buildRequest(
        cfg: TtsProviderConfig,
        apiKey: String,
        text: String,
        stream: Boolean,
        format: String,
        sampleRate: Int
    ): Request {
        val url = cfg.baseUrl.trim().ifBlank { "https://api.minimax.chat/v1/t2a_v2" }
        val pitchVal = ((cfg.pitch - 1.0f) * 12).toInt().coerceIn(-12, 12)
        val payload = buildJsonObject {
            put("model", cfg.modelName.ifBlank { "speech-02-turbo" })
            put("text", text)
            put("stream", stream)
            put("voice_setting", buildJsonObject {
                put("voice_id", cfg.voiceId.ifBlank { "male-qn-qingse" })
                put("speed", cfg.speed)
                put("vol", 1.0)
                put("pitch", pitchVal)
            })
            put("audio_setting", buildJsonObject {
                put("sample_rate", sampleRate)
                put("bitrate", 128000)
                put("format", format)
            })
        }.toString()

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
    }

    private fun decodeAudioStr(raw: String): ByteArray {
        val trimmed = raw.trim()
        val isHex = trimmed.length % 2 == 0 && trimmed.take(64).all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
        return if (isHex) {
            ByteArray(trimmed.length / 2) { idx ->
                ((Character.digit(trimmed[idx * 2], 16) shl 4) +
                    Character.digit(trimmed[idx * 2 + 1], 16)).toByte()
            }
        } else {
            Base64.getMimeDecoder().decode(trimmed)
        }
    }
}
