package com.aitts.v2.ui.reader

import android.app.Activity
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.engine.cloud.CloudFileNode
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
        viewModel.sync.importMultiViaSaf(uris) { rec -> rec?.let { onBookImported(it.id) } }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.sync.uploadFilesToCurrentFolder(uris)
    }
    val safTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::bindCustomSyncFolder)
    }

    BackHandler(enabled = state.selectedIds.isNotEmpty() || state.breadcrumbs.size > 1) {
        if (state.selectedIds.isNotEmpty()) viewModel.clearSelection() else viewModel.navigateUp()
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    val isReady = state.source == FileSourceTab.LOCAL ||
        (state.source == FileSourceTab.GOOGLE_DRIVE && state.driveConnected) ||
        (state.source == FileSourceTab.WEBDAV && state.webDavConnected)

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(if (state.selectedIds.isNotEmpty()) "已选 ${state.selectedIds.size} 项" else "文件与网盘中心") },
                navigationIcon = {
                    if (state.selectedIds.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Outlined.Close, contentDescription = "取消多选") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                    }
                },
                actions = {
                    if (state.selectedIds.isNotEmpty()) {
                        IconButton(onClick = viewModel::selectAllVisible) { Icon(Icons.Outlined.SelectAll, contentDescription = "全选") }
                        IconButton(onClick = viewModel.sync::batchImportSelected) {
                            Icon(Icons.Outlined.Download, contentDescription = "批量导入书架")
                        }
                        IconButton(onClick = { viewModel.sync.setBatchDeleteConfirm(true) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "批量删除")
                        }
                    } else if (isReady) {
                        IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.DriveFolderUpload, contentDescription = "上传到当前目录")
                        }
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                        }
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") }
                    } else {
                        IconButton(onClick = { safMultiPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "系统文件直选")
                        }
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
                val prog = state.busyProgress
                if (prog != null) {
                    LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.busyLabel.isNotBlank()) {
                    Text(
                        text = state.busyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            if (isReady) {
                val syncTarget = when (state.source) {
                    FileSourceTab.LOCAL -> state.dedicatedDirLabel
                    FileSourceTab.GOOGLE_DRIVE -> state.driveSyncFolderLabel
                    FileSourceTab.WEBDAV -> state.webDavSyncFolderLabel
                }
                val titleLabel = when (state.source) {
                    FileSourceTab.LOCAL -> state.dedicatedDirLabel
                    FileSourceTab.GOOGLE_DRIVE -> state.driveAccount
                    FileSourceTab.WEBDAV -> state.webDavEndpoint
                }
                CloudStatusActionStrip(
                    source = state.source,
                    title = titleLabel,
                    syncTargetLabel = syncTarget,
                    onOpenSync = { viewModel.sync.setSyncOptionsSheet(true) },
                    onScanMoon = if (state.source == FileSourceTab.GOOGLE_DRIVE) {
                        { viewModel.sync.scanMoonReaderLibrary() }
                    } else null,
                    secondaryActionLabel = if (state.source == FileSourceTab.LOCAL) "更换目录" else "断开",
                    onSecondaryAction = {
                        when (state.source) {
                            FileSourceTab.LOCAL -> safTreePicker.launch(null)
                            FileSourceTab.GOOGLE_DRIVE -> viewModel.auth.disconnectGoogle()
                            FileSourceTab.WEBDAV -> viewModel.auth.disconnectWebDav()
                        }
                    }
                )
                BreadcrumbFilterBar(
                    state = state,
                    showSortMenu = showSortMenu,
                    onToggleSortMenu = { showSortMenu = it },
                    viewModel = viewModel
                )
            }

            if (!state.errorMessage.isNullOrBlank() && isReady) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            val dirFolders = remember(state.items) { state.items.filter { it.isFolder } }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!isReady) {
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (state.source == FileSourceTab.GOOGLE_DRIVE) {
                                val (pkgName, sha1) = remember { viewModel.auth.getPackageAndSha1Info() }
                                GoogleDriveAuthCard(
                                    clientId = state.devClientId, clientSecret = state.devClientSecret,
                                    redirectUri = state.devRedirectUri, errorMessage = state.errorMessage, isBusy = state.isBusy,
                                    isBrowserWaiting = state.isBrowserAuthWaiting, pendingAuthUrl = state.pendingAuthUrl,
                                    pastedCallbackUrl = state.pastedCallbackUrl, packageName = pkgName, signingSha1 = sha1,
                                    onUpdateClient = { id, sec, redir ->
                                        viewModel.auth.updateDevInputs(cid = id, sec = sec, redir = redir)
                                    },
                                    onUpdatePastedUrl = { pasted -> viewModel.auth.updateDevInputs(pastedUrl = pasted) },
                                    onImportJson = { jsonPicker.launch(arrayOf("*/*")) },
                                    onOpenPasteDialog = { viewModel.auth.setDevAuthSheet(showPaste = true) },
                                    onOpenCloudConsole = {
                                        viewModel.auth.openExternalUrl(context, "https://console.cloud.google.com/apis/credentials")
                                    },
                                    onBrowserSignIn = { ext -> viewModel.auth.startBrowserPkceGoogleAuth(context, ext) },
                                    onReopenExternalBrowser = { viewModel.auth.reopenPendingAuthInExternalBrowser(context) },
                                    onCopyAuthUrl = { viewModel.auth.copyPendingAuthUrl(context) },
                                    onSubmitManualCallback = viewModel.auth::submitManualCallbackUrl,
                                    onCancelBrowserSignIn = viewModel.auth::cancelBrowserPkceAuth,
                                    onOneTapSignIn = {
                                        if (activity != null) viewModel.auth.startOneTapGoogleAuth(activity) { authLauncher.launch(it) }
                                    },
                                    onCopySigningInfo = { viewModel.auth.copyPackageAndSha1(context) },
                                    onOpenSafPicker = { safMultiPicker.launch(arrayOf("*/*")) },
                                    onOpenDevTokenSheet = { viewModel.auth.setDevAuthSheet(showDev = true) }
                                )
                            } else {
                                WebDavConnectCard(
                                    url = state.webDavUrlInput, user = state.webDavUserInput,
                                    pass = state.webDavPassInput, rootPath = state.webDavRootInput, isBusy = state.isBusy,
                                    onUpdate = viewModel.auth::updateWebDavInputs,
                                    onConnect = { viewModel.auth.connectWebDav { viewModel.refresh() } }
                                )
                            }
                        }
                    }
                } else if (state.items.isEmpty() && !state.isLoading) {
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InkPaperCard(padding = 20.dp) {
                                Text(
                                    text = "当前目录暂无匹配文件，可点上方上传按钮传书或取消「电子书」查看全部文件。",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(onClick = { safMultiPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("从系统文件选择器直接导入书籍")
                                }
                            }
                        }
                    }
                } else {
                    items(state.items, key = { it.id }) { node ->
                        val sizeLabel = if (node.isFolder) "目录" else Formatter.formatShortFileSize(context, node.sizeBytes)
                        CloudFileListItem(
                            node = node,
                            source = state.source,
                            selected = node.id in state.selectedIds,
                            selectionMode = state.selectedIds.isNotEmpty(),
                            isImported = viewModel.isNodeImported(node),
                            formattedSize = sizeLabel,
                            hasFoldersInDir = dirFolders.any { it.id != node.id },
                            onToggleSelect = { viewModel.toggleSelect(node.id) },
                            onClick = {
                                if (node.isFolder) viewModel.openFolder(node) else viewModel.sync.importFileNode(node, onBookImported)
                            },
                            onDownloadLocal = { viewModel.sync.downloadNodeToLocalSync(node) },
                            onScanMoonHere = if (state.source == FileSourceTab.GOOGLE_DRIVE) {
                                {
                                    val parent = state.breadcrumbs.lastOrNull()
                                    viewModel.sync.scanMoonReaderLibrary(parent?.idOrPath, parent?.label)
                                }
                            } else null,
                            onMoveToFolder = { viewModel.sync.openMoveDialog(node) },
                            onRename = { renameTarget = node },
                            onDelete = { deleteTarget = node }
                        )
                    }
                }
            }
        }
    }

    CloudManagerDialogs(
        state = state, viewModel = viewModel,
        showNewFolder = showNewFolder, onDismissNewFolder = { showNewFolder = false },
        renameTarget = renameTarget, onDismissRename = { renameTarget = null },
        deleteTarget = deleteTarget, onDismissDelete = { deleteTarget = null },
        context = context
    )
}
