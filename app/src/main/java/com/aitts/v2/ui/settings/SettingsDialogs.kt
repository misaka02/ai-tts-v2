package com.aitts.v2.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.GlobalSettings

@Composable
fun ProxyConfigDialog(
    settings: GlobalSettings,
    onSave: (GlobalSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var enabled by remember { mutableStateOf(settings.proxyEnabled) }
    var type by remember { mutableStateOf(settings.proxyType) }
    var host by remember { mutableStateOf(settings.proxyHost) }
    var port by remember { mutableStateOf(settings.proxyPort.toString()) }
    var connectTimeout by remember { mutableStateOf(settings.connectTimeoutSeconds.toString()) }
    var readTimeout by remember { mutableStateOf(settings.readTimeoutSeconds.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络代理与超时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用全局代理")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "HTTP", onClick = { type = "HTTP" }, label = { Text("HTTP") })
                    FilterChip(selected = type == "SOCKS", onClick = { type = "SOCKS" }, label = { Text("SOCKS5") })
                }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("代理主机") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = connectTimeout,
                        onValueChange = { connectTimeout = it },
                        label = { Text("连接超时(s)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = readTimeout,
                        onValueChange = { readTimeout = it },
                        label = { Text("读取超时(s)") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = (port.toIntOrNull() ?: 7890).coerceIn(1, 65535)
                val connSec = (connectTimeout.toIntOrNull() ?: 15).coerceIn(3, 120)
                val readSec = (readTimeout.toIntOrNull() ?: 60).coerceIn(5, 300)
                onSave(
                    settings.copy(
                        proxyEnabled = enabled,
                        proxyType = type,
                        proxyHost = host.trim(),
                        proxyPort = parsedPort,
                        connectTimeoutSeconds = connSec,
                        readTimeoutSeconds = readSec
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun BackupDialog(
    onExport: (Boolean) -> String,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var includeSecrets by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val payload = onExport(includeSecrets)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入目标文件")
        }.onSuccess {
            Toast.makeText(context, "备份文件已导出", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            Toast.makeText(context, "导出失败: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取备份文件")
        }.onSuccess { text ->
            onImport(text)
        }.onFailure { err ->
            Toast.makeText(context, "读取失败: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置备份与恢复") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    Text("导出时包含 API Key (默认已脱敏)")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("aitts-v2-backup.json") },
                        modifier = Modifier.weight(1f)
                    ) { Text("导出 JSON 文件") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("从文件导入") }
                }
                OutlinedButton(
                    onClick = {
                        val json = onExport(includeSecrets)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("aitts_backup", json))
                        Toast.makeText(context, "全量备份 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制备份 JSON 到剪贴板") }
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("或粘贴备份 JSON 进行恢复") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (importText.isNotBlank()) onImport(importText) }) { Text("从文本恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun ConfirmResetDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复默认设置？") },
        text = { Text("将重置全局参数、引擎列表与词典规则为默认值，已加密保存的 Keystore 密钥保留。") },
        confirmButton = { Button(onClick = onConfirm) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
