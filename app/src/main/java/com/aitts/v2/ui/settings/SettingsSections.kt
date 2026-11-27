package com.aitts.v2.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.AudioProcessingMode
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.SegmentationMode
import com.aitts.v2.model.SpeedPolicy
import com.aitts.v2.model.ThemeMode
import com.aitts.v2.model.ThemePalette
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
internal fun SystemSubtitleSection(
    settings: GlobalSettings,
    overlayGranted: Boolean,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onToggleSubtitle: (Boolean) -> Unit,
    onOpenSubtitleSheet: () -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        InkSwitchRow("后台播放状态通知栏", settings.playbackNotificationEnabled) { c ->
            onUpdate { it.copy(playbackNotificationEnabled = c) }
        }
        InkSwitchRow(
            title = if (overlayGranted) "前台悬浮小说字幕" else "前台悬浮小说字幕 (需授权)",
            checked = settings.isFloatingSubtitleEnabled,
            onCheckedChange = onToggleSubtitle
        )
        InkActionRow("悬浮字幕样式定制", settings.floatingSubtitleNovelPreset.displayName, onOpenSubtitleSheet)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SegmentationSection(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        InkSwitchRow("启用分段切分与并发预取", settings.isSentenceSplittingEnabled) { c ->
            onUpdate { it.copy(isSentenceSplittingEnabled = c) }
        }
        if (settings.isSentenceSplittingEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegmentationMode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.textSegmentationMode == m,
                        onClick = { onUpdate { it.copy(textSegmentationMode = m) } },
                        label = { Text(m.displayName) }
                    )
                }
            }
            InkSwitchRow("短段落自动合并", settings.mergeShortParagraphs) { c ->
                onUpdate { it.copy(mergeShortParagraphs = c) }
            }
            InkSwitchRow("超长段落按句末标点拆分", settings.splitLongParagraphs) { c ->
                onUpdate { it.copy(splitLongParagraphs = c) }
            }
            InkSwitchRow("滑动窗口并发预加载", settings.enableSegmentPreload) { c ->
                onUpdate { it.copy(enableSegmentPreload = c) }
            }
            Text("预加载前瞻深度: ${settings.preloadAheadCount} 段", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.preloadAheadCount.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(preloadAheadCount = v.toInt()) } },
                valueRange = 1f..4f,
                steps = 2
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AcousticsSection(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        Text("全局语速倍率 ${"%.2f".format(settings.globalSpeed)}×", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.globalSpeed,
            onValueChange = { v -> onUpdate { it.copy(globalSpeed = v) } },
            valueRange = 0.5f..2.0f
        )
        Text("全局音调倍率 ${"%.2f".format(settings.globalPitch)}", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.globalPitch,
            onValueChange = { v -> onUpdate { it.copy(globalPitch = v) } },
            valueRange = 0.5f..2.0f
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeedPolicy.entries.forEach { p ->
                FilterChip(
                    selected = settings.speedPolicy == p,
                    onClick = { onUpdate { it.copy(speedPolicy = p) } },
                    label = { Text(p.displayName) }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioProcessingMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.audioProcessingMode == m,
                    onClick = { onUpdate { it.copy(audioProcessingMode = m) } },
                    label = { Text(m.displayName) }
                )
            }
        }
        if (settings.audioProcessingMode == AudioProcessingMode.CUSTOM) {
            InkSwitchRow("人声清晰度滤波增强", settings.voiceClarityBoostEnabled) { c ->
                onUpdate { it.copy(voiceClarityBoostEnabled = c) }
            }
            Text("响度增益倍率 ${"%.2f".format(settings.loudnessGainFactor)}×", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.loudnessGainFactor,
                onValueChange = { v -> onUpdate { it.copy(loudnessGainFactor = v) } },
                valueRange = 0.6f..1.8f
            )
        }
        Text("句间自然停顿: ${settings.sentencePauseMs} ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.sentencePauseMs.toFloat(),
            onValueChange = { v -> onUpdate { it.copy(sentencePauseMs = v.toInt()) } },
            valueRange = 0f..600f
        )
        InkSwitchRow("首尾死区静音裁剪", settings.trimDeadSilence) { c ->
            onUpdate { it.copy(trimDeadSilence = c) }
        }
        InkSwitchRow("对白微情绪提示词注入", settings.isEmotionProsodyEnabled) { c ->
            onUpdate { it.copy(isEmotionProsodyEnabled = c) }
        }
    }
}

@Composable
internal fun NetworkResilienceSection(
    settings: GlobalSettings,
    providers: List<TtsProviderConfig>,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onOpenProxyDialog: () -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        val proxySummary = if (settings.proxyEnabled) {
            "${settings.proxyType} ${settings.proxyHost}:${settings.proxyPort} · 超时 ${settings.readTimeoutSeconds}s"
        } else {
            "直连 (连接 ${settings.connectTimeoutSeconds}s / 读取 ${settings.readTimeoutSeconds}s)"
        }
        InkActionRow("HTTP / SOCKS5 代理与超时", proxySummary, onOpenProxyDialog)
        InkSwitchRow("网络异常自动抖动重试", settings.autoRetryOnFailure) { c ->
            onUpdate { it.copy(autoRetryOnFailure = c) }
        }
        InkSwitchRow("主引擎失败自动降级兜底", settings.autoFallbackOnFailure) { c ->
            onUpdate { it.copy(autoFallbackOnFailure = c) }
        }
        if (settings.autoFallbackOnFailure) {
            Text("指定兜底节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                providers.forEach { p ->
                    FilterChip(
                        selected = settings.fallbackProviderId == p.id,
                        onClick = { onUpdate { it.copy(fallbackProviderId = p.id) } },
                        label = { Text(p.name.take(16)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeBackupSection(
    settings: GlobalSettings,
    cacheStats: Pair<Int, Float>,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onClearCache: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReset: () -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.themeMode == m,
                    onClick = { onUpdate { it.copy(themeMode = m) } },
                    label = { Text(m.displayName) }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemePalette.entries.forEach { p ->
                FilterChip(
                    selected = settings.themePalette == p,
                    onClick = { onUpdate { it.copy(themePalette = p) } },
                    leadingIcon = {
                        Box(modifier = Modifier.size(10.dp).background(Color(p.seedHex), CircleShape))
                    },
                    label = { Text(p.displayName) }
                )
            }
        }
        InkSwitchRow("AMOLED 纯黑背景 (深色模式)", settings.isAmoledPureBlack) { c ->
            onUpdate { it.copy(isAmoledPureBlack = c) }
        }
        InkSwitchRow("音频本地磁盘缓存", settings.isAudioCacheEnabled) { c ->
            onUpdate { it.copy(isAudioCacheEnabled = c) }
        }
        if (settings.isAudioCacheEnabled) {
            Text("磁盘缓存上限 ${settings.maxCacheSizeMb} MB", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.maxCacheSizeMb.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(maxCacheSizeMb = v.toInt()) } },
                valueRange = 50f..1000f
            )
        }
        InkActionRow("清空磁盘音频缓存", "当前 ${cacheStats.first} 个分片 · ${"%.2f".format(cacheStats.second)} MB", onClearCache)
        InkActionRow("请求诊断日志", "查看分段耗时与网络详情", onOpenLogs)
        InkActionRow("全量配置备份 / 恢复", "支持剪贴板脱敏口令与 JSON 文件导入导出", onOpenBackup)
        InkActionRow("恢复官方默认设置", "重置全局参数、引擎与词典规则 (保留 Keystore 密钥)", onOpenReset)
    }
}

@Composable
private fun InkSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InkActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Text("操作") }
    }
}
