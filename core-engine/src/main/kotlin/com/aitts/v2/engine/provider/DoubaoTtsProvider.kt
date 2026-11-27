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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * ByteDance Volcano / Doubao TTS provider.
 * Requires explicit `appId` in `extraParams["appId"]` (no hardcoded third-party credentials).
 */
class DoubaoTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.DOUBAO
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.doubaoVoices

    override suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = listOf("volcano_tts", "volcano_bigtts")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val token = request.credentials.apiKey.trim()
            if (token.isBlank()) {
                return@withContext Result.failure(IOException("请先填写火山引擎 AccessToken"))
            }
            val cfg = request.config
            val appId = resolveAppId(cfg)
                ?: return@withContext Result.failure(IOException("请先在豆包配置中填写您的火山引擎 AppID"))
            val cluster = cfg.extraParams["cluster"]?.takeIf { it.isNotBlank() }
                ?: cfg.modelName.ifBlank { "volcano_tts" }
            val url = cfg.baseUrl.trim().ifBlank { "https://openspeech.bytedance.com/api/v1/tts" }

            val payload = buildJsonObject {
                put("app", buildJsonObject {
                    put("appid", appId)
                    put("token", token)
                    put("cluster", cluster)
                })
                put("user", buildJsonObject { put("uid", "ai_tts_v2") })
                val emotion = cfg.extraParams["emotion"]?.trim().orEmpty()
                put("audio", buildJsonObject {
                    put("voice_type", cfg.voiceId.ifBlank { "zh_female_shuangkuaisisi_moon_bigtts" })
                    put("encoding", if (cfg.audioFormat.equals("wav", ignoreCase = true)) "wav" else "mp3")
                    put("speed_ratio", cfg.speed)
                    put("volume_ratio", 1.0)
                    put("pitch_ratio", cfg.pitch)
                    if (emotion.isNotBlank()) put("emotion", emotion)
                })
                put("request", buildJsonObject {
                    put("reqid", UUID.randomUUID().toString())
                    put("text", request.text)
                    put("text_type", "plain")
                    put("operation", "query")
                })
            }.toString()

            val authHeader = if (token.startsWith("Bearer;")) token else "Bearer;$token"
            val httpReq = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", authHeader)
                .build()

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("豆包 HTTP ${resp.code}: ${bodyStr.take(300)}")
                    }
                    val root = HttpCallSupport.json.decodeFromString<JsonObject>(bodyStr)
                    val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
                    if (code != 3000 && code != 0) {
                        val msg = root["message"]?.jsonPrimitive?.content ?: "Error"
                        throw IOException("豆包 API 错误 ($code): $msg")
                    }
                    val b64 = root["data"]?.jsonPrimitive?.content
                        ?: throw IOException("豆包响应中未找到 data 音频字段")
                    SynthesizedAudio(
                        bytes = Base64.getMimeDecoder().decode(b64),
                        sampleRate = cfg.sampleRate,
                        isRawPcm = false
                    )
                }
            }
        }

    private fun resolveAppId(cfg: TtsProviderConfig): String? {
        cfg.extraParams["appId"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (cfg.customHeadersJson.trim().startsWith("{")) {
            val obj = runCatching { HttpCallSupport.json.decodeFromString<JsonObject>(cfg.customHeadersJson) }.getOrNull()
            obj?.get("appid")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            obj?.get("app_id")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
