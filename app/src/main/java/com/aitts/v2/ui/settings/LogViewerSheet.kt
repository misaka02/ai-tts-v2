package com.aitts.v2.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitts.v2.model.LogEntry
import com.aitts.v2.model.LogLevel
import com.aitts.v2.model.RequestLogSession
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerSheet(
    sessions: List<RequestLogSession>,
    rawLogs: List<LogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("请求诊断日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (mode == 0) "共 ${sessions.size} 个会话" else "共 ${rawLogs.size} 条流水",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("分组") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("流水") })
                    IconButton(onClick = {
                        copyText(context, "aitts_logs", rawLogs.joinToString("\n") { it.formatLine() })
                        Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全部", modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = "清空", modifier = Modifier.size(18.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (mode == 0) {
                SessionLogList(
                    sessions = sessions,
                    expanded = expanded,
                    onToggle = { sid -> expanded = if (sid in expanded) expanded - sid else expanded + sid },
                    onCopySession = { sess ->
                        copyText(context, "sess_${sess.sessionId}", sess.entries.joinToString("\n") { it.formatLine() })
                        Toast.makeText(context, "已复制会话 #${sess.sessionId}", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rawLogs.reversed()) { entry ->
                        val errColor = MaterialTheme.colorScheme.error
                        val okColor = MaterialTheme.colorScheme.onSurface
                        Text(
                            text = entry.formatLine(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.level == LogLevel.ERROR) errColor else okColor
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空诊断日志？") },
            text = { Text("清空后无法恢复。") },
            confirmButton = { Button(onClick = { onClear(); confirmClear = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SessionLogList(
    sessions: List<RequestLogSession>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onCopySession: (RequestLogSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text("暂无会话日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions, key = { "${it.sessionId}_${it.startTime}" }) { sess ->
            val isExp = sess.sessionId in expanded
            InkPaperCard(padding = 10.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(sess.sessionId) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sess.summaryTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${sess.startTime} · ${sess.subtitle ?: sess.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onCopySession(sess) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制会话", modifier = Modifier.size(16.dp))
                        }
                        Icon(if (isExp) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                }
                AnimatedVisibility(visible = isExp) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        sess.entries.forEach { e ->
                            val errColor = MaterialTheme.colorScheme.error
                            val okColor = MaterialTheme.colorScheme.onSurface
                            Text(
                                text = e.formatLine(),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (e.level == LogLevel.ERROR) errColor else okColor
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun copyText(context: Context, label: String, content: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, content))
}
