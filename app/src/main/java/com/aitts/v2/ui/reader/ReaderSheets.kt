package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("排版与纸张主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            InkPaperCard(padding = 14.dp) {
                Text("纸张与夜读模式", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReaderPaperTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.paperTheme == theme,
                            onClick = { onUpdate { it.copy(paperTheme = theme) } },
                            label = { Text(theme.displayName) }
                        )
                    }
                }
                Text("字体风格", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFontFamily.entries.forEach { family ->
                        FilterChip(
                            selected = settings.fontFamily == family,
                            onClick = { onUpdate { it.copy(fontFamily = family) } },
                            label = { Text(family.displayName) }
                        )
                    }
                }
            }

            InkPaperCard(padding = 14.dp) {
                Text("字号 ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { v -> onUpdate { it.copy(fontSizeSp = v) } },
                    valueRange = 14f..28f
                )
                Text("行距 ${"%.2f".format(settings.lineHeightMultiplier)}×", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.lineHeightMultiplier,
                    onValueChange = { v -> onUpdate { it.copy(lineHeightMultiplier = v) } },
                    valueRange = 1.3f..2.2f
                )
                Text("左右页边距 ${settings.horizontalPaddingDp.toInt()} dp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.horizontalPaddingDp,
                    onValueChange = { v -> onUpdate { it.copy(horizontalPaddingDp = v) } },
                    valueRange = 12f..32f
                )
            }

            InkPaperCard(padding = 14.dp) {
                ToggleRow("中文首行两格缩进", settings.firstLineIndent) { checked ->
                    onUpdate { it.copy(firstLineIndent = checked) }
                }
                ToggleRow("听书自动滚屏跟随高亮", settings.autoScrollOnTts) { checked ->
                    onUpdate { it.copy(autoScrollOnTts = checked) }
                }
                ToggleRow("章节结束自动连播下一章", settings.autoNextChapterOnTts) { checked ->
                    onUpdate { it.copy(autoNextChapterOnTts = checked) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderTtsSheet(
    activeProvider: TtsProviderConfig,
    providers: List<TtsProviderConfig>,
    isPlayingOrSynthesizing: Boolean,
    sleepActive: Boolean,
    sleepRemainingSec: Int,
    floatingSubtitleEnabled: Boolean,
    onSelectProvider: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onToggleSubtitle: (Boolean) -> Unit,
    onStopTts: () -> Unit,
    onDismiss: () -> Unit
) {
    val descriptor = remember(activeProvider.type) { ProviderDescriptorRegistry.descriptorFor(activeProvider.type) }
    val acoustic = descriptor.acousticSpec
    val speedRange = acoustic.speedSliderRange
    val showPitch = AcousticMapper.supportsNativePitch(activeProvider.type, activeProvider.voiceId)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("听书控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "长按正文任意段落可从该处直接起读；音色与语速更改将对后续段落即时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InkPaperCard(padding = 14.dp) {
                Text("切换发声引擎", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    providers.filter { it.enabled }.forEach { item ->
                        FilterChip(
                            selected = item.id == activeProvider.id,
                            onClick = { onSelectProvider(item.id) },
                            label = { Text(item.name) }
                        )
                    }
                }
            }

            InkPaperCard(padding = 14.dp) {
                Text("语速 ${"%.2f".format(activeProvider.speed)}×", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = activeProvider.speed.coerceIn(speedRange.start, speedRange.endInclusive),
                    onValueChange = onSpeedChange,
                    valueRange = speedRange
                )
                Text(
                    text = acoustic.speedModeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showPitch) {
                    acoustic.pitchRange?.let { pr ->
                        val safePitch = activeProvider.pitch.coerceIn(pr.start, pr.endInclusive)
                        Text("音调 ${"%.2f".format(safePitch)}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = safePitch, onValueChange = onPitchChange, valueRange = pr)
                    }
                }
            }

            InkPaperCard(padding = 14.dp) {
                Text(
                    text = if (sleepActive) "睡眠定时 (${sleepRemainingSec / 60}m${sleepRemainingSec % 60}s)" else "睡眠定时",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "关闭", 15 to "15分", 30 to "30分", 60 to "60分").forEach { (mins, label) ->
                        FilterChip(
                            selected = (mins == 0 && !sleepActive),
                            onClick = { onSetSleepTimer(mins) },
                            label = { Text(label) }
                        )
                    }
                }
                ToggleRow("悬浮实时字幕", floatingSubtitleEnabled, onToggleSubtitle)
            }

            if (isPlayingOrSynthesizing) {
                OutlinedButton(onClick = onStopTts, modifier = Modifier.fillMaxWidth()) {
                    Text("停止当前听书")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
