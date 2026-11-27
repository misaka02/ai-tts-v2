package com.aitts.v2.ui.reader

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.engine.cloud.DriveFileItem
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
fun GoogleDriveScreen(
    viewModel: GoogleDriveViewModel,
    onBack: () -> Unit,
    onBookImported: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth by viewModel.authFlow.collectAsStateWithLifecycle()
    val existingNames by viewModel.existingBookNamesFlow.collectAsStateWithLifecycle()

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Google 网盘", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (auth.isConnected) auth.accountEmail.ifBlank { "已授权连接" } else "只读导入个人云端电子书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (auth.isConnected) {
                IconButton(onClick = { viewModel.refreshCurrentFolder() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                TextButton(onClick = viewModel::disconnect) { Text("断开") }
            }
        }

        if (!auth.isConnected) {
            DriveConnectSection(
                state = state,
                onSelectTab = viewModel::selectAuthTab,
                onUpdateInputs = viewModel::updateCredentialInputs,
                onStartBrowserOAuth = {
                    viewModel.startBrowserOAuth { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            Toast.makeText(context, "未找到可用浏览器: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCancelOAuth = viewModel::cancelBrowserOAuth,
                onConnectDirect = viewModel::connectWithTokens
            )
        } else {
            DriveFileBrowserSection(
                state = state,
                existingBookNames = existingNames,
                onNavigateUp = viewModel::navigateUpFolder,
                onSearchChange = viewModel::updateSearch,
                onToggleEbooksOnly = viewModel::toggleEbooksOnly,
                onOpenFolder = viewModel::openFolder,
                onImportFile = { item -> viewModel.downloadAndImport(item, onBookImported) },
                onLoadMore = { viewModel.refreshCurrentFolder(appendNextPage = true) }
            )
        }
    }
}

@Composable
private fun DriveFileBrowserSection(
    state: GoogleDriveUiState,
    existingBookNames: Set<String>,
    onNavigateUp: () -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleEbooksOnly: () -> Unit,
    onOpenFolder: (DriveFileItem) -> Unit,
    onImportFile: (DriveFileItem) -> Unit,
    onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.folderStack.joinToString(" / ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.folderStack.size > 1) {
                    TextButton(onClick = onNavigateUp) { Text("上一级") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("搜索云端书名") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.ebooksOnly,
                    onClick = onToggleEbooksOnly,
                    label = { Text("仅电子书") },
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.items.isEmpty() && !state.isLoading) {
                item {
                    InkPaperCard(padding = 16.dp) {
                        Text("当前目录下没有符合条件的电子书或文件夹。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                DriveItemRow(
                    item = item,
                    alreadyImported = existingBookNames.contains(item.name.substringBeforeLast('.').lowercase()),
                    isDownloading = state.downloadingFileId == item.id,
                    downloadProgress = state.downloadProgress,
                    onClick = {
                        if (item.isFolder) onOpenFolder(item) else onImportFile(item)
                    }
                )
            }
            if (state.nextPageToken != null && !state.isLoading) {
                item {
                    OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("加载更多文件")
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveItemRow(
    item: DriveFileItem,
    alreadyImported: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit
) {
    InkPaperCard(padding = 14.dp, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (item.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!item.isFolder && item.sizeBytes > 0L) {
                        val sizeText = if (item.sizeBytes >= 1024 * 1024) {
                            "${"%.1f".format(item.sizeBytes / (1024f * 1024f))} MB"
                        } else {
                            "${(item.sizeBytes / 1024L).coerceAtLeast(1)} KB"
                        }
                        Text(sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (alreadyImported) {
                        InkStatusPill(label = "已在书架", tone = PillTone.SUCCESS)
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            if (!item.isFolder && !isDownloading) {
                Icon(
                    imageVector = if (alreadyImported) Icons.Outlined.CloudDone else Icons.Outlined.CloudDownload,
                    contentDescription = "下载导入",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
