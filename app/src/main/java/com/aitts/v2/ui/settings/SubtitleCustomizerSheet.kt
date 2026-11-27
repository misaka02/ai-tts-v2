package com.aitts.v2.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("悬浮字幕实时样式台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            SubtitleLivePreviewCard(settings)

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

            ToggleRow("高反差抗干扰文字轮廓", settings.floatingSubtitleAdaptiveContrast) { c ->
                onUpdate { it.copy(floatingSubtitleAdaptiveContrast = c) }
            }
            ToggleRow("锁定位置 (防止翻页误触)", settings.floatingSubtitleLockPosition) { c ->
                onUpdate { it.copy(floatingSubtitleLockPosition = c) }
            }
            ToggleRow("显示左侧状态图标", settings.floatingSubtitleShowIcon) { c ->
                onUpdate { it.copy(floatingSubtitleShowIcon = c) }
            }
        }
    }
}

@Composable
private fun SubtitleLivePreviewCard(settings: GlobalSettings) {
    val isSysDark = isSystemInDarkTheme()
    val effectiveDark = when (settings.floatingSubtitleNovelPreset) {
        SubtitleNovelPreset.AUTO -> isSysDark
        SubtitleNovelPreset.NIGHT -> true
        else -> false
    }
    val (bgColor, fgColor) = when (settings.floatingSubtitleNovelPreset) {
        SubtitleNovelPreset.AUTO ->
            if (isSysDark) Color(0xFF181A20) to Color(0xFFF5F5F7) else Color.White to Color(0xFF0F172A)
        else -> Color(settings.floatingSubtitleNovelPreset.bgHex) to Color(settings.floatingSubtitleNovelPreset.textHex)
    }
    val alpha = settings.floatingSubtitleOpacity.coerceIn(0f, 1f)
    val shadow = if (alpha <= 0.05f || settings.floatingSubtitleAdaptiveContrast) {
        val shadowCol = if (effectiveDark) Color(0xCC000000) else Color(0x99FFFFFF)
        Shadow(color = shadowCol, offset = Offset(0f, 2f), blurRadius = 6f)
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor.copy(alpha = alpha),
        border = BorderStroke(1.dp, fgColor.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.floatingSubtitleShowIcon) {
                Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = fgColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = "“风雪夜归，纸墨生香。”这是当前样式下的悬浮小说字幕实时预览效果。",
                style = TextStyle(
                    color = fgColor,
                    fontSize = settings.floatingSubtitleFontSize.coerceIn(11, 24).sp,
                    textAlign = if (settings.floatingSubtitleAlignment == SubtitleAlignment.CENTER) TextAlign.Center else TextAlign.Start,
                    shadow = shadow
                ),
                maxLines = settings.floatingSubtitleMaxLines.coerceIn(1, 5),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
