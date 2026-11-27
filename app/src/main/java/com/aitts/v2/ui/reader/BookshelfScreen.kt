package com.aitts.v2.ui.reader

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
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
    onOpenBook: (String) -> Unit,
    onOpenFileManager: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsStateFlow.collectAsStateWithLifecycle()
    val hasCloud by viewModel.hasCloudConnectedFlow.collectAsStateWithLifecycle()
    var pendingDeleteBook by remember { mutableStateOf<BookRecord?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

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
                hasCloud = hasCloud,
                onSyncLocal = viewModel::syncDedicatedFolder,
                onOpenFileManager = onOpenFileManager,
                onImportClick = { picker.launch(arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/html", "*/*")) }
            )
        }

        if (state.selectedIds.isNotEmpty()) {
            item {
                InkPaperCard(highlighted = true, padding = 10.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选中 ${state.selectedIds.size} 本", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                            Button(onClick = viewModel::deleteSelectedBooks) { Text("批量删除") }
                        }
                    }
                }
            }
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
                            IconButton(onClick = viewModel::stopTts) { Icon(Icons.Outlined.Stop, contentDescription = "停止听书") }
                        }
                    }
                }
            }
        }

        if (state.books.isNotEmpty() || state.searchQuery.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("检索书名或作者") },
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        OutlinedButton(onClick = { showSortMenu = true }) { Text(state.sort.label) }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            BookshelfSort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { viewModel.updateSort(s); showSortMenu = false })
                            }
                        }
                    }
                }
            }
        }

        if (state.books.isEmpty() && !state.isImporting) {
            item {
                BookshelfEmptyCard(
                    onImportClick = { picker.launch(arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/html", "*/*")) },
                    onOpenFileManager = onOpenFileManager
                )
            }
        } else if (state.books.isNotEmpty()) {
            val recent = state.books.first()
            item { InkSectionHeader(if (recent.isPinned) "置顶书籍" else "最近阅读", trailing = recent.format.label) }
            item(key = "hero_${recent.id}") {
                BookCardItem(
                    record = recent,
                    highlighted = true,
                    inMultiSelect = state.selectedIds.isNotEmpty(),
                    selected = recent.id in state.selectedIds,
                    loadCover = { viewModel.loadCover(recent) },
                    onOpen = { if (state.selectedIds.isNotEmpty()) viewModel.toggleSelect(recent.id) else onOpenBook(recent.id) },
                    onLongPress = { viewModel.toggleSelect(recent.id) },
                    onTogglePin = { viewModel.togglePin(recent.id) },
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
                        inMultiSelect = state.selectedIds.isNotEmpty(),
                        selected = book.id in state.selectedIds,
                        loadCover = { viewModel.loadCover(book) },
                        onOpen = { if (state.selectedIds.isNotEmpty()) viewModel.toggleSelect(book.id) else onOpenBook(book.id) },
                        onLongPress = { viewModel.toggleSelect(book.id) },
                        onTogglePin = { viewModel.togglePin(book.id) },
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
            text = { Text("确认删除《${target.title}》及本地阅读进度、书签与划线笔记？") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteBook(target.id); pendingDeleteBook = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteBook = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun BookshelfHeader(
    bookCount: Int,
    isImporting: Boolean,
    hasCloud: Boolean,
    onSyncLocal: () -> Unit,
    onOpenFileManager: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSyncLocal) { Icon(Icons.Outlined.Sync, contentDescription = "扫描专属目录") }
            OutlinedButton(onClick = onOpenFileManager) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (hasCloud) "网盘✓" else "文件/网盘")
            }
            Button(onClick = onImportClick, enabled = !isImporting) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("导入")
                }
            }
        }
    }
}

@Composable
private fun BookshelfEmptyCard(onImportClick: () -> Unit, onOpenFileManager: () -> Unit) {
    InkPaperCard(padding = 20.dp) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("本地书架尚无藏书", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "采用 Readium 工业级内核渲染 EPUB 2/3（完整支持目录/样式/插图/划词笔记），结合原生流式分页处理超大 TXT / Markdown / HTML，支持从本地专有目录或网盘直接导入同步。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportClick, modifier = Modifier.weight(1f)) { Text("选择本地书籍") }
            OutlinedButton(onClick = onOpenFileManager, modifier = Modifier.weight(1f)) { Text("文件与网盘中心") }
        }
    }
}
