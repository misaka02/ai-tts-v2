package com.aitts.v2.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.aitts.v2.reader.model.ReaderPaperTheme

data class ResolvedReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val highlightBg: Color,
    val accent: Color
)

@Composable
fun rememberReaderColors(paperTheme: ReaderPaperTheme): ResolvedReaderColors {
    val scheme = MaterialTheme.colorScheme
    return remember(paperTheme, scheme) {
        if (paperTheme == ReaderPaperTheme.FOLLOW_APP) {
            ResolvedReaderColors(
                background = scheme.background,
                text = scheme.onBackground,
                secondaryText = scheme.onSurfaceVariant,
                highlightBg = scheme.primary.copy(alpha = 0.14f),
                accent = scheme.primary
            )
        } else {
            ResolvedReaderColors(
                background = Color(paperTheme.bgHex),
                text = Color(paperTheme.textHex),
                secondaryText = Color(paperTheme.secondaryTextHex),
                highlightBg = Color(paperTheme.highlightHex),
                accent = scheme.primary
            )
        }
    }
}
