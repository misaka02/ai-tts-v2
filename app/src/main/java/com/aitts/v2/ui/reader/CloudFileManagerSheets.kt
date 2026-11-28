package com.aitts.v2.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.engine.cloud.CloudFileNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudStatusActionStrip(
    source: FileSourceTab,
    title: String,
    syncTargetLabel: String,
    onOpenSync: () -> Unit,
    onScanMoon: (() -> Unit)? = null,
    secondaryActionLabel: String,
    onSecondaryAction: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (source == FileSourceTab.LOCAL) Icons.Outlined.Folder else Icons.Outlined.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (syncTargetLabel.isNotBlank()) {
                    Text(
                        text = "同步目录：$syncTargetLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onScanMoon != null) {
                FilledTonalButton(
                    onClick = onScanMoon,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("静读天下", style = MaterialTheme.typography.labelMedium)
                }
            }
            Button(
                onClick = onOpenSync,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("同步", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = onSecondaryAction,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(secondaryActionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudFileListItem(
    node: CloudFileNode,
    source: FileSourceTab,
    selected: Boolean,
    selectionMode: Boolean,
    isImported: Boolean,
    formattedSize: String,
    hasFoldersInDir: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onDownloadLocal: (() -> Unit)? = null,
    onScanMoonHere: (() -> Unit)? = null,
    onMoveToFolder: (() -> Unit)? = null,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateStr = remember(node.modifiedTimeMs) {
        if (node.modifiedTimeMs > 0L) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(node.modifiedTimeMs)) else ""
    }
    val subText = listOfNotNull(
        formattedSize.takeIf { it.isNotBlank() },
        dateStr.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onClick() },
                    onLongClick = onToggleSelect
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp).size(22.dp)
                )
            }
            FileTypeBadge(node = node)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subText.isNotBlank()) {
                        Text(subText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isImported) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "已在书架",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!node.isFolder && node.isSupportedEbook) {
                        DropdownMenuItem(text = { Text("导入并阅读") }, onClick = { menuExpanded = false; onClick() })
                        if (source != FileSourceTab.LOCAL && onDownloadLocal != null) {
                            DropdownMenuItem(
                                text = { Text("下载到本地同步目录") },
                                onClick = { menuExpanded = false; onDownloadLocal() }
                            )
                        }
                    }
                    if (node.isFolder && node.name.equals(".Moon+", true) && onScanMoonHere != null) {
                        DropdownMenuItem(
                            text = { Text("继承此静读天下书库") },
                            onClick = { menuExpanded = false; onScanMoonHere() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (selected) "取消勾选" else "勾选批量操作") },
                        onClick = { menuExpanded = false; onToggleSelect() }
                    )
                    if (source == FileSourceTab.GOOGLE_DRIVE && hasFoldersInDir && onMoveToFolder != null) {
                        DropdownMenuItem(
                            text = { Text("移动到子文件夹") },
                            onClick = { menuExpanded = false; onMoveToFolder() }
                        )
                    }
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun FileTypeBadge(node: CloudFileNode) {
    val ext = node.name.substringAfterLast('.', "").uppercase()
    val (label, bg, fg) = when {
        node.isFolder && node.name.equals(".Moon+", true) ->
            Triple("MOON", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        node.isFolder ->
            Triple("DIR", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ext == "EPUB" ->
            Triple("EPUB", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ext in setOf("TXT", "TEXT", "MD", "HTML", "HTM") ->
            Triple(ext.take(4), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        else ->
            Triple(ext.ifBlank { "FILE" }.take(4), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
        }
    }
}
