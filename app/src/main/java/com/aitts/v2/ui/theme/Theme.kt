package com.aitts.v2.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.aitts.v2.model.ThemeMode
import com.aitts.v2.model.ThemePalette

private val InkTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 27.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    )
)

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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    val primary = Color(palette.seedHex)
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFFFDFBF7),
            primaryContainer = primary.copy(alpha = 0.24f),
            onPrimaryContainer = Color(0xFFF2ECE1),
            secondary = Color(0xFF9C9387),
            background = if (amoledPureBlack) Color.Black else Color(0xFF131210),
            onBackground = Color(0xFFEFEAE0),
            surface = if (amoledPureBlack) Color.Black else Color(0xFF1C1A17),
            onSurface = Color(0xFFEFEAE0),
            onSurfaceVariant = Color(0xFF9C9387),
            surfaceContainer = if (amoledPureBlack) Color(0xFF0C0B0A) else Color(0xFF25221E),
            surfaceContainerHigh = if (amoledPureBlack) Color(0xFF151311) else Color(0xFF302C26),
            outlineVariant = if (amoledPureBlack) Color(0xFF2A2622) else Color(0xFF36312B),
            error = Color(0xFFE07A6B)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color(0xFFFDFBF7),
            primaryContainer = primary.copy(alpha = 0.12f),
            onPrimaryContainer = Color(0xFF1B1917),
            secondary = Color(0xFF675F55),
            background = Color(0xFFF6F2EA),
            onBackground = Color(0xFF1B1917),
            surface = Color(0xFFFDFBF7),
            onSurface = Color(0xFF1B1917),
            onSurfaceVariant = Color(0xFF675F55),
            surfaceContainer = Color(0xFFEFE9DD),
            surfaceContainerHigh = Color(0xFFE6DEC8),
            outlineVariant = Color(0xFFDDD4C4),
            error = Color(0xFFB33A2E)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = InkTypography,
        content = content
    )
}
