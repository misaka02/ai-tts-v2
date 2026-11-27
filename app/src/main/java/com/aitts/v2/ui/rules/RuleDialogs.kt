package com.aitts.v2.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.RuleCategory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditDialog(
    initial: ReplacementRule,
    onSave: (ReplacementRule) -> Unit,
    onDismiss: () -> Unit
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var replacement by remember { mutableStateOf(initial.replacement) }
    var description by remember { mutableStateOf(initial.description) }
    var isRegex by remember { mutableStateOf(initial.isRegex) }
    var isCaseSensitive by remember { mutableStateOf(initial.isCaseSensitive) }
    var category by remember { mutableStateOf(initial.category) }

    val regexError = remember(pattern, isRegex) {
        if (!isRegex || pattern.isBlank()) {
            null
        } else {
            runCatching { Regex(pattern) }.exceptionOrNull()?.message
        }
    }
    val canSave = pattern.isNotBlank() && regexError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "新增词典规则" else "编辑词典规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("词条说明") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("匹配表达式 / 原文") },
                    isError = regexError != null,
                    supportingText = regexError?.let { err ->
                        { Text("正则表达式语法错误: $err", color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换为 (留空即删除)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用正则表达式")
                    Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("区分大小写")
                    Switch(checked = isCaseSensitive, onCheckedChange = { isCaseSensitive = it })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleCategory.entries.forEach { cat ->
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            pattern = pattern,
                            replacement = replacement,
                            description = description.trim(),
                            isRegex = isRegex,
                            isCaseSensitive = isCaseSensitive,
                            category = category
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
