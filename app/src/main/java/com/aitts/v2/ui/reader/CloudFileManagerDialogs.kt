package com.aitts.v2.ui.reader

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
fun BreadcrumbFilterBar(
    state: CloudFileManagerUiState,
    showSortMenu: Boolean,
    onToggleSortMenu: (Boolean) -> Unit,
    viewModel: CloudFileManagerViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.breadcrumbs.size > 1) {
                IconButton(onClick = viewModel::navigateUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "上级目录", modifier = Modifier.size(18.dp))
                }
            }
            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                state.breadcrumbs.forEachIndexed { idx, crumb ->
                    if (idx > 0) {
                        Text(" › ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { viewModel.navigateToBreadcrumb(idx) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        val color = if (idx == state.breadcrumbs.lastIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(text = crumb.label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
            IconButton(onClick = viewModel::toggleSearchExpanded, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索", modifier = Modifier.size(18.dp))
            }
            FilterChip(selected = state.ebooksOnly, onClick = viewModel::toggleEbooksOnly, label = { Text("电子书") })
            TextButton(onClick = { onToggleSortMenu(true) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                val arrow = if (state.sortAscending) "↑" else "↓"
                Text("${state.sortField.label}$arrow", style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { onToggleSortMenu(false) }) {
                FileSortField.entries.forEach { f ->
                    DropdownMenuItem(text = { Text("按${f.label}") }, onClick = { viewModel.setSortField(f); onToggleSortMenu(false) })
                }
            }
        }
        if (state.searchExpanded) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearch,
                singleLine = true,
                placeholder = { Text("搜索文件名...") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CloudManagerDialogs(
    state: CloudFileManagerUiState,
    viewModel: CloudFileManagerViewModel,
    showNewFolder: Boolean,
    onDismissNewFolder: () -> Unit,
    renameTarget: CloudFileNode?,
    onDismissRename: () -> Unit,
    deleteTarget: CloudFileNode?,
    onDismissDelete: () -> Unit,
    context: Context
) {
    if (showNewFolder) {
        TextInputDialog("新建文件夹", "", "文件夹名称", { onDismissNewFolder(); viewModel.createFolder(it) }, onDismissNewFolder)
    }
    renameTarget?.let { target ->
        TextInputDialog("重命名", target.name, "新名称", { onDismissRename(); viewModel.renameNode(target, it) }, onDismissRename)
    }
    deleteTarget?.let { target ->
        DeleteFileConfirmDialog(target, { onDismissDelete(); viewModel.deleteNode(target) }, onDismissDelete)
    }
    if (state.showBatchDeleteConfirm) {
        BatchDeleteConfirmDialog(
            count = state.selectedIds.size,
            onConfirm = viewModel.sync::confirmBatchDelete,
            onDismiss = { viewModel.sync.setBatchDeleteConfirm(false) }
        )
    }
    state.moveTargetNode?.let { node ->
        val folders = state.items.filter { it.isFolder && it.id != node.id }
        MoveFolderDialog(
            node = node,
            folders = folders,
            onMoveTo = viewModel.sync::moveDriveNodeToFolder,
            onDismiss = { viewModel.sync.openMoveDialog(null) }
        )
    }
    if (state.showSyncOptionsSheet) {
        val curSyncLabel = when (state.source) {
            FileSourceTab.LOCAL -> state.dedicatedDirLabel
            FileSourceTab.GOOGLE_DRIVE -> state.driveSyncFolderLabel
            FileSourceTab.WEBDAV -> state.webDavSyncFolderLabel
        }
        SyncOptionsSheet(
            source = state.source,
            currentSyncFolderLabel = curSyncLabel,
            currentBrowseFolderLabel = state.breadcrumbs.lastOrNull()?.label.orEmpty(),
            onSetCurrentFolderAsSyncRoot = viewModel.sync::setCurrentFolderAsSyncRoot,
            onRunSync = viewModel.sync::startSyncFlow,
            onDismiss = { viewModel.sync.setSyncOptionsSheet(false) }
        )
    }
    state.pendingSyncPlan?.let { plan ->
        SyncDeleteConfirmDialog(plan = plan, onConfirm = viewModel.sync::confirmSyncPlan, onCancel = viewModel.sync::dismissPendingSyncPlan)
    }
    state.moonPreview?.let { preview ->
        MoonMigrationDialog(preview = preview, onImport = viewModel.sync::executeMoonImport, onDismiss = viewModel.sync::dismissMoonPreview)
    }
    if (state.showDevAuthSheet) {
        DevTokenConnectDialog(
            clientId = state.devClientId, clientSecret = state.devClientSecret,
            refreshToken = state.devRefreshToken, accessToken = state.devAccessToken,
            onUpdate = { cid, sec, ref, acc -> viewModel.auth.updateDevInputs(cid = cid, sec = sec, ref = ref, tok = acc) },
            onOpenPlayground = { viewModel.auth.openExternalUrl(context, "https://developers.google.com/oauthplayground") },
            onConnect = viewModel.auth::connectGoogleViaToken,
            onDismiss = { viewModel.auth.setDevAuthSheet(showDev = false) }
        )
    }
    if (state.showPasteCredsSheet) {
        PasteCredentialsDialog(
            onImportText = viewModel.auth::importCredentialsFromText,
            onDismiss = { viewModel.auth.setDevAuthSheet(showPaste = false) }
        )
    }
}

@Composable
fun WebDavConnectCard(
    url: String,
    user: String,
    pass: String,
    rootPath: String,
    isBusy: Boolean,
    onUpdate: (String?, String?, String?, String?) -> Unit,
    onConnect: () -> Unit
) {
    InkPaperCard(padding = 16.dp) {
        Text("连接 WebDAV 网盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "原生兼容 Alist / OpenList、坚果云、Nextcloud、群晖 NAS 等标准 WebDAV 服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = url,
            onValueChange = { onUpdate(it, null, null, null) },
            label = { Text("服务器地址 (如 https://dav.example.com/dav)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = user,
                onValueChange = { onUpdate(null, it, null, null) },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { onUpdate(null, null, it, null) },
                label = { Text("应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = rootPath,
            onValueChange = { onUpdate(null, null, null, it) },
            label = { Text("默认根目录") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onConnect, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("校验并连接 WebDAV")
        }
    }
}

@Composable
fun TextInputDialog(title: String, initialValue: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun DeleteFileConfirmDialog(node: CloudFileNode, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除确认") },
        text = { Text("确定要删除「${node.name}」吗？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun MoveFolderDialog(node: CloudFileNode, folders: List<CloudFileNode>, onMoveTo: (CloudFileNode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("将「${node.name}」移至") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                folders.forEach { folder ->
                    OutlinedButton(onClick = { onMoveTo(folder) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
