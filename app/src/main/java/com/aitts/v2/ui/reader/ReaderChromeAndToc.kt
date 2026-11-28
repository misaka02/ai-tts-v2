package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.BookImageItem
import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.ChapterRef

@Composable
fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    isBookmarked: Boolean,
    chromeBg: Color,
    chromeText: Color,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenSearch: () -> Unit,
    onStartAutoScroll: () -> Unit,
    onOpenJump: () -> Unit,
    onOpenBookInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMore by remember { mutableStateOf(false) }

    Surface(color = chromeBg, contentColor = chromeText, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(bookTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = chromeText.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenSearch) { Icon(Icons.Outlined.Search, contentDescription = "全书检索") }
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "书签",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else chromeText
                    )
                }
                Box {
                    IconButton(onClick = { showMore = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多功能") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text("自动滚屏/翻页阅读") }, onClick = { showMore = false; onStartAutoScroll() })
                        DropdownMenuItem(text = { Text("精确跳转章节/进度") }, onClick = { showMore = false; onOpenJump() })
                        DropdownMenuItem(text = { Text("书籍信息与导出笔记") }, onClick = { showMore = false; onOpenBookInfo() })
                    }
                }
            }
            HorizontalDivider(color = chromeText.copy(alpha = 0.14f))
        }
    }
}

@Composable
fun ReaderBottomBar(
    chapterIndex: Int,
    totalChapters: Int,
    bookProgression: Float,
    isPlaying: Boolean,
    isSynthesizing: Boolean,
    chromeBg: Color,
    chromeText: Color,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekProgression: (Float) -> Unit,
    onOpenJump: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleDayNight: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenTtsSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember(bookProgression) { mutableFloatStateOf(bookProgression.coerceIn(0f, 1f)) }
    val displayFraction = if (dragging) dragFraction else bookProgression.coerceIn(0f, 1f)
    val previewChapter = ((displayFraction * totalChapters.coerceAtLeast(1)).toInt() + 1)
        .coerceIn(1, totalChapters.coerceAtLeast(1))

    Surface(color = chromeBg, contentColor = chromeText, tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = chromeText.copy(alpha = 0.14f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevChapter, enabled = chapterIndex > 0) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一章")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (dragging) {
                            "跳转至约第 $previewChapter / ${totalChapters.coerceAtLeast(1)} 章 · ${(displayFraction * 100).toInt()}%"
                        } else {
                            "第 ${chapterIndex + 1} / ${totalChapters.coerceAtLeast(1)} 章 · ${(displayFraction * 100).toInt()}% (点击精跳)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = chromeText.copy(alpha = 0.85f),
                        modifier = Modifier.clickable(onClick = onOpenJump)
                    )
                    Slider(
                        value = displayFraction,
                        onValueChange = { dragging = true; dragFraction = it },
                        onValueChangeFinished = { dragging = false; onSeekProgression(dragFraction) },
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    )
                }
                IconButton(onClick = onNextChapter, enabled = chapterIndex + 1 < totalChapters) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一章")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onOpenToc) {
                    Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = "目录、书签、笔记与插图")
                }
                IconButton(onClick = onToggleDayNight) {
                    Icon(Icons.Outlined.Brightness4, contentDescription = "快捷切换日夜模式")
                }
                IconButton(onClick = onOpenAppearance) {
                    Icon(Icons.Outlined.FormatSize, contentDescription = "排版与亮度")
                }
                Button(onClick = onTogglePlay, modifier = Modifier.weight(1f)) {
                    when {
                        isSynthesizing -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("准备朗读")
                        }
                        isPlaying -> {
                            Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("暂停")
                        }
                        else -> {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("听书")
                        }
                    }
                }
                IconButton(onClick = onOpenTtsSheet) {
                    Icon(Icons.Outlined.Headphones, contentDescription = "听书控制台")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    chapters: List<ChapterRef>,
    currentChapterIndex: Int,
    bookmarks: List<BookBookmark>,
    highlights: List<BookHighlight>,
    images: List<BookImageItem>,
    isExportingImages: Boolean,
    imageExportProgress: String,
    loadThumbnail: suspend (String) -> Bitmap?,
    onSelectChapter: (ChapterRef) -> Unit,
    onSelectBookmark: (BookBookmark) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onSelectHighlight: (BookHighlight) -> Unit,
    onEditHighlightNote: (BookHighlight) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onSelectImage: (BookImageItem) -> Unit,
    onExportAllImages: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tocListState = rememberLazyListState()
    LaunchedEffect(selectedTab, currentChapterIndex) {
        val activePos = chapters.indexOfFirst { it.index == currentChapterIndex }
        if (selectedTab == 0 && activePos >= 0) tocListState.scrollToItem(activePos)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxWidth().height(500.dp).navigationBarsPadding()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("目录(${chapters.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("书签(${bookmarks.size})") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("笔记(${highlights.size})") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("插图(${images.size})") })
            }
            when (selectedTab) {
                0 -> LazyColumn(state = tocListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(chapters, key = { pos, chap -> "${pos}_${chap.index}_${chap.title}" }) { pos, chap ->
                        val active = chap.index == currentChapterIndex
                        val indentDp = (16 + chap.depth.coerceIn(0, 4) * 16).dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(chap) }
                                .padding(start = indentDp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chap.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                1 -> BookmarkListTab(
                    bookmarks = bookmarks,
                    onSelect = onSelectBookmark,
                    onRemove = onRemoveBookmark,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                2 -> HighlightListTab(
                    highlights = highlights,
                    onSelect = onSelectHighlight,
                    onEditNote = onEditHighlightNote,
                    onRemove = onRemoveHighlight,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                else -> BookImageGalleryTab(
                    images = images,
                    isExporting = isExportingImages,
                    exportProgressText = imageExportProgress,
                    loadThumbnail = loadThumbnail,
                    onSelectImage = onSelectImage,
                    onExportAll = onExportAllImages,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BookmarkListTab(
    bookmarks: List<BookBookmark>,
    onSelect: (BookBookmark) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bookmarks.isEmpty()) {
        Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无书签，点击顶栏书签图标即可收藏当前阅读位置。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier = modifier) {
        items(bookmarks, key = { it.id }) { mark ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(mark) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mark.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(mark.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(mark.id) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除书签")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}
