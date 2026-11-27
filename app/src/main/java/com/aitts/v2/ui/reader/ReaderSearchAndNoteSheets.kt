package com.aitts.v2.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSearchSheet(
    query: String,
    hits: List<BookSearchHit>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectHit: (Int, BookSearchHit) -> Unit,
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
                items(hits.size) { index ->
                    val hit = hits[index]
                    InkPaperCard(padding = 12.dp, onClick = { onSelectHit(index, hit) }) {
                        Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderHighlightNoteDialog(
    highlight: BookHighlight,
    onSave: (note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var noteText by remember(highlight.id) { mutableStateOf(highlight.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("划线笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "“${highlight.quote}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("笔记内容") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(noteText) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun ReaderHighlightActionDialog(
    highlight: BookHighlight,
    onChangeColor: (Long) -> Unit,
    onEditNote: () -> Unit,
    onCopyQuote: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val swatches = listOf(0xFFFFD54FL, 0xFF81D4FAL, 0xFFA5D6A7L, 0xFFF48FB1L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("划线操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "“${highlight.quote}”",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("颜色：", style = MaterialTheme.typography.labelMedium)
                    swatches.forEach { hex ->
                        val active = highlight.colorHex == hex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (active) 2.5.dp else 1.dp,
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable { onChangeColor(hex) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEditNote, modifier = Modifier.weight(1f)) { Text("写笔记") }
                    OutlinedButton(onClick = onCopyQuote, modifier = Modifier.weight(1f)) { Text("复制") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("删除划线", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun ReaderParagraphActionDialog(
    text: String,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onBookmark: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("段落操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = onSpeak, modifier = Modifier.fillMaxWidth()) { Text("从该段开始 AI 朗读") }
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("复制整段文字") }
                OutlinedButton(onClick = onBookmark, modifier = Modifier.fillMaxWidth()) { Text("添加书签于此处") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun HighlightListTab(
    highlights: List<BookHighlight>,
    onSelect: (BookHighlight) -> Unit,
    onEditNote: (BookHighlight) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (highlights.isEmpty()) {
        Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("在 EPUB 正文长按划词后，可点击“高亮 / 写笔记”记录摘录。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(highlights, key = { it.id }) { item ->
            InkPaperCard(padding = 12.dp, onClick = { onSelect(item) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.width(4.dp).height(28.dp).background(Color(item.colorHex), RoundedCornerShape(2.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.quote, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (item.note.isNotBlank()) {
                            Text("笔记：${item.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { onEditNote(item) }) {
                        Icon(Icons.Outlined.EditNote, contentDescription = "编辑笔记")
                    }
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除划线")
                    }
                }
            }
        }
    }
}
