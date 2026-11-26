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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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
        refreshCacheStats()
        _message.value = "音频缓存已清空"
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

    fun checkPermissions(context: Context): AppPermissionState = PermissionHelper.check(context)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
    }
}
