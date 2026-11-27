package com.aitts.v2.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aitts.v2.di.AppContainer
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.LogEntry
import com.aitts.v2.model.RequestLogSession
import com.aitts.v2.permission.AppPermissionState
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.service.SystemTtsSelfTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settingsFlow: StateFlow<GlobalSettings> = container.preferences.settingsFlow
    val providersFlow = container.preferences.providersFlow
    val rawLogsFlow: StateFlow<List<LogEntry>> = container.logger.entriesFlow
    val logSessionsFlow: StateFlow<List<RequestLogSession>> = container.logger.entriesFlow
        .map { container.logger.groupBySession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cacheStats = MutableStateFlow(container.diskCache.getStats())
    val cacheStats: StateFlow<Pair<Int, Float>> = _cacheStats.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _permissionState = MutableStateFlow(
        AppPermissionState(
            ignoringBatteryOptimizations = true,
            notificationGranted = true,
            overlayGranted = false,
            isSystemDefaultTts = false
        )
    )
    val permissionState: StateFlow<AppPermissionState> = _permissionState.asStateFlow()

    fun refreshPermissions(context: Context) {
        _permissionState.value = PermissionHelper.check(context)
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {
        val updated = transform(settingsFlow.value)
        container.preferences.updateSettings(updated)
        container.subtitleOverlay.applySettings(updated)
    }

    fun toggleSubtitlePreview(enabled: Boolean) {
        updateSettings { it.copy(isFloatingSubtitleEnabled = enabled) }
        if (enabled) container.subtitleOverlay.show() else container.subtitleOverlay.hide()
    }

    fun refreshCacheStats() {
        _cacheStats.value = container.diskCache.getStats()
    }

    fun clearCache() {
        container.diskCache.clearAll()
        container.discoveryCache.clear()
        refreshCacheStats()
        _message.value = "音频与发现缓存已清空"
    }

    fun syncRemoteManifest() {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val res = container.manifestRepository.refresh(settingsFlow.value.manifestUrl)
                _message.value = res.fold(
                    onSuccess = { m -> "已同步上游清单 v${m.version} (${m.updatedAt})" },
                    onFailure = { e -> "同步失败: ${e.message}" }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun runSystemTtsSelfTest(context: Context) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val res = SystemTtsSelfTester.runSelfTest(context)
                _message.value = res.message
            } catch (t: Throwable) {
                _message.value = "自检失败: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearLogs() = container.logger.clear()

    fun exportBackupJson(includeSecrets: Boolean): String =
        container.backupManager.exportAllJson(includeSecrets = includeSecrets)

    fun importBackupJson(rawJson: String) {
        val res = container.backupManager.importAllJson(rawJson)
        _message.value = res.fold(
            onSuccess = { "配置已恢复" },
            onFailure = { e -> "导入失败: ${e.message}" }
        )
    }

    fun resetDefaults() {
        container.preferences.resetToDefaults()
        _message.value = "已恢复官方默认设置"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
    }
}
