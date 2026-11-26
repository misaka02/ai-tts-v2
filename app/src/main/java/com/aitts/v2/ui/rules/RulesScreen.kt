package com.aitts.v2.ui.rules

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.RuleCategory

@Composable
fun RulesScreen(viewModel: RulesViewModel) {
    val context = LocalContext.current
    val rules by viewModel.rulesFlow.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var editingRule by remember { mutableStateOf<ReplacementRule?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val filtered = remember(rules, state.selectedCategory) {
        val cat = state.selectedCategory
        if (cat == null) rules else rules.filter { it.category == cat }
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
                    Text("发音规则流水线", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "共 ${rules.size} 条替换规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新增")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("实时预处理测试", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.previewInput,
                        onValueChange = viewModel::updatePreviewInput,
                        label = { Text("输入测试原文") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "输出: ${viewModel.previewProcessed(state.previewInput)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::importXianxiaPack, modifier = Modifier.weight(1f)) { Text("+修仙多音字") }
                OutlinedButton(onClick = viewModel::importNovelSymbolsPack, modifier = Modifier.weight(1f)) { Text("+符号净化") }
                OutlinedButton(onClick = { viewModel.batchToggle(true) }) { Text("全开") }
                OutlinedButton(onClick = { viewModel.batchToggle(false) }) { Text("全关") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = state.selectedCategory == null, onClick = { viewModel.selectCategory(null) }, label = { Text("全部") })
                RuleCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = state.selectedCategory == cat,
                        onClick = { viewModel.selectCategory(cat) },
                        label = { Text(cat.label) }
                    )
                }
            }
        }

        items(filtered, key = { it.id }) { rule ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().clickable { editingRule = rule }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = rule.description.ifBlank { "${rule.pattern} -> ${rule.replacement}" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "[${rule.category.label}] ${if (rule.isRegex) "正则" else "字面"}: ${rule.pattern} -> ${rule.replacement}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { editingRule = rule }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRule(rule, it) })
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RuleEditDialog(
            initial = ReplacementRule(id = "", pattern = "", replacement = "", category = RuleCategory.CUSTOM),
            onSave = { viewModel.saveRule(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false }
        )
    }

    editingRule?.let { target ->
        RuleEditDialog(
            initial = target,
            onSave = { viewModel.saveRule(it); editingRule = null },
            onDismiss = { editingRule = null }
        )
    }
}
