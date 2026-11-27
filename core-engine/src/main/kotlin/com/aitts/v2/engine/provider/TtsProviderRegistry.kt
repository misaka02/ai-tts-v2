package com.aitts.v2.engine.provider

import com.aitts.v2.engine.manifest.RemoteManifestRepository
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import java.io.IOException

/**
 * Central registry mapping [ProviderType] to concrete [TtsProvider] implementations.
 */
class TtsProviderRegistry(
    httpManager: HttpClientManager,
    offlineProvider: TtsProvider? = null,
    gecVersionProvider: () -> String = { RemoteManifestRepository.DEFAULT_EDGE_GEC }
) {
    private val providers: Map<ProviderType, TtsProvider> = buildMap {
        put(ProviderType.EDGE_TTS, EdgeTtsProvider(httpManager, gecVersionProvider = gecVersionProvider))
        put(ProviderType.MIMO, MimoTtsProvider(httpManager))
        put(ProviderType.GEMINI, GeminiTtsProvider(httpManager))
        put(ProviderType.OPENAI_COMPATIBLE, OpenAiCompatibleProvider(httpManager))
        put(ProviderType.MINIMAX, MiniMaxTtsProvider(httpManager))
        put(ProviderType.DOUBAO, DoubaoTtsProvider(httpManager))
        put(ProviderType.FISH_AUDIO, FishAudioTtsProvider(httpManager))
        put(ProviderType.AZURE, AzureTtsProvider(httpManager))
        put(ProviderType.CUSTOM_HTTP, CustomHttpProvider(httpManager))
        put(ProviderType.OFFLINE_SHERPA, offlineProvider ?: StubOfflineProvider())
    }

    fun get(type: ProviderType): TtsProvider = providers[type] ?: providers.getValue(ProviderType.EDGE_TTS)
}

private class StubOfflineProvider : TtsProvider {
    override val type = ProviderType.OFFLINE_SHERPA
    override val capabilities = ProviderCapabilities()
    override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> = emptyList()
    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        Result.failure(IOException("离线语音后端未注入，请使用 Full 离线完整版"))
}
