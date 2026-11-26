package com.aitts.v2.ui.hub

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.aitts.v2.ui.settings.LogViewerSheet

@Composable
fun SpeechHubScreen(
    viewModel: SpeechHubViewModel,
    onOpenProviders: () -> Unit,
    onEditActiveProvider: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProviderFlow.collectAsStateWithLifecycle()
    val rms by viewModel.rmsFlow.collectAsStateWithLifecycle()
    val history by viewModel.historyFlow.collectAsStateWithLifecycle()
    val sleepActive by viewModel.sleepActiveFlow.collectAsStateWithLifecycle()
    val sleepSec by viewModel.sleepRemainingFlow.collectAsStateWithLifecycle()
    val logSessions by viewModel.logSessionsFlow.collectAsStateWithLifecycle()
    val rawLogs by viewModel.rawLogsFlow.collectAsStateWithLifecycle()

    var showLogs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AI-TTS 朗读工作台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.lastLatencyMs?.let { "最近首响: ${it}ms" } ?: "系统 TTS 服务就绪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = { showSleepDialog = true }) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "睡眠定时",
                            tint = if (sleepActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "朗读历史")
                    }
                    IconButton(onClick = { showLogs = true }) {
                        Icon(Icons.Default.Terminal, contentDescription = "诊断日志")
                    }
                }
            }
        }

        if (sleepActive) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "睡眠定时剩余 ${sleepSec / 60} 分 ${sleepSec % 60} 秒 (最后 15 秒平滑淡出)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::stopSleepTimer) { Text("取消") }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(activeProvider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${activeProvider.type.displayName} · 音色: ${activeProvider.voiceId.ifBlank { "默认" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onOpenProviders) { Text("切换") }
                        TextButton(onClick = { onEditActiveProvider(activeProvider.id) }) { Text("配置") }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "语速 ${"%.2f".format(activeProvider.speed)}x",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(84.dp)
                        )
                        Slider(
                            value = activeProvider.speed,
                            onValueChange = viewModel::updateActiveSpeed,
                            valueRange = 0.5f..2.5f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("原生流式传输", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = activeProvider.isStreamingEnabled, onCheckedChange = viewModel::toggleStreaming)
                        }
                        OutlinedButton(onClick = viewModel::testActiveLatency, enabled = !state.isTestingPing) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.isTestingPing) "测速中" else state.lastPingMs?.takeIf { it >= 0 }?.let { "${it}ms" } ?: "测延迟")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(state.quoteSource, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = false,
                                onClick = viewModel::loadLocalQuote,
                                label = { Text("本地语料") },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                            FilterChip(
                                selected = false,
                                onClick = viewModel::loadOnlineQuote,
                                enabled = !state.isFetchingQuote,
                                label = { Text(if (state.isFetchingQuote) "加载中" else "一言") },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = viewModel::updateInputText,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("试听文本") }
                    )

                    LinearProgressIndicator(
                        progress = { if (state.isPlaying) rms.coerceIn(0.05f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = viewModel::togglePlayPreview,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape
                        ) {
                            if (state.isSynthesizing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("合成中...")
                            } else if (state.isPlaying) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("停止试听")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("立即试听")
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.exportLastAudio(context) },
                            enabled = state.canExport,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "导出音频")
                            Spacer(Modifier.width(4.dp))
                            Text("导出")
                        }
                    }
                }
            }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            isActive = sleepActive,
            remainingSeconds = sleepSec,
            onSelectMinutes = { viewModel.startSleepTimer(it); showSleepDialog = false },
            onStop = { viewModel.stopSleepTimer(); showSleepDialog = false },
            onDismiss = { showSleepDialog = false }
        )
    }

    if (showHistory) {
        HistorySheet(
            history = history,
            onClear = viewModel::clearHistory,
            onDismiss = { showHistory = false }
        )
    }

    if (showLogs) {
        LogViewerSheet(
            sessions = logSessions,
            rawLogs = rawLogs,
            onClear = viewModel::clearLogs,
            onDismiss = { showLogs = false }
        )
    }
}
