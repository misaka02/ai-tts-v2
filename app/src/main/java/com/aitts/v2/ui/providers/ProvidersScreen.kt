package com.aitts.v2.ui.providers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone
import java.util.UUID

@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel,
    onEditProvider: (String) -> Unit
) {
    val context = LocalContext.current
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showOfflineSheet by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<TtsProviderConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<TtsProviderConfig?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("声库与引擎", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "已配置 ${providers.size} 个合成节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showOfflineSheet = true }) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("离线库")
                    }
                    Button(onClick = { onEditProvider("node_${UUID.randomUUID().toString().take(8)}") }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新增")
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::testAllLatencies, enabled = !state.isBatchTesting, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.isBatchTesting) "测速中..." else "批量测速")
                }
                OutlinedButton(onClick = viewModel::sortByLatency, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("延迟排序")
                }
                OutlinedButton(onClick = { showImportDialog = true }) { Text("导入") }
            }
        }

        items(providers, key = { it.id }) { provider ->
            ProviderRowCard(
                provider = provider,
                isActive = provider.id == settings.activeProviderId,
                ping = state.latenciesMs[provider.id],
                onSelect = { viewModel.performAction(provider.id, ProviderAction.SET_ACTIVE) },
                onToggleEnabled = { viewModel.toggleEnabled(provider, it) },
                onEdit = { onEditProvider(provider.id) },
                onPin = { viewModel.performAction(provider.id, ProviderAction.PIN_TOP) },
                onMoveUp = { viewModel.performAction(provider.id, ProviderAction.MOVE_UP) },
                onMoveDown = { viewModel.performAction(provider.id, ProviderAction.MOVE_DOWN) },
                onDuplicate = { viewModel.performAction(provider.id, ProviderAction.DUPLICATE) },
                onShare = { shareTarget = provider },
                onDelete = { deleteTarget = provider }
            )
        }
    }

    if (showOfflineSheet) {
        OfflineModelsSheet(viewModel = viewModel, onDismiss = { showOfflineSheet = false })
    }
    ProvidersDialogs(
        context = context,
        showImportDialog = showImportDialog,
        shareTarget = shareTarget,
        deleteTarget = deleteTarget,
        onImportToken = { viewModel.importShareToken(it); showImportDialog = false },
        onDismissImport = { showImportDialog = false },
        onExportShare = { target, includeKeys -> viewModel.exportShareToken(target, includeKeys) },
        onDismissShare = { shareTarget = null },
        onConfirmDelete = { viewModel.performAction(it.id, ProviderAction.DELETE); deleteTarget = null },
        onDismissDelete = { deleteTarget = null }
    )
}

@Composable
private fun ProviderRowCard(
    provider: TtsProviderConfig,
    isActive: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    InkPaperCard(highlighted = isActive, padding = 14.dp, onClick = onSelect) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isActive) {
                Box(
                    modifier = Modifier.width(3.dp).height(34.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    if (isActive) InkStatusPill("当前首选", PillTone.ACCENT)
                }
                Text(
                    text = "${provider.type.displayName} · ${provider.voiceId.ifBlank { "默认音色" }}" +
                        (ping?.let { if (it >= 0) " · ${it}ms" else " · 超时" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            Switch(checked = provider.enabled, onCheckedChange = onToggleEnabled)
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("置顶") }, onClick = { menuExpanded = false; onPin() })
                    DropdownMenuItem(text = { Text("上移") }, onClick = { menuExpanded = false; onMoveUp() })
                    DropdownMenuItem(text = { Text("下移") }, onClick = { menuExpanded = false; onMoveDown() })
                    DropdownMenuItem(text = { Text("复制节点") }, onClick = { menuExpanded = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("分享口令") }, onClick = { menuExpanded = false; onShare() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ProvidersDialogs(
    context: Context,
    showImportDialog: Boolean,
    shareTarget: TtsProviderConfig?,
    deleteTarget: TtsProviderConfig?,
    onImportToken: (String) -> Unit,
    onDismissImport: () -> Unit,
    onExportShare: (TtsProviderConfig, Boolean) -> String,
    onDismissShare: () -> Unit,
    onConfirmDelete: (TtsProviderConfig) -> Unit,
    onDismissDelete: () -> Unit
) {
    if (showImportDialog) {
        var tokenText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissImport,
            title = { Text("导入配置口令 / JSON") },
            text = {
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("粘贴 aitts://provider?data=... 或 JSON") },
                    minLines = 3
                )
            },
            confirmButton = { Button(onClick = { onImportToken(tokenText) }) { Text("导入") } },
            dismissButton = { TextButton(onClick = onDismissImport) { Text("取消") } }
        )
    }

    shareTarget?.let { target ->
        var includeKeys by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissShare,
            title = { Text("分享节点：${target.name}") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeKeys, onCheckedChange = { includeKeys = it })
                    Text("包含 API Key (默认已脱敏)")
                }
            },
            confirmButton = {
                Button(onClick = {
                    val token = onExportShare(target, includeKeys)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aitts_provider", token))
                    Toast.makeText(context, "口令已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    onDismissShare()
                }) { Text("复制口令") }
            },
            dismissButton = { TextButton(onClick = onDismissShare) { Text("取消") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除节点？") },
            text = { Text("确认删除「${target.name}」吗？") },
            confirmButton = { Button(onClick = { onConfirmDelete(target) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("取消") } }
        )
    }
}
