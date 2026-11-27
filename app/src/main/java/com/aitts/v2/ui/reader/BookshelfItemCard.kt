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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var coverBitmap by remember(record.id, record.coverFileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, record.coverFileName) { coverBitmap = loadCover() }
    InkPaperCard(highlighted = highlighted, padding = 12.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (inMultiSelect) Checkbox(checked = selected, onCheckedChange = { onLongPress() })
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
                LinearProgressIndicator(
                    progress = { (record.progress.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = "置顶",
                    tint = if (record.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除书籍", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BookCoverThumb(record: BookRecord, bitmap: Bitmap?) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = record.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).height(80.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(record.title.take(2), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
