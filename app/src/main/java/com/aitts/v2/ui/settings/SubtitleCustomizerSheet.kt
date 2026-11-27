package com.aitts.v2.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleCustomizerSheet(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val isSysDark = isSystemInDarkTheme()
    val bgBase = if (isSysDark) Color(0xFF181614) else Color(0xFFFDFBF7)
    val fgColor = if (isSysDark) Color(0xFFEFEAE0) else Color(0xFF1B1917)
    val alpha = settings.floatingSubtitleOpacity.coerceIn(0f, 1f)
    val shadow = Shadow(
        color = if (isSysDark) Color(0xCC000000) else Color(0x99FFFFFF),
        offset = Offset(0f, 2f),
        blurRadius = 5f
    )

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("极简悬浮字幕调节", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bgBase.copy(alpha = alpha),
                border = BorderStroke(1.dp, fgColor.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "“风雪夜归，纸墨生香。”跟随系统明暗的实时悬浮字幕预览。",
                    style = TextStyle(
                        color = fgColor,
                        fontSize = settings.floatingSubtitleFontSize.coerceIn(11, 26).sp,
                        textAlign = if (settings.floatingSubtitleAlignment == SubtitleAlignment.CENTER) {
                            TextAlign.Center
                        } else {
                            TextAlign.Start
                        },
                        shadow = shadow
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            Text("背景不透明度 ${(alpha * 100).toInt()}%")
            Slider(value = alpha, onValueChange = { v -> onUpdate { it.copy(floatingSubtitleOpacity = v) } }, valueRange = 0f..1f)

            Text("字体大小 ${settings.floatingSubtitleFontSize} sp")
            Slider(
                value = settings.floatingSubtitleFontSize.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(floatingSubtitleFontSize = v.toInt()) } },
                valueRange = 11f..26f
            )

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
                Text("锁定位置 (防止翻页误触)")
                Switch(
                    checked = settings.floatingSubtitleLockPosition,
                    onCheckedChange = { c -> onUpdate { it.copy(floatingSubtitleLockPosition = c) } }
                )
            }
        }
    }
}
