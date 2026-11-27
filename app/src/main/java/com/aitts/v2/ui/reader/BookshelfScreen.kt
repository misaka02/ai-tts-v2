package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkSectionHeader
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onOpenBook: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsStateFlow.collectAsStateWithLifecycle()
    var pendingDeleteBook by remember { mutableStateOf<BookRecord?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBook(it) { importedId -> onOpenBook(importedId) } }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            BookshelfHeader(
                bookCount = state.books.size,
                isImporting = state.isImporting,
                onImportClick = {
                    picker.launch(
                        arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/html", "*/*")
                    )
                }
            )
        }

        if (ttsState.isPlaying || ttsState.isSynthesizing) {
            ttsState.bookId?.let { playingBookId ->
                item {
                    InkPaperCard(highlighted = true, padding = 12.dp, onClick = { onOpenBook(playingBookId) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "正在听书 · ${ttsState.bookTitle}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = ttsState.chapterTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = viewModel::stopTts) {
                                Icon(Icons.Outlined.Stop, contentDescription = "停止听书")
                            }
                        }
                    }
                }
            }
        }

        if (state.books.size > 3 || state.searchQuery.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("按书名或作者检索书架") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (state.books.isEmpty() && !state.isImporting) {
            item {
                BookshelfEmptyCard(
                    onImportClick = {
                        picker.launch(arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/html", "*/*"))
                    }
                )
            }
        } else if (state.books.isNotEmpty()) {
            val recent = state.books.first()
            item { InkSectionHeader("最近阅读", trailing = recent.format.label) }
            item(key = "hero_${recent.id}") {
                BookCardItem(
                    record = recent,
                    highlighted = true,
                    loadCover = { viewModel.loadCover(recent) },
                    onOpen = { onOpenBook(recent.id) },
                    onDelete = { pendingDeleteBook = recent }
                )
            }
            val rest = state.books.drop(1)
            if (rest.isNotEmpty()) {
                item { InkSectionHeader("全部藏书", trailing = "${rest.size} 本") }
                items(rest, key = { it.id }) { book ->
                    BookCardItem(
                        record = book,
                        highlighted = false,
                        loadCover = { viewModel.loadCover(book) },
                        onOpen = { onOpenBook(book.id) },
                        onDelete = { pendingDeleteBook = book }
                    )
                }
            }
        }
    }

    pendingDeleteBook?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBook = null },
            title = { Text("移出书架") },
            text = { Text("确认删除《${target.title}》及本地阅读进度与书签？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(target.id)
                    pendingDeleteBook = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBook = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BookshelfHeader(
    bookCount: Int,
    isImporting: Boolean,
    onImportClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("书架", style = MaterialTheme.typography.headlineMedium)
            InkStatusPill(label = "$bookCount 本馆藏", tone = PillTone.NEUTRAL)
        }
        Button(onClick = onImportClick, enabled = !isImporting) {
            if (isImporting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("解析中...")
            } else {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入书籍")
            }
        }
    }
}

@Composable
private fun BookshelfEmptyCard(onImportClick: () -> Unit) {
    InkPaperCard(padding = 20.dp) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("本地书架尚无藏书", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "原生支持 EPUB 2/3、超大 TXT (自动识别 UTF-8 / UTF-16 / GBK 编码)、Markdown 与 HTML，" +
                "点击任意段落即可驱动当前 AI 音色同步高亮朗读。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
            Text("选择本地电子书")
        }
    }
}

@Composable
private fun BookCardItem(
    record: BookRecord,
    highlighted: Boolean,
    loadCover: suspend () -> Bitmap?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var coverBitmap by remember(record.id, record.coverFileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, record.coverFileName) {
        coverBitmap = loadCover()
    }
    InkPaperCard(highlighted = highlighted, padding = 14.dp, onClick = onOpen) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCoverThumb(record = record, bitmap = coverBitmap)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.author.isNotBlank()) {
                    Text(
                        text = record.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkStatusPill(label = record.format.label, tone = PillTone.ACCENT)
                    Text(
                        text = "第 ${record.progress.chapterIndex + 1}/${record.chapterCount} 章 · ${record.progress.progressPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { (record.progress.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除书籍",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BookCoverThumb(record: BookRecord, bitmap: Bitmap?) {
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = record.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(60.dp).height(84.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = record.title.take(2),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
