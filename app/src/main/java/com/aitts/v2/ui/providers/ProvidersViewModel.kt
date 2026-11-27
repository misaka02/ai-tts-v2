package com.aitts.v2.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.audio.AudioEnhancer
import com.aitts.v2.engine.audio.SonicSpeedScaler
import com.aitts.v2.engine.provider.DiscoveryCatalog
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.model.OfflineModelSpec
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.ProviderTemplate
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ProviderAction { SET_ACTIVE, PIN_TOP, MOVE_UP, MOVE_DOWN, DUPLICATE, DELETE }

data class ProvidersUiState(
    val latenciesMs: Map<String, Long> = emptyMap(),
    val isBatchTesting: Boolean = false,
    val downloadingModelId: String? = null,
    val downloadProgress: Int = 0,
    val downloadMessage: String = "",
    val downloadedModelIds: Set<String> = emptySet(),
    val availableVoices: List<VoiceModel> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val stylesByVoice: Map<String, List<String>> = emptyMap(),
    val discoveryFromRemote: Boolean = false,
    val isLoadingVoices: Boolean = false,
    val isTestingSingle: Boolean = false,
    val message: String? = null
)

class ProvidersViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    val providersFlow = container.preferences.providersFlow
    val settingsFlow = container.preferences.settingsFlow
    val offlineIncluded = container.offlineIncluded

    val offlineCatalogFlow: StateFlow<List<OfflineModelSpec>> = container.manifestRepository.manifestFlow
        .map { mergedOfflineCatalog() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), mergedOfflineCatalog())

    private var singleTestJob: Job? = null

    init {
        refreshInstalledOfflineModels()
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun mergedOfflineCatalog(): List<OfflineModelSpec> {
        val remote = container.manifestRepository.offlineModels()
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val localExtras = container.modelInstaller.listInstalledSpecs().filter { it.id !in remoteIds }
        return remote + localExtras
    }

    fun refreshInstalledOfflineModels() {
        val installed = container.modelInstaller.listInstalledIds()
        _uiState.update { it.copy(downloadedModelIds = installed) }
    }

    fun performAction(id: String, action: ProviderAction) {
        when (action) {
            ProviderAction.SET_ACTIVE -> container.preferences.setActiveProviderId(id)
            ProviderAction.MOVE_UP -> container.preferences.moveProvider(id, -1)
            ProviderAction.MOVE_DOWN -> container.preferences.moveProvider(id, 1)
            ProviderAction.PIN_TOP -> container.preferences.pinProviderToTop(id)
            ProviderAction.DUPLICATE -> container.preferences.duplicateProvider(id)
            ProviderAction.DELETE -> container.preferences.deleteProvider(id)
        }
    }

    fun toggleEnabled(config: TtsProviderConfig, enabled: Boolean) =
        container.preferences.upsertProvider(config.copy(enabled = enabled))

    fun getProvider(id: String): TtsProviderConfig? = providersFlow.value.find { it.id == id }
    fun getCredentials(id: String): ProviderCredentials = container.preferences.getCredentials(id)

    fun saveProvider(config: TtsProviderConfig, credentials: ProviderCredentials) {
        container.preferences.upsertProvider(config, credentials)
        _uiState.update { it.copy(message = "配置已保存") }
    }

    fun createProviderFromTemplate(template: ProviderTemplate): String {
        val id = "${template.config.type.name.lowercase()}_${UUID.randomUUID().toString().take(6)}"
        val descriptor = ProviderDescriptorRegistry.descriptorFor(template.config.type)
        val tplHost = runCatching { java.net.URI(template.config.baseUrl).host?.lowercase() }.getOrNull()
        val officialHost = runCatching { java.net.URI(descriptor.defaultBaseUrl).host?.lowercase() }.getOrNull()
        val resolvedUrl = if (template.config.baseUrl.isBlank() || (tplHost != null && tplHost == officialHost)) {
            container.manifestRepository.defaultEndpointFor(template.config.type).ifBlank { template.config.baseUrl }
        } else {
            template.config.baseUrl
        }
        container.preferences.upsertProvider(template.config.copy(id = id, baseUrl = resolvedUrl))
        return id
    }

    fun activateOfflineModel(spec: OfflineModelSpec) {
        val existing = providersFlow.value.firstOrNull {
            it.type == ProviderType.OFFLINE_SHERPA && it.modelName == spec.id
        }
        val targetId = if (existing != null) {
            existing.id
        } else {
            val newId = "sherpa_${UUID.randomUUID().toString().take(6)}"
            container.preferences.upsertProvider(
                TtsProviderConfig(
                    id = newId,
                    type = ProviderType.OFFLINE_SHERPA,
                    name = spec.name,
                    modelName = spec.id,
                    voiceId = spec.defaultVoiceId,
                    sampleRate = spec.sampleRate,
                    audioFormat = "wav",
                    extraParams = mapOf("numThreads" to "2", "silenceScale" to "0.2")
                )
            )
            newId
        }
        container.preferences.setActiveProviderId(targetId)
        _uiState.update { it.copy(message = "已启用离线声线：${spec.name}") }
    }

    fun testAllLatencies() {
        if (_uiState.value.isBatchTesting) return
        _uiState.update { it.copy(isBatchTesting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val enabledList = providersFlow.value.filter { it.enabled }
            val results = coroutineScope {
                enabledList.map { p ->
                    async {
                        val start = System.currentTimeMillis()
                        val res = container.providerRegistry.get(p.type)
                            .testConnection(p, container.preferences.getCredentials(p.id))
                        p.id to if (res.isSuccess) System.currentTimeMillis() - start else -1L
                    }
                }.awaitAll().toMap()
            }
            _uiState.update { it.copy(latenciesMs = results, isBatchTesting = false, message = "测速完成") }
        }
    }

    fun sortByLatency() {
        val valid = _uiState.value.latenciesMs.filterValues { it >= 0 }
        if (valid.isEmpty()) {
            _uiState.update { it.copy(message = "请先执行批量测速") }
            return
        }
        container.preferences.sortProvidersByLatency(valid)
        _uiState.update { it.copy(message = "已按延迟从低到高排序") }
    }

    fun loadVoicesAndModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials,
        forceRefresh: Boolean = false
    ) {
        _uiState.update { it.copy(isLoadingVoices = true) }
        val credScope = listOf(
            credentials.apiKey.trim(),
            credentials.secondaryApiKey.trim(),
            config.extraParams["appId"].orEmpty().trim()
        ).joinToString("|")
        viewModelScope.launch(Dispatchers.IO) {
            val cached: DiscoveryCatalog? = if (!forceRefresh) {
                container.discoveryCache.get(config.type, config.baseUrl, config.modelName, credScope)
            } else {
                null
            }
            val catalog = cached ?: container.providerRegistry.get(config.type)
                .discoverCatalog(config, credentials)
                .also { container.discoveryCache.put(config.type, config.baseUrl, config.modelName, it, credScope) }

            _uiState.update {
                it.copy(
                    availableVoices = catalog.voices,
                    availableModels = catalog.models,
                    stylesByVoice = catalog.stylesByVoice,
                    discoveryFromRemote = catalog.fromRemote,
                    isLoadingVoices = false,
                    message = if (forceRefresh) {
                        if (catalog.fromRemote) "已同步上游 (${catalog.voices.size} 音色 / ${catalog.models.size} 模型)"
                        else "上游不可达或未填密钥，已展示本地预置清单"
                    } else null
                )
            }
        }
    }

    fun testAndPreview(config: TtsProviderConfig, credentials: ProviderCredentials) {
        singleTestJob?.cancel()
        container.previewPlayer.stop()
        _uiState.update { it.copy(isTestingSingle = true) }
        singleTestJob = viewModelScope.launch(Dispatchers.IO) {
            val provider = container.providerRegistry.get(config.type)
            val res = provider.synthesize(
                ProviderSynthesisRequest("你好，这是一段测试语音。", config.copy(isStreamingEnabled = false), credentials)
            )
            res.fold(
                onSuccess = { audio ->
                    val pcm = container.audioPipeline.decodeToStandardPcm(audio.bytes, audio.sampleRate, 24000)
                    pcm.fold(
                        onSuccess = { rawPcm ->
                            val finalPcm = applyPreviewEffects(rawPcm, config, provider.capabilities)
                            container.previewPlayer.openStream(24000)
                            container.previewPlayer.writeChunk(finalPcm)
                            container.previewPlayer.finishStream()
                            _uiState.update { it.copy(isTestingSingle = false, message = "试听完成") }
                        },
                        onFailure = { e ->
                            _uiState.update { it.copy(isTestingSingle = false, message = "解码失败: ${e.message}") }
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isTestingSingle = false, message = "试听失败: ${e.message}") }
                }
            )
        }
    }

    private fun applyPreviewEffects(
        rawPcm: ByteArray,
        config: TtsProviderConfig,
        caps: ProviderCapabilities
    ): ByteArray {
        val speedPlan = config.resolveSpeed(isStreaming = false, capabilities = caps)
        val scaled = if (speedPlan.needsClientSonic) {
            val scaler = SonicSpeedScaler(24000, 1, speedPlan.clientSonicFactor, speedPlan.clientSonicPitch)
            scaler.processChunk(rawPcm) + scaler.flush()
        } else {
            rawPcm
        }
        return AudioEnhancer().processPcm(
            pcmData = scaled,
            channels = 1,
            gain = 1.0f,
            trimSilence = settingsFlow.value.trimDeadSilence
        )
    }

    fun stopPreview() {
        singleTestJob?.cancel()
        singleTestJob = null
        container.previewPlayer.stop()
        _uiState.update { it.copy(isTestingSingle = false) }
    }

    fun manageOfflineModel(spec: OfflineModelSpec, download: Boolean) {
        if (!download) {
            container.modelInstaller.deleteModel(spec.id)
            refreshInstalledOfflineModels()
            _uiState.update { it.copy(message = "已清理模型文件") }
            return
        }
        if (_uiState.value.downloadingModelId != null) return
        _uiState.update { it.copy(downloadingModelId = spec.id, downloadProgress = 0, downloadMessage = "准备下载...") }
        viewModelScope.launch {
            val res = container.modelInstaller.installModel(spec) { pct, msg ->
                _uiState.update { it.copy(downloadProgress = pct, downloadMessage = msg) }
            }
            refreshInstalledOfflineModels()
            _uiState.update {
                it.copy(
                    downloadingModelId = null,
                    downloadProgress = 0,
                    downloadMessage = "",
                    message = res.fold(onSuccess = { "${spec.name} 下载就绪" }, onFailure = { e -> "下载失败: ${e.message}" })
                )
            }
        }
    }

    fun syncOfflineManifest() {
        viewModelScope.launch {
            val res = container.manifestRepository.refresh(settingsFlow.value.manifestUrl)
            refreshInstalledOfflineModels()
            _uiState.update {
                it.copy(message = res.fold(
                    onSuccess = { "已同步远程清单 (v${it.version})" },
                    onFailure = { e -> "同步失败: ${e.message}" }
                ))
            }
        }
    }

    fun defaultsForType(type: ProviderType): TtsProviderConfig {
        val tpl = ProviderDescriptorRegistry.templates.firstOrNull { it.config.type == type }?.config
            ?: TtsProviderConfig(id = "new", type = type, name = type.displayName)
        val remoteUrl = container.manifestRepository.defaultEndpointFor(type)
        return if (remoteUrl.isNotBlank() && type != ProviderType.CUSTOM_HTTP) tpl.copy(baseUrl = remoteUrl) else tpl
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProvidersViewModel(container) as T
    }
}
