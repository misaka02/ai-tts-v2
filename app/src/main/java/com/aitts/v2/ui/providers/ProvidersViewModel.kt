package com.aitts.v2.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.audio.WavCodec
import com.aitts.v2.engine.provider.ProviderSynthesisRequest
import com.aitts.v2.model.OfflineCatalogData
import com.aitts.v2.model.OfflineModelSpec
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvidersUiState(
    val latenciesMs: Map<String, Long> = emptyMap(),
    val isBatchTesting: Boolean = false,
    val downloadingModelId: String? = null,
    val downloadProgress: Int = 0,
    val downloadMessage: String = "",
    val downloadedModelIds: Set<String> = emptySet(),
    val availableVoices: List<VoiceModel> = emptyList(),
    val availableModels: List<String> = emptyList(),
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

    init {
        refreshInstalledOfflineModels()
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun refreshInstalledOfflineModels() {
        val installed = OfflineCatalogData.models
            .filter { container.modelInstaller.isModelInstalled(it.id) }
            .map { it.id }
            .toSet()
        _uiState.update { it.copy(downloadedModelIds = installed) }
    }

    fun setActive(id: String) = container.preferences.setActiveProviderId(id)
    fun move(id: String, delta: Int) = container.preferences.moveProvider(id, delta)
    fun pinToTop(id: String) = container.preferences.pinProviderToTop(id)
    fun duplicate(id: String) = container.preferences.duplicateProvider(id)
    fun delete(id: String) = container.preferences.deleteProvider(id)

    fun toggleEnabled(config: TtsProviderConfig, enabled: Boolean) {
        container.preferences.upsertProvider(config.copy(enabled = enabled))
    }

    fun getProvider(id: String): TtsProviderConfig? =
        providersFlow.value.find { it.id == id }

    fun getCredentials(id: String): ProviderCredentials =
        container.preferences.getCredentials(id)

    fun saveProvider(config: TtsProviderConfig, credentials: ProviderCredentials) {
        container.preferences.upsertProvider(config, credentials)
        _uiState.update { it.copy(message = "配置已保存") }
    }

    fun exportShareToken(provider: TtsProviderConfig, includeSecrets: Boolean): String =
        container.backupManager.exportProviderToken(provider, includeSecrets)

    fun importShareToken(token: String) {
        val res = container.backupManager.importProviderToken(token)
        _uiState.update {
            it.copy(message = res.fold(
                onSuccess = { p -> "已导入: ${p.name}" },
                onFailure = { e -> "导入失败: ${e.message}" }
            ))
        }
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
                        val cost = if (res.isSuccess) System.currentTimeMillis() - start else -1L
                        p.id to cost
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

    fun loadVoicesAndModels(config: TtsProviderConfig, credentials: ProviderCredentials) {
        _uiState.update { it.copy(isLoadingVoices = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val provider = container.providerRegistry.get(config.type)
            val voices = runCatching { provider.fetchVoices(config, credentials) }.getOrDefault(emptyList())
            val models = runCatching { provider.fetchModels(config, credentials) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(availableVoices = voices, availableModels = models, isLoadingVoices = false)
            }
        }
    }

    fun testAndPreview(config: TtsProviderConfig, credentials: ProviderCredentials) {
        _uiState.update { it.copy(isTestingSingle = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val provider = container.providerRegistry.get(config.type)
            val res = provider.synthesize(
                ProviderSynthesisRequest("你好，这是一段测试语音。", config.copy(isStreamingEnabled = false), credentials)
            )
            res.fold(
                onSuccess = { audio ->
                    val pcm = container.audioPipeline.decodeToStandardPcm(audio.bytes, audio.sampleRate, 24000)
                    pcm.fold(
                        onSuccess = { pcmBytes ->
                            container.previewPlayer.openStream(24000)
                            container.previewPlayer.writeChunk(pcmBytes)
                            container.previewPlayer.finishStream()
                            _uiState.update { it.copy(isTestingSingle = false, message = "试听成功 (${pcmBytes.size} bytes)") }
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

    fun stopPreview() = container.previewPlayer.stop()

    fun downloadOfflineModel(spec: OfflineModelSpec) {
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
                    message = res.fold(
                        onSuccess = { "${spec.name} 下载就绪" },
                        onFailure = { e -> "下载失败: ${e.message}" }
                    )
                )
            }
        }
    }

    fun deleteOfflineModel(modelId: String) {
        container.modelInstaller.deleteModel(modelId)
        refreshInstalledOfflineModels()
        _uiState.update { it.copy(message = "已清理模型文件") }
    }

    fun defaultsForType(type: ProviderType): TtsProviderConfig {
        val preset = com.aitts.v2.model.PresetCatalog.createDefaultProviders().firstOrNull { it.type == type }
        return preset ?: TtsProviderConfig(id = "new", type = type, name = type.displayName)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProvidersViewModel(container) as T
    }
}
