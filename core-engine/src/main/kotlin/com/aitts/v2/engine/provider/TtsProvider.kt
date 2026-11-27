package com.aitts.v2.engine.provider

import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel

data class ProviderSynthesisRequest(
    val text: String,
    val config: TtsProviderConfig,
    val credentials: ProviderCredentials = ProviderCredentials(),
    val sessionId: String = ""
)

data class SynthesizedAudio(
    val bytes: ByteArray,
    val sampleRate: Int,
    val isRawPcm: Boolean
)

data class PcmStreamChunk(
    val pcm16Bytes: ByteArray,
    val sampleRate: Int
)

interface TtsProvider {
    val type: ProviderType
    val capabilities: ProviderCapabilities

    suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel>

    suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = listOfNotNull(config.modelName.takeIf { it.isNotBlank() })

    suspend fun discoverCatalog(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): DiscoveryCatalog {
        val models = fetchModels(config, credentials)
        val voices = fetchVoices(config, credentials)
        return DiscoveryCatalog(
            models = models,
            voices = voices,
            stylesByVoice = voices.filter { it.styles.isNotEmpty() }.associate { it.id to it.styles },
            resolvedEndpoint = config.baseUrl,
            fromRemote = false
        )
    }

    suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio>

    suspend fun synthesizeStream(
        request: ProviderSynthesisRequest,
        onChunk: suspend (PcmStreamChunk) -> Unit
    ): Result<SynthesizedAudio> = synthesize(request).onSuccess { audio ->
        if (audio.isRawPcm && audio.bytes.isNotEmpty()) {
            onChunk(PcmStreamChunk(audio.bytes, audio.sampleRate))
        }
    }

    suspend fun testConnection(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): Result<Boolean> {
        val res = synthesize(
            ProviderSynthesisRequest(
                text = "测试语音合成",
                config = config.copy(isStreamingEnabled = false),
                credentials = credentials
            )
        )
        return res.fold(
            onSuccess = { audio ->
                if (audio.bytes.isNotEmpty()) Result.success(true)
                else Result.failure(IllegalStateException("Synthesized audio is empty"))
            },
            onFailure = { Result.failure(it) }
        )
    }
}
