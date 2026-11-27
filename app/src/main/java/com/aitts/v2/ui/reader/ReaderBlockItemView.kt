package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderSettings

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
    onLongPress: () -> Unit,
    loadImage: suspend (String) -> Bitmap?
) {
    val activeBg = if (isActive) colors.highlightBg else Color.Transparent
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(activeBg)
        .pointerInput(block.index, block.speakableText != null) {
            detectTapGestures(
                onTap = { pos ->
                    val frac = if (size.width > 0) pos.x / size.width else 0.5f
                    onTap(frac)
                },
                onLongPress = {
                    if (block.speakableText != null) onLongPress()
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
                    fontWeight = FontWeight.Bold,
                    fontSize = (settings.fontSizeSp * scale).sp,
                    lineHeight = (settings.fontSizeSp * scale * 1.38f).sp,
                    color = colors.text
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
                    fontStyle = if (block.isQuote) FontStyle.Italic else FontStyle.Normal,
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    color = if (block.isQuote) colors.secondaryText else colors.text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is ReaderBlock.Image -> {
            var bmp by remember(block.resourcePath) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(block.resourcePath) { bmp = loadImage(block.resourcePath) }
            val loaded = bmp
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxImageHeightDp.dp)
                                .clip(RoundedCornerShape(8.dp))
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
