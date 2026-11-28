package com.aitts.v2.ui.reader

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
    var editingBook by remember { mutableStateOf<BookRecord?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importBooks(uris) { importedId -> onOpenBook(importedId) }
    }
    val importTypes = arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/html", "*/*")

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
                onImportClick = { picker.launch(importTypes) }
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

        if (state.books.isNotEmpty() || state.searchQuery.isNotEmpty() || state.selectedCategory.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text("检索书名或作者") },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::toggleGridMode) {
                            Icon(
                                imageVector = if (state.isGridMode) {
                                    Icons.AutoMirrored.Outlined.FormatListBulleted
                                } else Icons.Outlined.GridView,
                                contentDescription = "切换网格/列表"
                            )
                        }
                        Box {
                            OutlinedButton(onClick = { showSortMenu = true }) { Text(state.sort.label) }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                BookshelfSort.entries.forEach { s ->
                                    DropdownMenuItem(text = { Text(s.label) }, onClick = { viewModel.updateSort(s); showSortMenu = false })
                                }
                            }
                        }
                    }
                    if (state.categories.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.selectedCategory.isEmpty(),
                                onClick = { viewModel.selectCategory("") },
                                label = { Text("全部") }
                            )
                            state.categories.forEach { cat ->
                                FilterChip(
                                    selected = state.selectedCategory == cat,
                                    onClick = { viewModel.selectCategory(cat) },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.books.isEmpty() && !state.isImporting) {
            item {
                BookshelfEmptyCard(
                    onImportClick = { picker.launch(importTypes) },
                    onOpenFileManager = onOpenFileManager
                )
            }
        } else if (state.isGridMode) {
            val rows = state.books.chunked(3)
            items(rows, key = { row -> row.joinToString("_") { it.id } }) { rowBooks ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (book in rowBooks) {
                        BookGridCard(
                            record = book,
                            inMultiSelect = state.selectedIds.isNotEmpty(),
                            selected = book.id in state.selectedIds,
                            loadCover = { viewModel.loadCover(book) },
                            onOpen = { if (state.selectedIds.isNotEmpty()) viewModel.toggleSelect(book.id) else onOpenBook(book.id) },
                            onLongPress = { viewModel.toggleSelect(book.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - rowBooks.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
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
                    onEditInfo = { editingBook = recent },
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
                        onEditInfo = { editingBook = book },
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

    editingBook?.let { target ->
        EditBookInfoDialog(
            record = target,
            onSave = { title, author, category ->
                viewModel.updateBookInfo(target.id, title, author, category)
                editingBook = null
            },
            onDismiss = { editingBook = null }
        )
    }
}
