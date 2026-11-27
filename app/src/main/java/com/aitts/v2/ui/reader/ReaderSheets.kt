package com.aitts.v2.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderReadingMode
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTextAlign
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    isEpub: Boolean,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImportFont: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFont)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("阅读器设置与排版", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            InkPaperCard(padding = 14.dp) {
                val followSysBrightness = settings.brightness < 0f
                ToggleRow("跟随系统亮度", followSysBrightness) { follow ->
                    onUpdate { it.copy(brightness = if (follow) -1f else 0.6f) }
                }
                if (!followSysBrightness) {
                    Text("屏幕亮度 ${(settings.brightness * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = settings.brightness.coerceIn(0.05f, 1f),
                        onValueChange = { v -> onUpdate { it.copy(brightness = v) } },
                        valueRange = 0.05f..1f
                    )
                }
                ToggleRow("阅读时屏幕常亮", settings.keepScreenOn) { on -> onUpdate { it.copy(keepScreenOn = on) } }
                ToggleRow("全屏沉浸隐藏状态栏", settings.fullscreenImmersive) { on ->
                    onUpdate { it.copy(fullscreenImmersive = on) }
                }
            }

            InkPaperCard(padding = 14.dp) {
                Text("翻页与纸张主题", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderReadingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.readingMode == mode,
                            onClick = { onUpdate { it.copy(readingMode = mode) } },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
                if (settings.readingMode == ReaderReadingMode.PAGED) {
                    ToggleRow("音量键翻页", settings.volumeKeyPaging) { on -> onUpdate { it.copy(volumeKeyPaging = on) } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReaderPaperTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.paperTheme == theme,
                            onClick = { onUpdate { it.copy(paperTheme = theme) } },
                            label = { Text(theme.displayName) }
                        )
                    }
                }
                if (isEpub) {
                    ToggleRow("保留 EPUB 出版商原排版样式", settings.publisherStyles) { on ->
                        onUpdate { it.copy(publisherStyles = on) }
                    }
                }
            }

            InkPaperCard(padding = 14.dp) {
                Text("字体与对齐", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFontFamily.entries.forEach { family ->
                        FilterChip(
                            selected = settings.fontFamily == family && settings.customFontPath.isBlank(),
                            onClick = { onUpdate { it.copy(fontFamily = family, customFontPath = "") } },
                            label = { Text(family.displayName) }
                        )
                    }
                    ReaderTextAlign.entries.forEach { align ->
                        FilterChip(
                            selected = settings.textAlign == align,
                            onClick = { onUpdate { it.copy(textAlign = align) } },
                            label = { Text(align.displayName) }
                        )
                    }
                }
                if (!isEpub) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { fontPicker.launch(arrayOf("font/*", "*/*")) }) {
                            Text(if (settings.customFontPath.isBlank()) "导入 TTF/OTF 字体" else "更换自定义字体")
                        }
                        if (settings.customFontPath.isNotBlank()) {
                            OutlinedButton(onClick = { onUpdate { it.copy(customFontPath = "") } }) { Text("清除字体") }
                        }
                    }
                }
                Text("字号 ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.fontSizeSp.coerceIn(14f, 30f),
                    onValueChange = { v -> onUpdate { it.copy(fontSizeSp = v) } },
                    valueRange = 14f..30f
                )
                Text("行距 ${"%.2f".format(settings.lineHeightMultiplier)}×", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.lineHeightMultiplier.coerceIn(1.2f, 2.3f),
                    onValueChange = { v -> onUpdate { it.copy(lineHeightMultiplier = v) } },
                    valueRange = 1.2f..2.3f
                )
                Text(
                    text = "段间距 ${settings.paragraphSpacingDp.toInt()} dp · 左右边距 ${settings.horizontalPaddingDp.toInt()} dp",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.paragraphSpacingDp.coerceIn(4f, 28f),
                    onValueChange = { v -> onUpdate { it.copy(paragraphSpacingDp = v) } },
                    valueRange = 4f..28f
                )
                Slider(
                    value = settings.horizontalPaddingDp.coerceIn(8f, 36f),
                    onValueChange = { v -> onUpdate { it.copy(horizontalPaddingDp = v) } },
                    valueRange = 8f..36f
                )
            }

            InkPaperCard(padding = 14.dp) {
                ToggleRow("中文首行两格缩进", settings.firstLineIndent) { on -> onUpdate { it.copy(firstLineIndent = on) } }
                ToggleRow("听书自动滚屏/翻页高亮", settings.autoScrollOnTts) { on -> onUpdate { it.copy(autoScrollOnTts = on) } }
                ToggleRow("章节结束自动连播下一章", settings.autoNextChapterOnTts) { on ->
                    onUpdate { it.copy(autoNextChapterOnTts = on) }
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
