package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderImageFilter
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTextAlign

/**
 * Shared block renderer used identically by both horizontal paged and vertical scroll viewports.
 */
@Composable
fun ReaderBlockItemView(
    block: ReaderBlock,
    displayText: String,
    isActive: Boolean,
    settings: ReaderSettings,
    fontFamily: FontFamily,
    colors: ResolvedReaderColors,
    maxImageHeightDp: Int,
    onTap: (fractionX: Float) -> Unit,
    onLongPress: (String) -> Unit,
    loadImage: suspend (String) -> Bitmap?,
    onImageClick: (String, Bitmap) -> Unit = { _, _ -> }
) {
    val activeBg = if (isActive) colors.highlightBg else Color.Transparent
    val align = when (settings.textAlign) {
        ReaderTextAlign.JUSTIFY -> TextAlign.Justify
        ReaderTextAlign.CENTER -> TextAlign.Center
        ReaderTextAlign.START, ReaderTextAlign.AUTO -> TextAlign.Start
    }
    val baseWeight = FontWeight(settings.fontWeight.coerceIn(300, 700))
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(activeBg)
        .pointerInput(block.index, block.speakableText) {
            detectTapGestures(
                onTap = { pos ->
                    val frac = if (size.width > 0) pos.x / size.width else 0.5f
                    onTap(frac)
                },
                onLongPress = {
                    val txt = block.speakableText
                    if (!txt.isNullOrBlank()) onLongPress(txt)
                }
            )
        }
        .padding(horizontal = if (isActive) 8.dp else 0.dp, vertical = if (isActive) 4.dp else 0.dp)

    when (block) {
        is ReaderBlock.Heading -> {
            val scale = when (block.level) {
                1 -> 1.32f
                2 -> 1.18f
                else -> 1.08f
            }
            Row(modifier = rowModifier.height(IntrinsicSize.Min)) {
                if (isActive) ReaderAccentBar(colors.accent)
                Text(
                    text = displayText,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight((settings.fontWeight + 200).coerceIn(600, 900)),
                    fontSize = (settings.fontSizeSp * scale).sp,
                    lineHeight = (settings.fontSizeSp * scale * 1.38f).sp,
                    textAlign = align,
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is ReaderBlock.Paragraph -> {
            Row(modifier = rowModifier.height(IntrinsicSize.Min)) {
                if (isActive) {
                    ReaderAccentBar(colors.accent)
                } else if (block.isQuote) {
                    ReaderAccentBar(colors.secondaryText.copy(alpha = 0.45f))
                }
                Text(
                    text = displayText,
                    fontFamily = fontFamily,
                    fontWeight = baseWeight,
                    fontStyle = if (block.isQuote) FontStyle.Italic else FontStyle.Normal,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    letterSpacing = settings.letterSpacingEm.em,
                    textAlign = align,
                    color = if (block.isQuote) colors.secondaryText else colors.text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is ReaderBlock.Image -> {
            var bmp by remember(block.resourcePath) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(block.resourcePath) { bmp = loadImage(block.resourcePath) }
            val loaded = bmp
            val imgFilter = remember(settings.nightImageFilter) { resolveImageColorFilter(settings.nightImageFilter) }
            if (loaded != null || block.altText.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (loaded != null) {
                        Image(
                            bitmap = loaded.asImageBitmap(),
                            contentDescription = block.altText,
                            contentScale = ContentScale.Fit,
                            colorFilter = imgFilter,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxImageHeightDp.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(block.resourcePath, loaded) }
                        )
                    }
                    if (block.altText.isNotBlank()) {
                        Text(text = block.altText, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    }
                }
            }
        }
        is ReaderBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = colors.secondaryText.copy(alpha = 0.28f)
        )
    }
}

private fun resolveImageColorFilter(filter: ReaderImageFilter): ColorFilter? = when (filter) {
    ReaderImageFilter.NONE -> null
    ReaderImageFilter.DARKEN -> ColorFilter.colorMatrix(
        ColorMatrix().apply { setToScale(0.75f, 0.75f, 0.75f, 1f) }
    )
    ReaderImageFilter.INVERT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

@Composable
fun rememberResolvedReaderFontFamily(settings: ReaderSettings): FontFamily {
    val path = settings.customFontPath
    return remember(path, settings.fontFamily) {
        if (path.isNotBlank()) {
            runCatching {
                val file = java.io.File(path)
                if (file.exists()) FontFamily(android.graphics.Typeface.createFromFile(file)) else null
            }.getOrNull()
        } else {
            null
        } ?: when (settings.fontFamily) {
            ReaderFontFamily.SERIF -> FontFamily.Serif
            ReaderFontFamily.SANS -> FontFamily.SansSerif
            ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
        }
    }
}

@Composable
private fun ReaderAccentBar(color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
            .width(3.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}
