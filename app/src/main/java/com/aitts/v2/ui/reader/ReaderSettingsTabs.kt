package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ReaderColumnCount
import com.aitts.v2.reader.model.ReaderDirection
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderImageFilter
import com.aitts.v2.reader.model.ReaderOrientationLock
import com.aitts.v2.reader.model.ReaderPageAnimation
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderScriptConvert
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTapZoneMode
import com.aitts.v2.reader.model.ReaderTextAlign
import com.aitts.v2.ui.theme.InkPaperCard
import java.io.File

@Composable
fun ReaderAppearanceTab(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InkPaperCard(padding = 12.dp) {
            val followSys = settings.brightness < 0f
            SettingToggleRow("跟随系统亮度", followSys) { follow ->
                onUpdate { it.copy(brightness = if (follow) -1f else 0.6f) }
            }
            if (!followSys) {
                StepperSliderRow(
                    label = "屏幕亮度",
                    valueText = "${(settings.brightness * 100).toInt()}%",
                    value = settings.brightness,
                    valueRange = 0.05f..1f,
                    step = 0.05f,
                    onValueChange = { v -> onUpdate { it.copy(brightness = v) } }
                )
            }
        }
        InkPaperCard(padding = 12.dp) {
            ThemeSwatchGrid(settings = settings, onUpdate = onUpdate)
        }
        InkPaperCard(padding = 12.dp) {
            SettingChipGroup(
                label = "插图护眼滤镜 (所有主题生效)",
                items = ReaderImageFilter.entries,
                selected = settings.nightImageFilter,
                itemLabel = { it.displayName },
                onSelect = { filter -> onUpdate { it.copy(nightImageFilter = filter) } }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderFontTab(
    settings: ReaderSettings,
    isEpub: Boolean,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onPickCustomFont: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InkPaperCard(padding = 12.dp) {
            StepperSliderRow(
                label = "正文大小",
                valueText = "${settings.fontSizeSp.toInt()} sp",
                value = settings.fontSizeSp,
                valueRange = 12f..32f,
                step = 1f,
                onValueChange = { v -> onUpdate { it.copy(fontSizeSp = snapStep(v, 1f)) } }
            )
            val weightLabel = when (settings.fontWeight) {
                300 -> "细体 (300)"
                500 -> "中等 (500)"
                600 -> "半粗 (600)"
                700 -> "粗体 (700)"
                else -> "常规 (400)"
            }
            StepperSliderRow(
                label = "字体粗细",
                valueText = weightLabel,
                value = settings.fontWeight.toFloat(),
                valueRange = 300f..700f,
                step = 100f,
                onValueChange = { v -> onUpdate { it.copy(fontWeight = snapStep(v, 100f).toInt().coerceIn(300, 700)) } }
            )
        }

        InkPaperCard(padding = 12.dp) {
            Text("字体选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReaderFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = settings.fontFamily == family && settings.customFontPath.isBlank(),
                        onClick = { onUpdate { it.copy(fontFamily = family, customFontPath = "") } },
                        label = { Text(family.displayName) }
                    )
                }
            }
            val customName = if (settings.customFontPath.isNotBlank()) File(settings.customFontPath).name else ""
            if (customName.isNotBlank()) {
                Text("当前自定义字体：$customName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickCustomFont) {
                    Text(if (customName.isBlank()) "导入 TTF/OTF 字体" else "更换自定义字体")
                }
                if (customName.isNotBlank()) {
                    OutlinedButton(onClick = { onUpdate { it.copy(customFontPath = "") } }) { Text("清除字体") }
                }
            }
            SettingChipGroup(
                label = "中文简繁转换",
                items = ReaderScriptConvert.entries,
                selected = settings.scriptConvert,
                itemLabel = { it.displayName },
                onSelect = { conv -> onUpdate { it.copy(scriptConvert = conv) } }
            )
            if (isEpub) {
                SettingToggleRow(
                    label = "保留 EPUB 出版商原样式",
                    checked = settings.publisherStyles,
                    subtitle = "开启后优先保留原书行距、缩进与对齐"
                ) { on -> onUpdate { it.copy(publisherStyles = on) } }
            }
        }
    }
}

@Composable
fun ReaderLayoutTab(
    settings: ReaderSettings,
    isEpub: Boolean,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InkPaperCard(padding = 12.dp) {
            StepperSliderRow(
                label = "行距",
                valueText = "${"%.2f".format(settings.lineHeightMultiplier)}×",
                value = settings.lineHeightMultiplier,
                valueRange = 1.1f..2.5f,
                step = 0.05f,
                onValueChange = { v -> onUpdate { it.copy(lineHeightMultiplier = snapStep(v, 0.05f)) } }
            )
            StepperSliderRow(
                label = "段间距",
                valueText = "${settings.paragraphSpacingDp.toInt()} dp",
                value = settings.paragraphSpacingDp,
                valueRange = 0f..32f,
                step = 2f,
                onValueChange = { v -> onUpdate { it.copy(paragraphSpacingDp = snapStep(v, 2f)) } }
            )
            StepperSliderRow(
                label = "字间距",
                valueText = "${"%.2f".format(settings.letterSpacingEm)} em",
                value = settings.letterSpacingEm,
                valueRange = 0f..0.30f,
                step = 0.02f,
                onValueChange = { v -> onUpdate { it.copy(letterSpacingEm = snapStep(v, 0.02f)) } }
            )
            if (isEpub) {
                StepperSliderRow(
                    label = "英文词间距",
                    valueText = "${"%.2f".format(settings.wordSpacingEm)} em",
                    value = settings.wordSpacingEm,
                    valueRange = 0f..0.50f,
                    step = 0.05f,
                    onValueChange = { v -> onUpdate { it.copy(wordSpacingEm = snapStep(v, 0.05f)) } }
                )
            }
        }

        InkPaperCard(padding = 12.dp) {
            StepperSliderRow(
                label = "左右页边距",
                valueText = "${settings.horizontalPaddingDp.toInt()} dp",
                value = settings.horizontalPaddingDp,
                valueRange = 8f..44f,
                step = 2f,
                onValueChange = { v -> onUpdate { it.copy(horizontalPaddingDp = snapStep(v, 2f)) } }
            )
            StepperSliderRow(
                label = "上下页边距",
                valueText = "${settings.verticalPaddingDp.toInt()} dp",
                value = settings.verticalPaddingDp,
                valueRange = 4f..40f,
                step = 2f,
                onValueChange = { v -> onUpdate { it.copy(verticalPaddingDp = snapStep(v, 2f)) } }
            )
            val indentOptions = listOf(0f to "无缩进", 1f to "缩进 1 格", 2f to "缩进 2 格")
            val curIndent = indentOptions.minBy { kotlin.math.abs(it.first - settings.effectiveIndentEm()) }
            SettingChipGroup(
                label = "段落首行缩进",
                items = indentOptions,
                selected = curIndent,
                itemLabel = { it.second },
                onSelect = { (em, _) -> onUpdate { it.copy(paragraphIndentEm = em, firstLineIndent = em > 0f) } }
            )
            SettingChipGroup(
                label = "对齐方式",
                items = ReaderTextAlign.entries,
                selected = settings.textAlign,
                itemLabel = { it.displayName },
                onSelect = { align -> onUpdate { it.copy(textAlign = align) } }
            )
        }

        if (isEpub) {
            InkPaperCard(padding = 12.dp) {
                SettingChipGroup(
                    label = "分栏模式 (横屏/平板宽屏生效)",
                    items = ReaderColumnCount.entries,
                    selected = settings.columnCount,
                    itemLabel = { it.displayName },
                    onSelect = { col -> onUpdate { it.copy(columnCount = col) } }
                )
                SettingChipGroup(
                    label = "阅读方向",
                    items = ReaderDirection.entries,
                    selected = settings.readingDirection,
                    itemLabel = { it.displayName },
                    onSelect = { dir -> onUpdate { it.copy(readingDirection = dir) } }
                )
                SettingToggleRow("竖排排版 (右起竖行)", settings.verticalText) { on ->
                    onUpdate { it.copy(verticalText = on) }
                }
                SettingToggleRow("英文单词自动断字", settings.hyphens) { on ->
                    onUpdate { it.copy(hyphens = on) }
                }
            }
        }
    }
}

@Composable
fun ReaderInteractionTab(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InkPaperCard(padding = 12.dp) {
            SettingChipGroup(
                label = "翻页方式",
                items = ReaderReadingMode.entries,
                selected = settings.readingMode,
                itemLabel = { it.displayName },
                onSelect = { mode -> onUpdate { it.copy(readingMode = mode) } }
            )
            if (settings.readingMode == ReaderReadingMode.PAGED) {
                SettingChipGroup(
                    label = "翻页动效",
                    items = ReaderPageAnimation.entries,
                    selected = settings.pageAnimation,
                    itemLabel = { it.displayName },
                    onSelect = { anim -> onUpdate { it.copy(pageAnimation = anim) } }
                )
                SettingChipGroup(
                    label = "屏幕点击区域",
                    items = ReaderTapZoneMode.entries,
                    selected = settings.tapZoneMode,
                    itemLabel = { it.displayName },
                    onSelect = { z -> onUpdate { it.copy(tapZoneMode = z) } }
                )
                SettingToggleRow("音量键翻页", settings.volumeKeyPaging) { on ->
                    onUpdate { it.copy(volumeKeyPaging = on) }
                }
                if (settings.volumeKeyPaging) {
                    SettingToggleRow("反转音量键方向 (上进下退)", settings.invertVolumeKeys) { on ->
                        onUpdate { it.copy(invertVolumeKeys = on) }
                    }
                }
            }
        }

        InkPaperCard(padding = 12.dp) {
            SettingChipGroup(
                label = "屏幕方向锁定",
                items = ReaderOrientationLock.entries,
                selected = settings.orientationLock,
                itemLabel = { it.displayName },
                onSelect = { lock -> onUpdate { it.copy(orientationLock = lock) } }
            )
            SettingToggleRow("左侧边缘上下滑调节亮度", settings.leftEdgeBrightnessGesture) { on ->
                onUpdate { it.copy(leftEdgeBrightnessGesture = on) }
            }
            SettingToggleRow("阅读时屏幕常亮", settings.keepScreenOn) { on -> onUpdate { it.copy(keepScreenOn = on) } }
            SettingToggleRow("全屏沉浸隐藏系统栏", settings.fullscreenImmersive) { on ->
                onUpdate { it.copy(fullscreenImmersive = on) }
            }
            SettingToggleRow("显示顶部章节名栏", settings.showHeaderBar) { on ->
                onUpdate { it.copy(showHeaderBar = on) }
            }
            SettingToggleRow("显示底部页码进度栏", settings.showFooterBar) { on ->
                onUpdate { it.copy(showFooterBar = on) }
            }
            if (settings.showFooterBar) {
                SettingToggleRow("在页脚显示时间与电量", settings.showBatteryTime) { on ->
                    onUpdate { it.copy(showBatteryTime = on) }
                }
            }
        }
    }
}
