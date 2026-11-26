package com.aitts.v2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aitts.v2.model.ThemeMode
import com.aitts.v2.model.ThemePalette

@Composable
fun AiTtsTheme(
    themeMode: ThemeMode,
    palette: ThemePalette,
    amoledPureBlack: Boolean,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val primary = Color(palette.seedHex)
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.22f),
            onPrimaryContainer = Color(0xFFE8EEF9),
            background = if (amoledPureBlack) Color.Black else Color(0xFF111318),
            surface = if (amoledPureBlack) Color.Black else Color(0xFF171A21),
            surfaceContainer = if (amoledPureBlack) Color(0xFF0A0A0C) else Color(0xFF1F232C),
            surfaceContainerHigh = if (amoledPureBlack) Color(0xFF121316) else Color(0xFF282D38)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.14f),
            onPrimaryContainer = Color(0xFF0F172A),
            background = Color(0xFFF7F9FC),
            surface = Color.White,
            surfaceContainer = Color(0xFFF0F4F9),
            surfaceContainerHigh = Color(0xFFE6ECF5)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
