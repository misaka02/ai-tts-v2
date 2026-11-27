package com.aitts.v2.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ChapterRef
import com.aitts.v2.ui.theme.InkPaperCard

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
    modifier: Modifier = Modifier
) {
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
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = chromeText.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = "全书检索")
                }
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "书签",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else chromeText
                    )
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
    isPlaying: Boolean,
    isSynthesizing: Boolean,
    chromeBg: Color,
    chromeText: Color,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenTtsSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = chromeBg, contentColor = chromeText, tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = chromeText.copy(alpha = 0.14f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onPrevChapter, enabled = chapterIndex > 0) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一章")
                }
                Text(
                    text = "第 ${chapterIndex + 1} / ${totalChapters.coerceAtLeast(1)} 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = chromeText.copy(alpha = 0.8f)
                )
                IconButton(onClick = onNextChapter, enabled = chapterIndex + 1 < totalChapters) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一章")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onOpenToc) {
                    Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = "目录与书签")
                }
                IconButton(onClick = onOpenAppearance) {
                    Icon(Icons.Outlined.FormatSize, contentDescription = "排版设置")
                }
                Button(onClick = onTogglePlay, modifier = Modifier.weight(1f)) {
                    when {
                        isSynthesizing -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("准备朗读...")
                        }
                        isPlaying -> {
                            Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("暂停听书")
                        }
                        else -> {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("AI 听书")
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
    onSelectChapter: (Int) -> Unit,
    onSelectBookmark: (BookBookmark) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tocListState = rememberLazyListState()
    LaunchedEffect(selectedTab, currentChapterIndex) {
        if (selectedTab == 0 && currentChapterIndex in chapters.indices) {
            tocListState.scrollToItem(currentChapterIndex)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxWidth().height(460.dp).navigationBarsPadding()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("目录 (${chapters.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("书签 (${bookmarks.size})") })
            }
            if (selectedTab == 0) {
                LazyColumn(state = tocListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(chapters, key = { it.index }) { chap ->
                        val active = chap.index == currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(chap.index) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            } else {
                if (bookmarks.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无书签，点击顶栏书签图标即可收藏当前阅读位置。", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(bookmarks, key = { it.id }) { mark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectBookmark(mark) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        mark.chapterTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        mark.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onRemoveBookmark(mark.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除书签")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSearchSheet(
    query: String,
    hits: List<BookSearchHit>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectHit: (BookSearchHit) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().height(460.dp).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("全书检索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("输入至少 2 个字开始搜索") },
                modifier = Modifier.fillMaxWidth()
            )
            if (isSearching) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在检索全书内容...", style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits) { hit ->
                    InkPaperCard(padding = 12.dp, onClick = { onSelectHit(hit) }) {
                        Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
