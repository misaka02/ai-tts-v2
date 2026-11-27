package com.aitts.v2.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings

data class ResolvedReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val highlightBg: Color,
    val accent: Color
)

@Composable
fun rememberReaderColors(settings: ReaderSettings): ResolvedReaderColors {
    val scheme = MaterialTheme.colorScheme
    return remember(settings.paperTheme, settings.customBgHex, settings.customTextHex, scheme) {
        if (settings.customBgHex != 0L && settings.customTextHex != 0L) {
            val bg = Color(settings.customBgHex)
            val fg = Color(settings.customTextHex)
            val isDark = bg.luminance() < 0.45f
            ResolvedReaderColors(
                background = bg,
                text = fg,
                secondaryText = fg.copy(alpha = 0.65f),
                highlightBg = if (isDark) Color(0x336AA0D4L) else Color(0x1F26537CL),
                accent = scheme.primary
            )
        } else if (settings.paperTheme == ReaderPaperTheme.FOLLOW_APP) {
            ResolvedReaderColors(
                background = scheme.background,
                text = scheme.onBackground,
                secondaryText = scheme.onSurfaceVariant,
                highlightBg = scheme.primary.copy(alpha = 0.14f),
                accent = scheme.primary
            )
        } else {
            ResolvedReaderColors(
                background = Color(settings.paperTheme.bgHex),
                text = Color(settings.paperTheme.textHex),
                secondaryText = Color(settings.paperTheme.secondaryTextHex),
                highlightBg = Color(settings.paperTheme.highlightHex),
                accent = scheme.primary
            )
        }
    }
}
