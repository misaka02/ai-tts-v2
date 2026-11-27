package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.flow.distinctUntilChanged

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

@Composable
fun ReaderContentViewport(
    chapter: ChapterContent,
    totalChapters: Int,
    readerSettings: ReaderSettings,
    colors: ResolvedReaderColors,
    listState: LazyListState,
    activeTtsBlockIndex: Int,
    pendingScrollBlockIndex: Int?,
    onConsumedPendingScroll: () -> Unit,
    onVisibleBlockChanged: (Int, Int) -> Unit,
    onTapBody: () -> Unit,
    onLongPressSpeak: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    loadImage: suspend (String) -> Bitmap?
) {
    LaunchedEffect(chapter.chapterIndex, pendingScrollBlockIndex) {
        val targetBlock = pendingScrollBlockIndex ?: return@LaunchedEffect
        val itemIdx = chapter.blocks.indexOfFirst { it.index >= targetBlock }.let { if (it < 0) 0 else it + 1 }
        listState.scrollToItem(itemIdx)
        onConsumedPendingScroll()
    }

    LaunchedEffect(listState, chapter.chapterIndex) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { itemIndex ->
                val blockPos = (itemIndex - 1).coerceIn(0, (chapter.blocks.size - 1).coerceAtLeast(0))
                val blockIndex = chapter.blocks.getOrNull(blockPos)?.index ?: 0
                onVisibleBlockChanged(blockIndex, 0)
            }
    }

    LaunchedEffect(activeTtsBlockIndex, chapter.chapterIndex, readerSettings.autoScrollOnTts) {
        if (!readerSettings.autoScrollOnTts || activeTtsBlockIndex < 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val blockPos = chapter.blocks.indexOfFirst { it.index == activeTtsBlockIndex }
        if (blockPos < 0) return@LaunchedEffect
        val itemIdx = blockPos + 1
        val visible = listState.layoutInfo.visibleItemsInfo
        val alreadyWellPositioned = visible.any { it.index == itemIdx && it.offset >= 60 && it.offset < 700 }
        if (!alreadyWellPositioned) {
            listState.animateScrollToItem(itemIdx, scrollOffset = -120)
        }
    }

    val fontFamily = when (readerSettings.fontFamily) {
        ReaderFontFamily.SERIF -> FontFamily.Serif
        ReaderFontFamily.SANS -> FontFamily.SansSerif
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTapBody() },
        contentPadding = PaddingValues(
            start = readerSettings.horizontalPaddingDp.dp,
            end = readerSettings.horizontalPaddingDp.dp,
            top = 24.dp,
            bottom = 84.dp
        ),
        verticalArrangement = Arrangement.spacedBy(readerSettings.paragraphSpacingDp.dp)
    ) {
        item(key = "chapter_top_${chapter.chapterIndex}") {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = fontFamily,
                    color = colors.text,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.25f))
            }
        }

        itemsIndexed(chapter.blocks, key = { idx, blk -> "${chapter.chapterIndex}_${blk.index}_$idx" }) { _, block ->
            val displayText = when (block) {
                is ReaderBlock.Heading -> block.text
                is ReaderBlock.Paragraph ->
                    if (readerSettings.firstLineIndent && !block.isQuote) "\u3000\u3000${block.text}" else block.text
                else -> ""
            }
            ReaderBlockItemView(
                block = block,
                displayText = displayText,
                isActive = activeTtsBlockIndex == block.index,
                settings = readerSettings,
                fontFamily = fontFamily,
                colors = colors,
                maxImageHeightDp = 420,
                onTap = { onTapBody() },
                onLongPress = { onLongPressSpeak(block.index) },
                loadImage = loadImage
            )
        }

        item(key = "chapter_footer_${chapter.chapterIndex}") {
            ChapterFooterNav(
                chapterIndex = chapter.chapterIndex,
                totalChapters = totalChapters,
                colors = colors,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter
            )
        }
    }
}

@Composable
private fun ChapterFooterNav(
    chapterIndex: Int,
    totalChapters: Int,
    colors: ResolvedReaderColors,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.25f))
        Text(
            text = "本章完 (${chapterIndex + 1} / ${totalChapters.coerceAtLeast(1)})",
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onPrevChapter,
                enabled = chapterIndex > 0,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                modifier = Modifier.weight(1f)
            ) { Text("上一章") }
            OutlinedButton(
                onClick = onNextChapter,
                enabled = chapterIndex + 1 < totalChapters,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                modifier = Modifier.weight(1f)
            ) { Text("下一章") }
        }
    }
}
