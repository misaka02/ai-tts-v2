package com.aitts.v2.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import kotlin.math.roundToInt

@Composable
fun StepperSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    val minVal = valueRange.start
    val maxVal = valueRange.endInclusive
    var isDragging by remember { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(value.coerceIn(minVal, maxVal)) }
    var lastEmitMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(value, isDragging) {
        if (!isDragging) draft = value.coerceIn(minVal, maxVal)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = {
                    val next = (draft - step).coerceIn(minVal, maxVal)
                    isDragging = false
                    draft = next
                    onValueChange(next)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "减少 $label", modifier = Modifier.size(16.dp))
            }
            Slider(
                value = draft,
                onValueChange = { raw ->
                    isDragging = true
                    draft = raw
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 95L) {
                        lastEmitMs = now
                        onValueChange(raw)
                    }
                },
                onValueChangeFinished = {
                    isDragging = false
                    onValueChange(draft)
                },
                valueRange = valueRange,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val next = (draft + step).coerceIn(minVal, maxVal)
                    isDragging = false
                    draft = next
                    onValueChange(next)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "增加 $label", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChipGroup(
    label: String,
    items: List<T>,
    selected: T,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                FilterChip(
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    label = { Text(itemLabel(item)) }
                )
            }
        }
    }
}

private data class CustomSwatchPreset(val label: String, val bgHex: Long, val textHex: Long)

private val CURATED_SWATCHES = listOf(
    CustomSwatchPreset("暖杏茶", 0xFFF3E6D0L, 0xFF33261BL),
    CustomSwatchPreset("豆沙绿", 0xFFCDE5D0L, 0xFF1A2D1FL),
    CustomSwatchPreset("墨绿林", 0xFF16241DL, 0xFFD2E2D8L),
    CustomSwatchPreset("深海夜", 0xFF111B27L, 0xFFD4E0EDL)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSwatchGrid(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("纸张与配色主题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderPaperTheme.entries.forEach { theme ->
                val isSelected = settings.customBgHex == 0L && settings.paperTheme == theme
                val bg = if (theme == ReaderPaperTheme.FOLLOW_APP) MaterialTheme.colorScheme.surface else Color(theme.bgHex)
                val fg = if (theme == ReaderPaperTheme.FOLLOW_APP) MaterialTheme.colorScheme.onSurface else Color(theme.textHex)
                SwatchTile(theme.displayName, bg, fg, isSelected) {
                    onUpdate { it.copy(paperTheme = theme, customBgHex = 0L, customTextHex = 0L) }
                }
            }
            CURATED_SWATCHES.forEach { preset ->
                val isSelected = settings.customBgHex == preset.bgHex
                SwatchTile(preset.label, Color(preset.bgHex), Color(preset.textHex), isSelected) {
                    onUpdate { it.copy(customBgHex = preset.bgHex, customTextHex = preset.textHex) }
                }
            }
        }
    }
}

@Composable
private fun SwatchTile(
    title: String,
    bg: Color,
    fg: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else 1.dp
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aa", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg)
        Text(title, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.85f))
    }
}

internal fun snapStep(value: Float, step: Float): Float = (value / step).roundToInt() * step
