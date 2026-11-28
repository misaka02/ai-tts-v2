package com.aitts.v2.ui.reader

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.engine.cloud.SyncDeleteCandidate
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.engine.cloud.SyncPlan
import com.aitts.v2.reader.MoonMigrationPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncOptionsSheet(
    source: FileSourceTab,
    currentSyncFolderLabel: String,
    currentBrowseFolderLabel: String,
    onSetCurrentFolderAsSyncRoot: () -> Unit,
    onRunSync: (SyncDirection) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("同步设置 (${source.label})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前同步目录：$currentSyncFolderLabel", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (source != FileSourceTab.LOCAL) {
                        OutlinedButton(onClick = onSetCurrentFolderAsSyncRoot, modifier = Modifier.fillMaxWidth()) {
                            Text("将当前浏览目录「$currentBrowseFolderLabel」设为同步目录")
                        }
                    }
                    Text(
                        "安全承诺：同步过程中如发现某端文件被移除，绝不会静默删书，必弹窗由您逐项手动勾选确认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (source == FileSourceTab.LOCAL) {
                Button(onClick = { onRunSync(SyncDirection.TWO_WAY) }, modifier = Modifier.fillMaxWidth()) {
                    Text("扫描专属目录并同步到书架")
                }
            } else {
                SyncDirection.entries.forEach { dir ->
                    if (dir == SyncDirection.TWO_WAY) {
                        Button(onClick = { onRunSync(dir) }, modifier = Modifier.fillMaxWidth()) { Text(dir.label) }
                    } else {
                        OutlinedButton(onClick = { onRunSync(dir) }, modifier = Modifier.fillMaxWidth()) { Text(dir.label) }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncDeleteConfirmDialog(
    plan: SyncPlan,
    onConfirm: (approvedLocalIds: Set<String>, approvedRemoteIds: Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var checkedLocal by remember(plan) { mutableStateOf(emptySet<String>()) }
    var checkedRemote by remember(plan) { mutableStateOf(emptySet<String>()) }
    val totalPending = plan.pendingLocalDeletes.size + plan.pendingRemoteDeletes.size

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("同步删除需您手动确认", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "检测到以下 ${totalPending} 项此前已同步的文件在另一端已移除；新增拉取(${plan.toPull.size})/推送(${plan.toPush.size})将正常执行。" +
                        "默认全部不勾选，只有您明确勾选的文件才会被删除：",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        checkedLocal = plan.pendingLocalDeletes.map { it.id }.toSet()
                        checkedRemote = plan.pendingRemoteDeletes.map { it.id }.toSet()
                    }) { Text("全选删除项") }
                    TextButton(onClick = {
                        checkedLocal = emptySet()
                        checkedRemote = emptySet()
                    }) { Text("全不选") }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(plan.pendingLocalDeletes, key = { "loc_${it.id}" }) { cand ->
                        DeleteCandidateRow(
                            cand = cand,
                            badge = "删本地",
                            sizeLabel = Formatter.formatShortFileSize(context, cand.sizeBytes),
                            checked = cand.id in checkedLocal,
                            onToggle = {
                                checkedLocal = if (cand.id in checkedLocal) checkedLocal - cand.id else checkedLocal + cand.id
                            }
                        )
                    }
                    items(plan.pendingRemoteDeletes, key = { "rem_${it.id}" }) { cand ->
                        DeleteCandidateRow(
                            cand = cand,
                            badge = "删云端",
                            sizeLabel = Formatter.formatShortFileSize(context, cand.sizeBytes),
                            checked = cand.id in checkedRemote,
                            onToggle = {
                                checkedRemote = if (cand.id in checkedRemote) checkedRemote - cand.id else checkedRemote + cand.id
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(checkedLocal, checkedRemote) }) {
                val n = checkedLocal.size + checkedRemote.size
                Text(if (n == 0) "跳过全部删除并同步" else "确认删除 $n 项并同步")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消同步") } }
    )
}

@Composable
private fun DeleteCandidateRow(
    cand: SyncDeleteCandidate,
    badge: String,
    sizeLabel: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Text(
                    cand.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val subText = "$sizeLabel · ${cand.reason}"
            Text(subText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BatchDeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量删除确认") },
        text = { Text("确定要删除已勾选的 $count 个文件/目录吗？（Google 网盘将移入回收站）") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun MoonMigrationDialog(
    preview: MoonMigrationPreview,
    onImport: (selectedIds: Set<String>, downloadMissing: Boolean, alsoSetAsSyncFolder: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var activeGroup by remember(preview) { mutableStateOf("") }
    var downloadMissing by remember(preview) { mutableStateOf(true) }
    var bindSync by remember(preview) { mutableStateOf(true) }
    val filtered = remember(preview, activeGroup) {
        if (activeGroup.isBlank()) preview.candidates else preview.candidates.filter { it.shelfGroup == activeGroup }
    }
    var selectedIds by remember(preview) { mutableStateOf(preview.candidates.map { it.bookFile.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("继承静读天下 (.Moon+) 书库", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val poCount = preview.candidates.count { it.hasProgress }
                val anCount = preview.candidates.count { it.hasNotes }
                val skipNote = if (preview.unsupportedCount > 0) "，已跳过 ${preview.unsupportedCount} 个 PDF/暂不支持格式" else ""
                Text(
                    "扫描「${preview.sourceFolderName}」共发现 ${preview.candidates.size} 本可导入电子书" +
                        "（含 $poCount 份阅读进度、$anCount 份笔记高亮$skipNote），过程只读不修改原备份。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (preview.groups.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = activeGroup.isBlank(), onClick = { activeGroup = "" }, label = { Text("全部") })
                        preview.groups.forEach { grp ->
                            FilterChip(selected = activeGroup == grp, onClick = { activeGroup = grp }, label = { Text(grp) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedIds = selectedIds + filtered.map { it.bookFile.id } }) { Text("全选当前") }
                    TextButton(onClick = { selectedIds = selectedIds - filtered.map { it.bookFile.id }.toSet() }) { Text("取消当前") }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.bookFile.id }) { cand ->
                        val checked = cand.bookFile.id in selectedIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedIds = if (checked) selectedIds - cand.bookFile.id else selectedIds + cand.bookFile.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedIds = if (checked) selectedIds - cand.bookFile.id else selectedIds + cand.bookFile.id
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cand.displayTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val tags = listOfNotNull(
                                    cand.shelfGroup.takeIf { it.isNotBlank() },
                                    if (cand.alreadyOnShelf) "已在书架·合并数据" else "新书导入",
                                    if (cand.hasProgress) "有进度" else null,
                                    if (cand.hasNotes) "有笔记" else null
                                ).joinToString(" · ")
                                Text(tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { downloadMissing = !downloadMissing }) {
                    Checkbox(checked = downloadMissing, onCheckedChange = { downloadMissing = it })
                    Text("下载并导入尚未入库的书籍正文", style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { bindSync = !bindSync }) {
                    Checkbox(checked = bindSync, onCheckedChange = { bindSync = it })
                    Text("同时将「${preview.sourceFolderName}」设为默认云端同步目录", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(selectedIds, downloadMissing, bindSync) },
                enabled = selectedIds.isNotEmpty()
            ) { Text("开始继承 (${selectedIds.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
