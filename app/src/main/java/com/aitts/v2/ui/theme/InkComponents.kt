package com.aitts.v2.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private data class PillColors(val dot: Color, val bg: Color, val fg: Color)

@Composable
fun InkPaperCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    padding: Dp = 16.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val clickMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, borderColor),
        modifier = clickMod
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun InkSectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InkStatusPill(
    label: String,
    tone: PillTone = PillTone.NEUTRAL,
    onClick: (() -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val successColor = if (isDark) Color(0xFF6DD5A0) else Color(0xFF1F5C45)
    val warningColor = if (isDark) Color(0xFFF4A968) else Color(0xFFA8551E)
    val colors = when (tone) {
        PillTone.ACCENT -> PillColors(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.11f),
            if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
        )
        PillTone.SUCCESS -> PillColors(successColor, successColor.copy(alpha = if (isDark) 0.18f else 0.12f), successColor)
        PillTone.WARNING -> PillColors(warningColor, warningColor.copy(alpha = if (isDark) 0.18f else 0.12f), warningColor)
        PillTone.NEUTRAL -> PillColors(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val mod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(shape = CircleShape, color = colors.bg, modifier = mod) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).background(colors.dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.fg)
        }
    }
}

@Composable
fun InkWaveformCanvas(
    rms: Float,
    isPlaying: Boolean,
    isSynthesizing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val active = isPlaying || isSynthesizing
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "ink_wave")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (isPlaying) 900 else 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_phase"
        )
        animated
    } else {
        0f
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier.fillMaxWidth().height(26.dp)) {
        val barCount = 24
        val step = size.width / barCount
        val barWidth = (step * 0.44f).coerceAtLeast(2.dp.toPx())
        val centerY = size.height / 2f
        val energy = when {
            isPlaying -> rms.coerceIn(0.18f, 1.0f)
            isSynthesizing -> 0.35f
            else -> 0.10f
        }

        for (i in 0 until barCount) {
            val wave = if (active) {
                abs(sin(phase + i * 0.45f)).coerceIn(0.18f, 1.0f)
            } else {
                0.22f + 0.14f * abs(sin(i * 0.65f))
            }
            val h = (size.height * energy * wave).coerceIn(3.dp.toPx(), size.height)
            val x = i * step + (step - barWidth) / 2f
            drawRoundRect(
                color = if (active) color.copy(alpha = 0.45f + 0.55f * wave) else trackColor,
                topLeft = Offset(x, centerY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
