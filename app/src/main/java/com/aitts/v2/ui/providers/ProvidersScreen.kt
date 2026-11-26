package com.aitts.v2.ui.providers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
        modifier = Modifier.fillMaxSize(),
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
                    Text("语音引擎矩阵", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "已配置 ${providers.size} 个节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showOfflineSheet = true }) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("离线库")
                    }
                    Button(onClick = { onEditProvider("new_${System.currentTimeMillis() % 10000}") }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新增")
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::testAllLatencies, enabled = !state.isBatchTesting, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.isBatchTesting) "测速中..." else "批量测速")
                }
                OutlinedButton(onClick = viewModel::sortByLatency, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("按延迟排序")
                }
                OutlinedButton(onClick = { showImportDialog = true }) { Text("口令导入") }
            }
        }

        items(providers, key = { it.id }) { provider ->
            val isActive = provider.id == settings.activeProviderId
            val ping = state.latenciesMs[provider.id]
            Card(
                shape = RoundedCornerShape(16.dp),
                border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setActive(provider.id) }
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            if (isActive) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${provider.type.displayName} · ${provider.voiceId.ifBlank { "默认" }}" +
                                        (ping?.let { if (it >= 0) " · ${it}ms" else " · 失败" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(checked = provider.enabled, onCheckedChange = { viewModel.toggleEnabled(provider, it) })
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { viewModel.move(provider.id, -1) }) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.move(provider.id, 1) }) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.pinToTop(provider.id) }) {
                            Icon(Icons.Default.VerticalAlignTop, contentDescription = "置顶", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.duplicate(provider.id) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { shareTarget = provider }) {
                            Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onEditProvider(provider.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deleteTarget = provider }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showOfflineSheet) {
        OfflineModelsSheet(viewModel = viewModel, onDismiss = { showOfflineSheet = false })
    }

    if (showImportDialog) {
        var tokenText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入配置口令 / JSON") },
            text = {
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("粘贴 aitts://provider?data=... 或 JSON") },
                    minLines = 3
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.importShareToken(tokenText); showImportDialog = false }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }

    shareTarget?.let { target ->
        var includeKeys by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { shareTarget = null },
            title = { Text("分享配置：${target.name}") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeKeys, onCheckedChange = { includeKeys = it })
                    Text("包含 API Key (默认已脱敏，勾选需注意安全)")
                }
            },
            confirmButton = {
                Button(onClick = {
                    val token = viewModel.exportShareToken(target, includeKeys)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aitts_provider", token))
                    Toast.makeText(context, "口令已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    shareTarget = null
                }) { Text("复制口令") }
            },
            dismissButton = { TextButton(onClick = { shareTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除引擎？") },
            text = { Text("确认删除「${target.name}」吗？") },
            confirmButton = {
                Button(onClick = { viewModel.delete(target.id); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
