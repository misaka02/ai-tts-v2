package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Horizontal page-turning reader viewport supporting 3-zone tap navigation,
 * swipe page/chapter transitions, running header/footer, and live TTS page tracking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagedReaderViewport(
    chapter: ChapterContent,
    totalChapters: Int,
    bookProgressPercent: Int,
    readerSettings: ReaderSettings,
    colors: ResolvedReaderColors,
    activeTtsBlockIndex: Int,
    pendingScrollBlockIndex: Int?,
    pendingScrollCharOffset: Int = 0,
    pendingLandOnLastPage: Boolean,
    pageTurnEvents: Flow<Int>,
    onConsumedPendingScroll: () -> Unit,
    onVisibleBlockChanged: (Int, Int) -> Unit,
    onTapCenter: () -> Unit,
    onLongPressSpeak: (Int) -> Unit,
    onPrevChapter: (landOnLastPage: Boolean) -> Unit,
    onNextChapter: () -> Unit,
    loadImage: suspend (String) -> Bitmap?,
    onImageClick: (Bitmap) -> Unit = {}
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()
    val fontFamily = rememberResolvedReaderFontFamily(readerSettings)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val headerPx = with(density) { 36.dp.roundToPx() }
        val footerPx = with(density) { 32.dp.roundToPx() }
        val horizPadPx = with(density) { (readerSettings.horizontalPaddingDp * 2f).dp.roundToPx() }
        val activeHighlightPadPx = with(density) { 16.dp.roundToPx() }
        val contentWidthPx = (constraints.maxWidth - horizPadPx - activeHighlightPadPx).coerceAtLeast(180)
        val contentHeightPx = (constraints.maxHeight - headerPx - footerPx).coerceAtLeast(240)
        val imageReservePx = minOf((contentHeightPx * 0.40f).toInt(), with(density) { 220.dp.roundToPx() })
        val maxImageDp = with(density) { imageReservePx.toDp().value.toInt().coerceAtLeast(80) }

        val pages = remember(chapter, readerSettings, fontFamily, contentWidthPx, contentHeightPx) {
            ChapterPaginator.paginate(
                chapter = chapter,
                settings = readerSettings,
                fontFamily = fontFamily,
                widthPx = contentWidthPx,
                heightPx = contentHeightPx,
                density = density,
                textMeasurer = textMeasurer
            )
        }

        val hasPrev = chapter.chapterIndex > 0
        val hasNext = chapter.chapterIndex + 1 < totalChapters
        val pageOffset = if (hasPrev) 1 else 0
        val totalPagerItems = pageOffset + pages.size + (if (hasNext) 1 else 0)

        key(chapter.chapterIndex) {
            var anchorBlockIndex by remember { mutableIntStateOf(pendingScrollBlockIndex ?: 0) }
            var anchorCharOffset by remember { mutableIntStateOf(pendingScrollCharOffset) }
            val startRealPage = when {
                pendingLandOnLastPage -> pages.lastIndex
                pendingScrollBlockIndex != null -> findPageForPosition(pages, pendingScrollBlockIndex, pendingScrollCharOffset)
                else -> 0
            }
            val pagerState = rememberPagerState(
                initialPage = pageOffset + startRealPage,
                pageCount = { totalPagerItems }
            )

            LaunchedEffect(pages, pendingScrollBlockIndex, pendingScrollCharOffset, pendingLandOnLastPage) {
                val targetBlock = pendingScrollBlockIndex ?: anchorBlockIndex
                val targetChar = if (pendingScrollBlockIndex != null) pendingScrollCharOffset else anchorCharOffset
                val targetRealPage = if (pendingLandOnLastPage) {
                    pages.lastIndex
                } else {
                    findPageForPosition(pages, targetBlock, targetChar)
                }
                if (pagerState.currentPage != pageOffset + targetRealPage) {
                    pagerState.scrollToPage(pageOffset + targetRealPage)
                }
                if (pendingScrollBlockIndex != null || pendingLandOnLastPage) {
                    val landed = pages[targetRealPage].slices.firstOrNull()
                    anchorBlockIndex = landed?.blockIndex ?: targetBlock
                    anchorCharOffset = landed?.startCharOffset ?: targetChar
                    onConsumedPendingScroll()
                }
            }

            LaunchedEffect(pagerState.settledPage, pages.size) {
                val settled = pagerState.settledPage
                if (hasPrev && settled == 0) {
                    onPrevChapter(true)
                    return@LaunchedEffect
                }
                if (hasNext && settled == totalPagerItems - 1) {
                    onNextChapter()
                    return@LaunchedEffect
                }
                val realIdx = (settled - pageOffset).coerceIn(0, pages.lastIndex)
                val pageSlices = pages[realIdx].slices
                val isTtsFollowPage = activeTtsBlockIndex >= 0 && pageSlices.any { it.blockIndex == activeTtsBlockIndex }
                if (isTtsFollowPage) {
                    anchorBlockIndex = activeTtsBlockIndex
                    anchorCharOffset = 0
                    return@LaunchedEffect
                }
                val firstSlice = pageSlices.firstOrNull()
                val firstBlock = firstSlice?.blockIndex ?: 0
                val firstChar = firstSlice?.startCharOffset ?: 0
                anchorBlockIndex = firstBlock
                anchorCharOffset = firstChar
                onVisibleBlockChanged(firstBlock, firstChar)
            }

            LaunchedEffect(activeTtsBlockIndex, readerSettings.autoScrollOnTts, pages) {
                if (!readerSettings.autoScrollOnTts || activeTtsBlockIndex < 0) return@LaunchedEffect
                val targetReal = pages.indexOfFirst { page -> page.slices.any { it.blockIndex == activeTtsBlockIndex } }
                if (targetReal >= 0 && pagerState.currentPage != pageOffset + targetReal && !pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(pageOffset + targetReal)
                }
            }

            fun turnPage(delta: Int) {
                val curReal = pagerState.currentPage - pageOffset
                val targetReal = curReal + delta
                when {
                    targetReal < 0 && hasPrev -> onPrevChapter(true)
                    targetReal >= pages.size && hasNext -> onNextChapter()
                    targetReal in pages.indices -> coroutineScope.launch { pagerState.animateScrollToPage(pageOffset + targetReal) }
                }
            }

            LaunchedEffect(pageTurnEvents, pages.size) {
                pageTurnEvents.collect { delta -> turnPage(delta) }
            }

            val currentRealPage = (pagerState.currentPage - pageOffset).coerceIn(0, pages.lastIndex)

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = readerSettings.horizontalPaddingDp.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { pagerIdx ->
                    val realIdx = pagerIdx - pageOffset
                    if (realIdx !in pages.indices) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (realIdx < 0) "载入上一章..." else "载入下一章...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.secondaryText
                            )
                        }
                    } else {
                        SingleBookPage(
                            page = pages[realIdx],
                            settings = readerSettings,
                            fontFamily = fontFamily,
                            colors = colors,
                            maxImageHeightDp = maxImageDp,
                            activeTtsBlockIndex = activeTtsBlockIndex,
                            onTapZone = { fractionX ->
                                when {
                                    fractionX < 0.30f -> turnPage(-1)
                                    fractionX > 0.70f -> turnPage(1)
                                    else -> onTapCenter()
                                }
                            },
                            onLongPressSpeak = onLongPressSpeak,
                            loadImage = loadImage,
                            onImageClick = onImageClick
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(horizontal = readerSettings.horizontalPaddingDp.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "第 ${currentRealPage + 1} / ${pages.size} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondaryText
                    )
                    Text(
                        text = if (activeTtsBlockIndex >= 0) "朗读中 · $bookProgressPercent%" else "全书 $bookProgressPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activeTtsBlockIndex >= 0) colors.accent else colors.secondaryText
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleBookPage(
    page: ReaderPage,
    settings: ReaderSettings,
    fontFamily: FontFamily,
    colors: ResolvedReaderColors,
    maxImageHeightDp: Int,
    activeTtsBlockIndex: Int,
    onTapZone: (Float) -> Unit,
    onLongPressSpeak: (Int) -> Unit,
    loadImage: suspend (String) -> Bitmap?,
    onImageClick: (Bitmap) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page.pageIndex) {
                detectTapGestures { offset ->
                    val fraction = if (size.width > 0) offset.x / size.width else 0.5f
                    onTapZone(fraction)
                }
            }
            .padding(horizontal = settings.horizontalPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp)
    ) {
        for (slice in page.slices) {
            ReaderBlockItemView(
                block = slice.block,
                displayText = slice.displayText,
                isActive = activeTtsBlockIndex == slice.blockIndex,
                settings = settings,
                fontFamily = fontFamily,
                colors = colors,
                maxImageHeightDp = maxImageHeightDp,
                onTap = onTapZone,
                onLongPress = { onLongPressSpeak(slice.blockIndex) },
                loadImage = loadImage,
                onImageClick = onImageClick
            )
        }
    }
}

private fun findPageForPosition(pages: List<ReaderPage>, blockIndex: Int, charOffset: Int): Int =
    pages.indexOfFirst { page ->
        page.slices.any { slice -> matchesSlicePosition(slice, blockIndex, charOffset) }
    }.coerceAtLeast(0)

private fun matchesSlicePosition(slice: PageBlockSlice, blockIndex: Int, charOffset: Int): Boolean {
    if (slice.blockIndex > blockIndex) return true
    if (slice.blockIndex < blockIndex) return false
    return charOffset <= 0 || slice.endCharOffset > charOffset
}
