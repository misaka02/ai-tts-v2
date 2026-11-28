package com.aitts.v2.ui.reader

import android.app.Activity
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.engine.cloud.SyncDirection
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFileManagerScreen(
    viewModel: CloudFileManagerViewModel,
    onBack: () -> Unit,
    onBookImported: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CloudFileNode?>(null) }
    var deleteTarget by remember { mutableStateOf<CloudFileNode?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (activity != null) viewModel.auth.handleGoogleAuthResult(activity, res.data)
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.auth.importClientSecretJson(context, it) }
    }
    val safMultiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importMultiViaSaf(uris) { rec -> rec?.let { onBookImported(it.id) } }
    }
    val safTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::bindCustomSyncFolder)
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("文件与网盘中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { safMultiPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "系统文件/网盘直选")
                    }
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = state.source.ordinal) {
                FileSourceTab.entries.forEach { tab ->
                    Tab(selected = state.source == tab, onClick = { viewModel.selectSource(tab) }, text = { Text(tab.label) })
                }
            }

            if (state.isBusy || state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.busyLabel.isNotBlank()) {
                    Text(
                        text = state.busyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    when (state.source) {
                        FileSourceTab.LOCAL -> InkPaperCard(padding = 12.dp) {
                            Text("本地专有目录：${state.dedicatedDirLabel}", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.runSync(SyncDirection.TWO_WAY) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("扫描同步到书架")
                                }
                                OutlinedButton(onClick = { safTreePicker.launch(null) }) { Text("更换目录") }
                            }
                        }
                        FileSourceTab.GOOGLE_DRIVE -> if (!state.driveConnected) {
                            val (pkgName, sha1) = remember { viewModel.auth.getPackageAndSha1Info() }
                            GoogleDriveAuthCard(
                                clientId = state.devClientId,
                                clientSecret = state.devClientSecret,
                                errorMessage = state.errorMessage,
                                isBusy = state.isBusy,
                                isBrowserWaiting = state.isBrowserAuthWaiting,
                                pendingAuthUrl = state.pendingAuthUrl,
                                pastedCallbackUrl = state.pastedCallbackUrl,
                                packageName = pkgName,
                                signingSha1 = sha1,
                                onUpdateClient = { id, sec -> viewModel.auth.updateDevInputs(cid = id, sec = sec) },
                                onUpdatePastedUrl = { pasted -> viewModel.auth.updateDevInputs(pastedUrl = pasted) },
                                onImportJson = { jsonPicker.launch(arrayOf("application/json", "*/*")) },
                                onBrowserSignIn = { viewModel.auth.startBrowserPkceGoogleAuth(context) },
                                onCopyAuthUrl = { viewModel.auth.copyPendingAuthUrl(context) },
                                onSubmitManualCallback = viewModel.auth::submitManualCallbackUrl,
                                onCancelBrowserSignIn = viewModel.auth::cancelBrowserPkceAuth,
                                onOneTapSignIn = {
                                    if (activity != null) {
                                        viewModel.auth.startOneTapGoogleAuth(activity) { authLauncher.launch(it) }
                                    }
                                },
                                onCopySigningInfo = { viewModel.auth.copyPackageAndSha1(context) },
                                onOpenSafPicker = { safMultiPicker.launch(arrayOf("*/*")) },
                                onOpenDevTokenSheet = { viewModel.auth.setDevAuthSheet(true) }
                            )
                        } else {
                            CloudConnectedBar(
                                label = "已连接：${state.driveAccount}",
                                onSync = { viewModel.runSync(SyncDirection.TWO_WAY) },
                                onDisconnect = viewModel.auth::disconnectGoogle
                            )
                        }
                        FileSourceTab.WEBDAV -> if (!state.webDavConnected) {
                            WebDavConnectCard(
                                url = state.webDavUrlInput,
                                user = state.webDavUserInput,
                                pass = state.webDavPassInput,
                                rootPath = state.webDavRootInput,
                                isBusy = state.isBusy,
                                onUpdate = viewModel.auth::updateWebDavInputs,
                                onConnect = { viewModel.auth.connectWebDav { viewModel.refresh() } }
                            )
                        } else {
                            CloudConnectedBar(
                                label = "WebDAV：${state.webDavEndpoint}",
                                onSync = { viewModel.runSync(SyncDirection.TWO_WAY) },
                                onDisconnect = viewModel.auth::disconnectWebDav
                            )
                        }
                    }
                }

                val isReady = state.source == FileSourceTab.LOCAL ||
                    (state.source == FileSourceTab.GOOGLE_DRIVE && state.driveConnected) ||
                    (state.source == FileSourceTab.WEBDAV && state.webDavConnected)

                if (isReady) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.breadcrumbs.forEachIndexed { idx, crumb ->
                                    FilterChip(
                                        selected = idx == state.breadcrumbs.lastIndex,
                                        onClick = { viewModel.navigateToBreadcrumb(idx) },
                                        label = { Text(crumb.label) }
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = viewModel::updateSearch,
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    placeholder = { Text("过滤文件名") },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(selected = state.ebooksOnly, onClick = viewModel::toggleEbooksOnly, label = { Text("电子书") })
                                TextButton(onClick = { showSortMenu = true }) { Text(state.sortField.label) }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    FileSortField.entries.forEach { f ->
                                        DropdownMenuItem(
                                            text = { Text(f.label) },
                                            onClick = { viewModel.setSortField(f); showSortMenu = false }
                                        )
                                    }
                                }
                            }
                            if (state.selectedIds.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("已选 ${state.selectedIds.size} 项", style = MaterialTheme.typography.labelMedium)
                                    Button(onClick = viewModel::batchImportSelected) { Text("批量导入书架") }
                                    TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                                }
                            }
                        }
                    }

                    if (!state.errorMessage.isNullOrBlank()) {
                        item {
                            InkPaperCard(padding = 12.dp) {
                                Text(
                                    text = state.errorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    if (state.items.isEmpty() && !state.isLoading) {
                        item {
                            InkPaperCard(padding = 20.dp) {
                                Text("当前目录暂无匹配文件，可直接点右上角目录按钮从手机或第三方网盘多选直导。")
                            }
                        }
                    } else {
                        items(state.items, key = { it.id }) { node ->
                            val sizeLabel = if (node.isFolder) {
                                "目录"
                            } else {
                                Formatter.formatShortFileSize(context, node.sizeBytes)
                            }
                            FileNodeRow(
                                node = node,
                                selected = node.id in state.selectedIds,
                                formattedSize = sizeLabel,
                                onToggleSelect = { viewModel.toggleSelect(node.id) },
                                onClick = {
                                    if (node.isFolder) viewModel.openFolder(node)
                                    else viewModel.importFileNode(node, onBookImported)
                                },
                                onRename = { renameTarget = node },
                                onDelete = { deleteTarget = node }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog("新建文件夹", "", "文件夹名称", { showNewFolder = false; viewModel.createFolder(it) }, { showNewFolder = false })
    }
    renameTarget?.let { target ->
        TextInputDialog("重命名", target.name, "新名称", { renameTarget = null; viewModel.renameNode(target, it) }, { renameTarget = null })
    }
    deleteTarget?.let { target ->
        DeleteFileConfirmDialog(target, { deleteTarget = null; viewModel.deleteNode(target) }, { deleteTarget = null })
    }
    if (state.showDevAuthSheet) {
        DevTokenConnectDialog(
            state.devAccessToken,
            { viewModel.auth.updateDevInputs(tok = it) },
            viewModel.auth::connectGoogleViaToken
        ) { viewModel.auth.setDevAuthSheet(false) }
    }
}
