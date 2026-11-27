package com.aitts.v2.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderSettings

data class PageBlockSlice(
    val blockIndex: Int,
    val block: ReaderBlock,
    val displayText: String,
    val isContinuation: Boolean,
    val startCharOffset: Int = 0,
    val endCharOffset: Int = 0
)

data class ReaderPage(
    val pageIndex: Int,
    val slices: List<PageBlockSlice>
)

/**
 * Pixel-accurate TextMeasurer paginator that packs headings, split paragraphs,
 * quotes, images, and dividers cleanly into fixed viewport pages without line clipping.
 */
object ChapterPaginator {

    fun paginate(
        chapter: ChapterContent,
        settings: ReaderSettings,
        fontFamily: FontFamily,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        textMeasurer: TextMeasurer
    ): List<ReaderPage> {
        if (widthPx <= 0 || heightPx <= 0 || chapter.blocks.isEmpty()) {
            return listOf(ReaderPage(0, emptyList()))
        }
        val state = PaginatorState(
            settings = settings,
            fontFamily = fontFamily,
            widthPx = widthPx,
            heightPx = heightPx,
            spacingPx = with(density) { settings.paragraphSpacingDp.dp.roundToPx() },
            imageMaxHeightPx = minOf((heightPx * 0.40f).toInt(), with(density) { 220.dp.roundToPx() }),
            captionReservePx = with(density) { 28.dp.roundToPx() },
            dividerHeightPx = with(density) { 18.dp.roundToPx() },
            minKeepWithNextPx = with(density) { (settings.fontSizeSp * settings.lineHeightMultiplier * 2f).sp.roundToPx() },
            textMeasurer = textMeasurer
        )
        for (block in chapter.blocks) {
            state.placeBlock(block)
        }
        return state.finish()
    }

    private class PaginatorState(
        private val settings: ReaderSettings,
        private val fontFamily: FontFamily,
        private val widthPx: Int,
        private val heightPx: Int,
        private val spacingPx: Int,
        private val imageMaxHeightPx: Int,
        private val captionReservePx: Int,
        private val dividerHeightPx: Int,
        private val minKeepWithNextPx: Int,
        private val textMeasurer: TextMeasurer
    ) {
        private val pages = mutableListOf<ReaderPage>()
        private val currentSlices = mutableListOf<PageBlockSlice>()
        private var remainingHeight = heightPx

        fun placeBlock(block: ReaderBlock) {
            when (block) {
                is ReaderBlock.Heading -> placeHeading(block)
                is ReaderBlock.Paragraph -> placeParagraph(block)
                is ReaderBlock.Image -> {
                    val captionExtra = if (block.altText.isNotBlank()) captionReservePx else 0
                    placeFixedItem(block, minOf(heightPx, imageMaxHeightPx + captionExtra))
                }
                is ReaderBlock.Divider -> placeFixedItem(block, dividerHeightPx)
            }
        }

        fun finish(): List<ReaderPage> {
            if (currentSlices.isNotEmpty() || pages.isEmpty()) {
                pages.add(ReaderPage(pages.size, currentSlices.toList()))
                currentSlices.clear()
            }
            return pages
        }

        private fun flushPage() {
            if (currentSlices.isNotEmpty()) {
                pages.add(ReaderPage(pages.size, currentSlices.toList()))
                currentSlices.clear()
            }
            remainingHeight = heightPx
        }

        private fun placeFixedItem(block: ReaderBlock, itemHeight: Int) {
            if (itemHeight > remainingHeight && currentSlices.isNotEmpty()) {
                flushPage()
            }
            currentSlices.add(PageBlockSlice(block.index, block, "", false))
            remainingHeight = (remainingHeight - itemHeight - spacingPx).coerceAtLeast(0)
        }

        private fun placeHeading(block: ReaderBlock.Heading) {
            val scale = when (block.level) {
                1 -> 1.32f
                2 -> 1.18f
                else -> 1.08f
            }
            val style = TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight((settings.fontWeight + 200).coerceIn(600, 900)),
                fontSize = (settings.fontSizeSp * scale).sp,
                lineHeight = (settings.fontSizeSp * scale * 1.38f).sp
            )
            val measured = measureText(block.text, style)
            if (measured.size.height + minKeepWithNextPx > remainingHeight && currentSlices.isNotEmpty()) {
                flushPage()
            }
            placeTextSlices(block, block.text, style, prefix = "")
        }

        private fun placeParagraph(block: ReaderBlock.Paragraph) {
            val indentEm = settings.effectiveIndentEm()
            val indentPrefix = when {
                block.isQuote -> ""
                indentEm >= 1.5f -> "\u3000\u3000"
                indentEm >= 0.5f -> "\u3000"
                else -> ""
            }
            val style = TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight(settings.fontWeight.coerceIn(300, 700)),
                fontStyle = if (block.isQuote) FontStyle.Italic else FontStyle.Normal,
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                letterSpacing = settings.letterSpacingEm.em
            )
            placeTextSlices(block, block.text, style, prefix = indentPrefix)
        }

        private fun placeTextSlices(
            block: ReaderBlock,
            rawText: String,
            style: TextStyle,
            prefix: String
        ) {
            var rawOffset = 0
            var isCont = false
            while (rawOffset < rawText.length) {
                val activePrefix = if (isCont) "" else prefix
                val display = activePrefix + rawText.substring(rawOffset)
                val layout = measureText(display, style)
                if (layout.size.height <= remainingHeight) {
                    currentSlices.add(PageBlockSlice(block.index, block, display, isCont, rawOffset, rawText.length))
                    remainingHeight = (remainingHeight - layout.size.height - spacingPx).coerceAtLeast(0)
                    return
                }
                val lastFitLine = findLastFitLine(layout, remainingHeight)
                if (lastFitLine < 0 && currentSlices.isNotEmpty()) {
                    flushPage()
                    continue
                }
                val targetLine = lastFitLine.coerceAtLeast(0)
                val endIdx = layout.getLineEnd(targetLine, visibleEnd = false).coerceIn(1, display.length)
                val consumedRaw = (endIdx - activePrefix.length).coerceAtLeast(1)
                val nextOffset = (rawOffset + consumedRaw).coerceAtMost(rawText.length)
                val sliceEnd = (activePrefix.length + (nextOffset - rawOffset)).coerceIn(1, display.length)
                val sliceText = display.substring(0, sliceEnd).trimEnd('\n', '\r')
                currentSlices.add(PageBlockSlice(block.index, block, sliceText, isCont, rawOffset, nextOffset))
                rawOffset = nextOffset
                isCont = true
                if (rawOffset < rawText.length) flushPage() else remainingHeight = 0
            }
        }

        private fun measureText(text: String, style: TextStyle) = textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = widthPx)
        )

        private fun findLastFitLine(layout: androidx.compose.ui.text.TextLayoutResult, maxHeight: Int): Int {
            var fit = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= maxHeight) fit = line else break
            }
            return fit
        }
    }
}
