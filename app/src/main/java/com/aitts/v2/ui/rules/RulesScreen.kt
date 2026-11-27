package com.aitts.v2.ui.rules

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.engine.text.TextPipeline
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.RuleCategory
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkSectionHeader
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
fun RulesScreen(viewModel: RulesViewModel) {
    val context = LocalContext.current
    val rules by viewModel.rulesFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
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
    val processedPreview = remember(state.previewInput, rules, settings.isNumberNormalizationEnabled) {
        TextPipeline.preprocessFlat(
            rawText = state.previewInput,
            rules = rules,
            enableNumbers = settings.isNumberNormalizationEnabled
        )
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
                    Text("发音词典", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "共 ${rules.size} 条净化与纠音规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新增")
                }
            }
        }

        item {
            InkSectionHeader("实时预处理工作台")
            Spacer(Modifier.size(6.dp))
            InkPaperCard(padding = 14.dp) {
                OutlinedTextField(
                    value = state.previewInput,
                    onValueChange = viewModel::updatePreviewInput,
                    label = { Text("输入测试原文") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = settings.isNumberNormalizationEnabled,
                    onClick = { viewModel.toggleNumberNormalization(!settings.isNumberNormalizationEnabled) },
                    label = { Text("中文数字规整 (主要供轻量离线 VITS 模型开启)") }
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("管线输出预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(processedPreview, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::importNovelSymbolsPack, modifier = Modifier.weight(1f)) { Text("+ 符号净化包") }
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
            RuleItemCard(
                rule = rule,
                onEdit = { editingRule = rule },
                onDelete = { viewModel.deleteRule(rule.id) },
                onToggle = { viewModel.toggleRule(rule, it) }
            )
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

@Composable
private fun RuleItemCard(
    rule: ReplacementRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    InkPaperCard(padding = 12.dp, onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.description.ifBlank { "自定义词条" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    InkStatusPill(rule.category.label, PillTone.NEUTRAL)
                    if (rule.isRegex) InkStatusPill("正则", PillTone.ACCENT)
                }
                Text(
                    text = "${rule.pattern} → ${rule.replacement.ifEmpty { "∅ (删除)" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
        }
    }
}
