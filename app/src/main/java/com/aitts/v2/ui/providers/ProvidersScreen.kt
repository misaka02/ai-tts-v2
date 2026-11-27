package com.aitts.v2.ui.providers

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.aitts.v2.model.EngineCategory
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.ProviderTemplate
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkSectionHeader
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

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
    var showTemplateSheet by remember { mutableStateOf(false) }
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
                    Button(onClick = { showTemplateSheet = true }) {
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
            }
        }

        items(providers, key = { it.id }) { provider ->
            ProviderRowCard(
                provider = provider,
                isActive = provider.id == settings.activeProviderId,
                ping = state.latenciesMs[provider.id],
                isTestingPreview = state.isTestingSingle,
                onSelect = { viewModel.performAction(provider.id, ProviderAction.SET_ACTIVE) },
                onPreview = { viewModel.testAndPreview(provider, viewModel.getCredentials(provider.id)) },
                onToggleEnabled = { viewModel.toggleEnabled(provider, it) },
                onEdit = { onEditProvider(provider.id) },
                onAction = { viewModel.performAction(provider.id, it) },
                onRequestDelete = { deleteTarget = provider }
            )
        }
    }

    if (showOfflineSheet) {
        OfflineModelsSheet(viewModel = viewModel, onDismiss = { showOfflineSheet = false })
    }
    if (showTemplateSheet) {
        VendorTemplatePickerSheet(
            onPickTemplate = { tpl ->
                val newId = viewModel.createProviderFromTemplate(tpl)
                showTemplateSheet = false
                onEditProvider(newId)
            },
            onDismiss = { showTemplateSheet = false }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除节点？") },
            text = { Text("确认删除「${target.name}」吗？") },
            confirmButton = {
                Button(onClick = { viewModel.performAction(target.id, ProviderAction.DELETE); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProviderRowCard(
    provider: TtsProviderConfig,
    isActive: Boolean,
    ping: Long?,
    isTestingPreview: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onAction: (ProviderAction) -> Unit,
    onRequestDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val desc = ProviderDescriptorRegistry.descriptorFor(provider.type)

    InkPaperCard(highlighted = isActive, padding = 14.dp, onClick = onSelect) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isActive) {
                Box(modifier = Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    if (isActive) InkStatusPill("首选", PillTone.ACCENT)
                }
                Text(
                    text = "${desc.category.displayName} · ${provider.voiceId.ifBlank { "默认音色" }}" +
                        (ping?.let { if (it >= 0) " · ${it}ms" else " · 超时" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPreview, enabled = !isTestingPreview) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "试听", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            Switch(checked = provider.enabled, onCheckedChange = onToggleEnabled)
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("置顶") }, onClick = { menuExpanded = false; onAction(ProviderAction.PIN_TOP) })
                    DropdownMenuItem(text = { Text("上移") }, onClick = { menuExpanded = false; onAction(ProviderAction.MOVE_UP) })
                    DropdownMenuItem(text = { Text("下移") }, onClick = { menuExpanded = false; onAction(ProviderAction.MOVE_DOWN) })
                    DropdownMenuItem(text = { Text("复制节点") }, onClick = { menuExpanded = false; onAction(ProviderAction.DUPLICATE) })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuExpanded = false; onRequestDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VendorTemplatePickerSheet(
    onPickTemplate: (ProviderTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    val grouped = remember { ProviderDescriptorRegistry.templates.groupBy { it.category } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("选择引擎模板新建节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineCategory.entries.forEach { cat ->
                    val list = grouped[cat].orEmpty()
                    if (list.isNotEmpty()) {
                        item { InkSectionHeader(cat.displayName) }
                        items(list, key = { it.id }) { tpl ->
                            InkPaperCard(padding = 12.dp, onClick = { onPickTemplate(tpl) }) {
                                Text(tpl.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    tpl.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
