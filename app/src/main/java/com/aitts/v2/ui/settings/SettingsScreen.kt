package com.aitts.v2.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.model.AudioProcessingMode
import com.aitts.v2.model.SegmentationMode
import com.aitts.v2.model.SpeedPolicy
import com.aitts.v2.model.ThemeMode
import com.aitts.v2.model.ThemePalette
import com.aitts.v2.permission.PermissionHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val logSessions by viewModel.logSessionsFlow.collectAsStateWithLifecycle()
    val rawLogs by viewModel.rawLogsFlow.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showProxyDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val permState = remember(settings) { viewModel.checkPermissions(context) }

    LaunchedEffect(Unit) { viewModel.refreshCacheStats() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("系统与引擎设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            SettingsCard("Android 系统集成") {
                ActionRow("系统默认 TTS 引擎设置", "设为系统首选后，开源阅读 (Legado) / 静读天下可直接调用") {
                    PermissionHelper.openSystemTtsSettings(context)
                }
                ActionRow(
                    "电池优化白名单",
                    if (permState.ignoringBatteryOptimizations) "已加入白名单" else "建议开启以防锁屏断流"
                ) { PermissionHelper.openIgnoreBatteryOptimizations(context) }
                SwitchRow("后台播放状态通知栏", settings.playbackNotificationEnabled) { c ->
                    viewModel.updateSettings { it.copy(playbackNotificationEnabled = c) }
                }
                SwitchRow("前台悬浮小说字幕", settings.isFloatingSubtitleEnabled) { c ->
                    if (c && !permState.overlayGranted) PermissionHelper.openOverlayPermission(context)
                    else viewModel.toggleSubtitlePreview(c)
                }
                ActionRow("悬浮字幕样式定制", settings.floatingSubtitleNovelPreset.displayName) {
                    showSubtitleSheet = true
                }
            }
        }

        item {
            SettingsCard("文本分段与预加载") {
                SwitchRow("启用分段切分与并发预取", settings.isSentenceSplittingEnabled) { c ->
                    viewModel.updateSettings { it.copy(isSentenceSplittingEnabled = c) }
                }
                if (settings.isSentenceSplittingEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegmentationMode.entries.forEach { m ->
                            FilterChip(
                                selected = settings.textSegmentationMode == m,
                                onClick = { viewModel.updateSettings { it.copy(textSegmentationMode = m) } },
                                label = { Text(m.displayName) }
                            )
                        }
                    }
                    SwitchRow("短段落自动合并", settings.mergeShortParagraphs) { c ->
                        viewModel.updateSettings { it.copy(mergeShortParagraphs = c) }
                    }
                    SwitchRow("超长段落按句末标点拆分", settings.splitLongParagraphs) { c ->
                        viewModel.updateSettings { it.copy(splitLongParagraphs = c) }
                    }
                    SwitchRow("滑动窗口并发预加载", settings.enableSegmentPreload) { c ->
                        viewModel.updateSettings { it.copy(enableSegmentPreload = c) }
                    }
                    Text("预加载前瞻深度: ${settings.preloadAheadCount} 段")
                    Slider(
                        value = settings.preloadAheadCount.toFloat(),
                        onValueChange = { v -> viewModel.updateSettings { it.copy(preloadAheadCount = v.toInt()) } },
                        valueRange = 1f..4f,
                        steps = 2
                    )
                }
            }
        }

        item {
            SettingsCard("声学、语速策略与语义规整") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeedPolicy.entries.forEach { p ->
                        FilterChip(
                            selected = settings.speedPolicy == p,
                            onClick = { viewModel.updateSettings { it.copy(speedPolicy = p) } },
                            label = { Text(p.displayName) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioProcessingMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.audioProcessingMode == m,
                            onClick = { viewModel.updateSettings { it.copy(audioProcessingMode = m) } },
                            label = { Text(m.displayName) }
                        )
                    }
                }
                Text("句间自然停顿: ${settings.sentencePauseMs} ms")
                Slider(
                    value = settings.sentencePauseMs.toFloat(),
                    onValueChange = { v -> viewModel.updateSettings { it.copy(sentencePauseMs = v.toInt()) } },
                    valueRange = 0f..600f
                )
                SwitchRow("首尾死区静音裁剪", settings.trimDeadSilence) { c ->
                    viewModel.updateSettings { it.copy(trimDeadSilence = c) }
                }
                SwitchRow("中文数字/时间/金额读法规整", settings.isNumberNormalizationEnabled) { c ->
                    viewModel.updateSettings { it.copy(isNumberNormalizationEnabled = c) }
                }
                SwitchRow("英文缩写规整 (AI/WiFi 等)", settings.isAcronymNormalizationEnabled) { c ->
                    viewModel.updateSettings { it.copy(isAcronymNormalizationEnabled = c) }
                }
                SwitchRow("对白微情绪提示词注入", settings.isEmotionProsodyEnabled) { c ->
                    viewModel.updateSettings { it.copy(isEmotionProsodyEnabled = c) }
                }
            }
        }

        item {
            SettingsCard("网络代理与高可用容灾") {
                ActionRow(
                    "HTTP / SOCKS5 代理",
                    if (settings.proxyEnabled) "${settings.proxyType} ${settings.proxyHost}:${settings.proxyPort}" else "未启用"
                ) { showProxyDialog = true }
                SwitchRow("网络异常自动抖动重试", settings.autoRetryOnFailure) { c ->
                    viewModel.updateSettings { it.copy(autoRetryOnFailure = c) }
                }
                SwitchRow("主引擎失败自动降级兜底", settings.autoFallbackOnFailure) { c ->
                    viewModel.updateSettings { it.copy(autoFallbackOnFailure = c) }
                }
                if (settings.autoFallbackOnFailure) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        providers.take(5).forEach { p ->
                            FilterChip(
                                selected = settings.fallbackProviderId == p.id,
                                onClick = { viewModel.updateSettings { it.copy(fallbackProviderId = p.id) } },
                                label = { Text(p.name.take(14)) }
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsCard("界面主题与存储备份") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.themeMode == m,
                            onClick = { viewModel.updateSettings { it.copy(themeMode = m) } },
                            label = { Text(m.displayName) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemePalette.entries.forEach { p ->
                        FilterChip(
                            selected = settings.themePalette == p,
                            onClick = { viewModel.updateSettings { it.copy(themePalette = p) } },
                            label = { Text(p.displayName) }
                        )
                    }
                }
                SwitchRow("AMOLED 纯黑背景 (深色模式)", settings.isAmoledPureBlack) { c ->
                    viewModel.updateSettings { it.copy(isAmoledPureBlack = c) }
                }
                SwitchRow("音频本地磁盘缓存", settings.isAudioCacheEnabled) { c ->
                    viewModel.updateSettings { it.copy(isAudioCacheEnabled = c) }
                }
                ActionRow("清空磁盘音频缓存", "当前 ${cacheStats.first} 个分片 · ${"%.2f".format(cacheStats.second)} MB") {
                    viewModel.clearCache()
                }
                ActionRow("请求诊断日志", "查看分段耗时与网络详情") { showLogsSheet = true }
                ActionRow("全量配置备份 / 恢复", "支持一键脱敏导出与 JSON 导入") { showBackupDialog = true }
                ActionRow("恢复默认设置", "重置全局设置、引擎与规则列表") { showResetDialog = true }
            }
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
        SubtitleCustomizerSheet(
            settings = settings,
            onUpdate = viewModel::updateSettings,
            onDismiss = { showSubtitleSheet = false }
        )
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
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) { Text("操作") }
    }
}
