package com.aitts.v2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.SubtitleAlignment
import com.aitts.v2.model.SubtitleNovelPreset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleCustomizerSheet(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("悬浮字幕样式定制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text("阅读底色预设", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleNovelPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.floatingSubtitleNovelPreset == preset,
                        onClick = { onUpdate { it.copy(floatingSubtitleNovelPreset = preset) } },
                        label = { Text(preset.displayName) }
                    )
                }
            }

            Text("背景不透明度 ${(settings.floatingSubtitleOpacity * 100).toInt()}%")
            Slider(
                value = settings.floatingSubtitleOpacity,
                onValueChange = { v -> onUpdate { it.copy(floatingSubtitleOpacity = v) } },
                valueRange = 0f..1f
            )

            Text("字体大小 ${settings.floatingSubtitleFontSize} sp")
            Slider(
                value = settings.floatingSubtitleFontSize.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(floatingSubtitleFontSize = v.toInt()) } },
                valueRange = 11f..24f
            )

            Text("折叠最大行数 ${settings.floatingSubtitleMaxLines} 行")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5).forEach { lines ->
                    FilterChip(
                        selected = settings.floatingSubtitleMaxLines == lines,
                        onClick = { onUpdate { it.copy(floatingSubtitleMaxLines = lines) } },
                        label = { Text("${lines}行") }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.floatingSubtitleAlignment == SubtitleAlignment.LEFT,
                    onClick = { onUpdate { it.copy(floatingSubtitleAlignment = SubtitleAlignment.LEFT) } },
                    label = { Text("居左段落") }
                )
                FilterChip(
                    selected = settings.floatingSubtitleAlignment == SubtitleAlignment.CENTER,
                    onClick = { onUpdate { it.copy(floatingSubtitleAlignment = SubtitleAlignment.CENTER) } },
                    label = { Text("居中字幕") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("高反差抗干扰文字轮廓")
                Switch(
                    checked = settings.floatingSubtitleAdaptiveContrast,
                    onCheckedChange = { c -> onUpdate { it.copy(floatingSubtitleAdaptiveContrast = c) } }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("锁定位置 (防止翻页误触)")
                Switch(
                    checked = settings.floatingSubtitleLockPosition,
                    onCheckedChange = { c -> onUpdate { it.copy(floatingSubtitleLockPosition = c) } }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示左侧状态图标")
                Switch(
                    checked = settings.floatingSubtitleShowIcon,
                    onCheckedChange = { c -> onUpdate { it.copy(floatingSubtitleShowIcon = c) } }
                )
            }
        }
    }
}
