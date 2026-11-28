package com.aitts.v2.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCardItem(
    record: BookRecord,
    highlighted: Boolean,
    inMultiSelect: Boolean,
    selected: Boolean,
    loadCover: suspend () -> Bitmap?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onEditInfo: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var coverBitmap by remember(record.id, record.coverFileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, record.coverFileName) { coverBitmap = loadCover() }
    InkPaperCard(highlighted = highlighted, padding = 12.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (inMultiSelect) Checkbox(checked = selected, onCheckedChange = { onLongPress() })
            BookCoverThumb(record = record, bitmap = coverBitmap, modifier = Modifier.width(56.dp).height(80.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.author.isNotBlank() || record.category.isNotBlank()) {
                    val meta = listOf(record.author, record.category).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(
                        text = meta,
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
                LinearProgressIndicator(
                    progress = { (record.progress.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "置顶",
                            modifier = Modifier.size(18.dp),
                            tint = if (record.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onEditInfo, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "编辑分类",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "删除书籍",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridCard(
    record: BookRecord,
    inMultiSelect: Boolean,
    selected: Boolean,
    loadCover: suspend () -> Bitmap?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    var coverBitmap by remember(record.id, record.coverFileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, record.coverFileName) { coverBitmap = loadCover() }
    InkPaperCard(highlighted = selected || record.isPinned, padding = 8.dp, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
                BookCoverThumb(record = record, bitmap = coverBitmap, modifier = Modifier.fillMaxSize())
                if (inMultiSelect) {
                    Checkbox(checked = selected, onCheckedChange = { onLongPress() }, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
            Text(
                text = record.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${record.format.label} · ${record.progress.progressPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BookCoverThumb(record: BookRecord, bitmap: Bitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = record.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(record.title.take(2), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun EditBookInfoDialog(
    record: BookRecord,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(record.id) { mutableStateOf(record.title) }
    var author by remember(record.id) { mutableStateOf(record.author) }
    var category by remember(record.id) { mutableStateOf(record.category) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书籍信息与分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("书名") }, singleLine = true)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者") }, singleLine = true)
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("书单分类 (如 小说/技术/外刊)") },
                    singleLine = true
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, author, category) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun BookshelfHeader(
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
fun BookshelfEmptyCard(onImportClick: () -> Unit, onOpenFileManager: () -> Unit) {
    InkPaperCard(padding = 20.dp) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("本地书架尚无藏书", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "采用 Readium 内核渲染 EPUB 2/3（完整样式/插图/笔记），配合流式分页处理超大 TXT/Markdown/HTML，支持多选直导与网盘同步。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportClick, modifier = Modifier.weight(1f)) { Text("多选导入书籍") }
            OutlinedButton(onClick = onOpenFileManager, modifier = Modifier.weight(1f)) { Text("文件与网盘中心") }
        }
    }
}
