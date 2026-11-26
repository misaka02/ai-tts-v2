package com.aitts.v2.ui.hub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.audio.MediaStoreAudioExporter
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.audio.WavCodec
import com.aitts.v2.engine.synthesis.SynthesisInput
import com.aitts.v2.engine.synthesis.SynthesisSink
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.LogEntry
import com.aitts.v2.model.RequestLogSession
import com.aitts.v2.model.SpeechHistoryItem
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

data class HubUiState(
    val inputText: String = "欢迎使用 AI-TTS v2，这是一个高保真、低延迟的安卓系统级大模型语音引擎。",
    val quoteSource: String = "默认试听语料",
    val isFetchingQuote: Boolean = false,
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val isTestingPing: Boolean = false,
    val lastPingMs: Long? = null,
    val lastLatencyMs: Long? = null,
    val canExport: Boolean = false,
    val statusMessage: String? = null
)

class SpeechHubViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    val settingsFlow: StateFlow<GlobalSettings> = container.preferences.settingsFlow
    val providersFlow: StateFlow<List<TtsProviderConfig>> = container.preferences.providersFlow
    val activeProviderFlow: StateFlow<TtsProviderConfig> = combine(
        container.preferences.settingsFlow,
        container.preferences.providersFlow
    ) { _, _ -> container.preferences.getActiveProvider() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), container.preferences.getActiveProvider())

    val rmsFlow: StateFlow<Float> = container.previewPlayer.rmsFlow
    val historyFlow: StateFlow<List<SpeechHistoryItem>> = container.preferences.historyFlow
    val sleepActiveFlow: StateFlow<Boolean> = container.sleepTimer.isActiveFlow
    val sleepRemainingFlow: StateFlow<Int> = container.sleepTimer.remainingSecondsFlow

    val logSessionsFlow: StateFlow<List<RequestLogSession>> = container.logger.entriesFlow
        .map { container.logger.groupBySession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawLogsFlow: StateFlow<List<LogEntry>> = container.logger.entriesFlow

    private var activeSessionId: String? = null
    private var previewJob: Job? = null
    private var lastSynthesizedPcm: ByteArray = ByteArray(0)

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun loadLocalQuote() {
        val quote = container.quoteRepository.randomLocalQuote()
        _uiState.update { it.copy(inputText = quote.text, quoteSource = quote.source) }
    }

    fun loadOnlineQuote() {
        _uiState.update { it.copy(isFetchingQuote = true) }
        viewModelScope.launch {
            val quote = container.quoteRepository.fetchOnlineQuote()
            _uiState.update {
                it.copy(inputText = quote.text, quoteSource = quote.source, isFetchingQuote = false)
            }
        }
    }

    fun updateActiveSpeed(speed: Float) {
        val cur = container.preferences.getActiveProvider()
        container.preferences.upsertProvider(cur.copy(speed = speed.coerceIn(0.4f, 2.5f)))
    }

    fun updateActivePitch(pitch: Float) {
        val cur = container.preferences.getActiveProvider()
        container.preferences.upsertProvider(cur.copy(pitch = pitch.coerceIn(0.5f, 2.0f)))
    }

    fun toggleStreaming(enabled: Boolean) {
        val cur = container.preferences.getActiveProvider()
        container.preferences.upsertProvider(cur.copy(isStreamingEnabled = enabled))
    }

    fun selectActiveProvider(providerId: String) {
        container.preferences.setActiveProviderId(providerId)
    }

    fun testActiveLatency() {
        val active = container.preferences.getActiveProvider()
        val creds = container.preferences.getCredentials(active.id)
        _uiState.update { it.copy(isTestingPing = true) }
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            val res = container.providerRegistry.get(active.type).testConnection(active, creds)
            val cost = System.currentTimeMillis() - start
            _uiState.update {
                if (res.isSuccess) {
                    it.copy(isTestingPing = false, lastPingMs = cost, statusMessage = "${active.name} 延迟 ${cost}ms")
                } else {
                    it.copy(isTestingPing = false, lastPingMs = -1L, statusMessage = "测速失败: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun togglePlayPreview() {
        if (_uiState.value.isPlaying || _uiState.value.isSynthesizing) {
            stopPreview()
            return
        }
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "请输入要朗读的文本") }
            return
        }

        val sessionId = "hub_${UUID.randomUUID().toString().take(6)}"
        activeSessionId = sessionId
        val pcmCollector = ByteArrayOutputStream()
        val startMs = System.currentTimeMillis()
        var firstAudioAt: Long? = null

        _uiState.update { it.copy(isSynthesizing = true, isPlaying = false) }
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val active = container.preferences.getActiveProvider()
            val input = SynthesisInput(
                rawText = text,
                settings = container.preferences.settingsFlow.value,
                activeConfig = active,
                allProviders = container.preferences.providersFlow.value,
                rules = container.preferences.rulesFlow.value,
                credentialsLookup = { id -> container.preferences.getCredentials(id) },
                fadeGainProvider = { container.sleepTimer.getFadeVolumeFactor() },
                onHistoryRecorded = { item -> container.preferences.recordSpeechHistory(item) }
            )

            val sink = object : SynthesisSink {
                override fun onStart(sampleRate: Int): Boolean {
                    container.previewPlayer.openStream(sampleRate)
                    return true
                }

                override fun onPcmChunk(pcm16Bytes: ByteArray) {
                    if (firstAudioAt == null) {
                        firstAudioAt = System.currentTimeMillis() - startMs
                        _uiState.update { it.copy(isSynthesizing = false, isPlaying = true, lastLatencyMs = firstAudioAt) }
                    }
                    pcmCollector.write(pcm16Bytes)
                    container.previewPlayer.writeChunk(pcm16Bytes)
                }

                override fun onDone() {
                    container.previewPlayer.finishStream()
                    val collected = pcmCollector.toByteArray()
                    if (collected.isNotEmpty()) lastSynthesizedPcm = collected
                    _uiState.update {
                        it.copy(
                            isSynthesizing = false,
                            isPlaying = false,
                            canExport = lastSynthesizedPcm.isNotEmpty(),
                            lastLatencyMs = firstAudioAt ?: (System.currentTimeMillis() - startMs)
                        )
                    }
                }

                override fun onError(error: Throwable) {
                    container.previewPlayer.stop()
                    _uiState.update {
                        it.copy(isSynthesizing = false, isPlaying = false, statusMessage = "合成失败: ${error.message}")
                    }
                }
            }

            try {
                container.orchestrator.execute(sessionId, input, sink)
            } catch (_: CancellationException) {
                container.previewPlayer.stop()
                _uiState.update { it.copy(isSynthesizing = false, isPlaying = false) }
            }
        }
    }

    fun stopPreview() {
        activeSessionId?.let { container.orchestrator.cancelSession(it) }
        activeSessionId = null
        previewJob?.cancel()
        previewJob = null
        container.previewPlayer.stop()
        _uiState.update { it.copy(isSynthesizing = false, isPlaying = false) }
    }

    fun exportLastAudio(context: Context) {
        if (lastSynthesizedPcm.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "请先试听合成一段音频") }
            return
        }
        val wav = WavCodec.encodeToWav(lastSynthesizedPcm, sampleRate = 24000, channels = 1)
        val res = MediaStoreAudioExporter.exportToDownloads(context, wav, sampleRate = 24000)
        _uiState.update {
            it.copy(statusMessage = res.fold(
                onSuccess = { path -> "已导出至 $path" },
                onFailure = { err -> "导出失败: ${err.message}" }
            ))
        }
    }

    fun startSleepTimer(minutes: Int) = container.sleepTimer.start(minutes)
    fun stopSleepTimer() = container.sleepTimer.stop()
    fun clearHistory() = container.preferences.clearHistory()
    fun clearLogs() = container.logger.clear()

    override fun onCleared() {
        stopPreview()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SpeechHubViewModel(container) as T
    }
}
