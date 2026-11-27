package com.aitts.v2.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.permission.AppPermissionState
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkSectionHeader
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val logSessions by viewModel.logSessionsFlow.collectAsStateWithLifecycle()
    val rawLogs by viewModel.rawLogsFlow.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val permState by viewModel.permissionState.collectAsStateWithLifecycle()

    var showProxyDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions(context)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions(context)
        viewModel.refreshCacheStats()
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("偏好与系统", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "系统接入状态、分段策略、声学调音与主题备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SystemReadinessCard(
                permState = permState,
                onOpenTts = { PermissionHelper.openSystemTtsSettings(context) },
                onOpenBattery = { PermissionHelper.openIgnoreBatteryOptimizations(context) },
                onOpenOverlay = { PermissionHelper.openOverlayPermission(context) }
            )
        }

        item {
            InkSectionHeader("通知与悬浮字幕")
            SystemSubtitleSection(
                settings = settings,
                overlayGranted = permState.overlayGranted,
                onUpdate = viewModel::updateSettings,
                onToggleSubtitle = { enabled ->
                    if (enabled && !permState.overlayGranted) {
                        PermissionHelper.openOverlayPermission(context)
                    } else {
                        viewModel.toggleSubtitlePreview(enabled)
                    }
                },
                onOpenSubtitleSheet = { showSubtitleSheet = true }
            )
        }

        item {
            InkSectionHeader("文本分段与预加载")
            SegmentationSection(settings = settings, onUpdate = viewModel::updateSettings)
        }

        item {
            InkSectionHeader("声学调音与语义规整")
            AcousticsSection(settings = settings, onUpdate = viewModel::updateSettings)
        }

        item {
            InkSectionHeader("网络代理与容灾")
            NetworkResilienceSection(
                settings = settings,
                providers = providers,
                onUpdate = viewModel::updateSettings,
                onOpenProxyDialog = { showProxyDialog = true }
            )
        }

        item {
            InkSectionHeader("纸墨主题与数据管理")
            ThemeBackupSection(
                settings = settings,
                cacheStats = cacheStats,
                onUpdate = viewModel::updateSettings,
                onClearCache = viewModel::clearCache,
                onOpenLogs = { showLogsSheet = true },
                onOpenBackup = { showBackupDialog = true },
                onOpenReset = { showResetDialog = true }
            )
        }
    }

    if (showProxyDialog) {
        ProxyConfigDialog(
            settings = settings,
            onSave = { updated -> viewModel.updateSettings { updated }; showProxyDialog = false },
            onDismiss = { showProxyDialog = false }
        )
    }
    if (showBackupDialog) {
        BackupDialog(
            onExport = { includeSecrets -> viewModel.exportBackupJson(includeSecrets) },
            onImport = { json -> viewModel.importBackupJson(json); showBackupDialog = false },
            onDismiss = { showBackupDialog = false }
        )
    }
    if (showSubtitleSheet) {
        SubtitleCustomizerSheet(settings = settings, onUpdate = viewModel::updateSettings, onDismiss = { showSubtitleSheet = false })
    }
    if (showLogsSheet) {
        LogViewerSheet(
            sessions = logSessions,
            rawLogs = rawLogs,
            onClear = viewModel::clearLogs,
            onDismiss = { showLogsSheet = false }
        )
    }
    if (showResetDialog) {
        ConfirmResetDialog(
            onConfirm = { viewModel.resetDefaults(); showResetDialog = false },
            onDismiss = { showResetDialog = false }
        )
    }
}

@Composable
private fun SystemReadinessCard(
    permState: AppPermissionState,
    onOpenTts: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        ReadinessRow(
            title = "系统默认 TTS 引擎",
            status = if (permState.isSystemDefaultTts) "已设为默认" else "点击前往切换",
            ok = permState.isSystemDefaultTts,
            onClick = onOpenTts
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReadinessRow(
            title = "后台电池白名单",
            status = if (permState.ignoringBatteryOptimizations) "已放行" else "建议忽略优化",
            ok = permState.ignoringBatteryOptimizations,
            onClick = onOpenBattery
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReadinessRow(
            title = "悬浮字幕权限",
            status = if (permState.overlayGranted) "已授权" else "前往授权",
            ok = permState.overlayGranted,
            onClick = onOpenOverlay
        )
    }
}

@Composable
private fun ReadinessRow(title: String, status: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        InkStatusPill(status, if (ok) PillTone.SUCCESS else PillTone.WARNING, onClick = onClick)
    }
}
