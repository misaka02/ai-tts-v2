package com.aitts.v2.offline

import android.content.Context
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.engine.provider.SynthesizedAudio
import com.aitts.v2.engine.provider.TtsProvider
import com.aitts.v2.model.OfflineCatalogData
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import java.io.IOException

object PlatformOfflineBackend : OfflineTtsBackendFactory {
    override fun isNativeRuntimeBundled(): Boolean = false

    override fun createProvider(context: Context, installer: OfflineModelInstaller): TtsProvider {
        return CloudOnlyOfflineProvider()
    }
}

private class CloudOnlyOfflineProvider : TtsProvider {
    override val type = ProviderType.OFFLINE_SHERPA
    override val capabilities = ProviderCapabilities()

    override suspend fun fetchVoices(config: TtsProviderConfig, credentials: ProviderCredentials): List<VoiceModel> {
        val spec = OfflineCatalogData.findById(config.modelName) ?: OfflineCatalogData.models.first()
        return spec.speakers.mapIndexed { idx, label -> VoiceModel("spk_$idx", label) }
    }

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        OfflineCatalogData.models.map { it.id }

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        Result.failure(IOException("当前为轻巧云端版 (Cloud APK)，未内置 Sherpa-ONNX 推理引擎，请安装 Full ARM64 完整版以使用本地离线模型。"))
}
