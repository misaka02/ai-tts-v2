package com.aitts.v2.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.SpeechHistoryItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    isActive: Boolean,
    remainingSeconds: Int,
    onSelectMinutes: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isActive) {
                    Text("当前剩余 ${remainingSeconds / 60} 分 ${remainingSeconds % 60} 秒")
                } else {
                    Text("到达设定时长前 15 秒将平滑淡出音量并停止朗读。")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 30, 45, 60, 90).forEach { min ->
                        FilterChip(selected = false, onClick = { onSelectMinutes(min) }, label = { Text("${min} 分钟") })
                    }
                }
            }
        },
        confirmButton = {
            if (isActive) {
                TextButton(onClick = onStop) { Text("停止定时") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<SpeechHistoryItem>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最近朗读记录 (${history.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
            if (history.isEmpty()) {
                Text("暂无朗读记录", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) { item ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(
                                    "${item.providerName} · ${item.voiceId} · ${item.characterCount}字 · ${item.costMs}ms",
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
