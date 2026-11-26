package com.aitts.v2.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络代理设置") },
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
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = port.toIntOrNull() ?: 7890
                onSave(settings.copy(proxyEnabled = enabled, proxyType = type, proxyHost = host.trim(), proxyPort = parsedPort))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置备份与恢复") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    Text("导出时包含 API Key (默认已脱敏)")
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
            Button(onClick = { if (importText.isNotBlank()) onImport(importText) }) { Text("恢复配置") }
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
        text = { Text("将重置全局参数、引擎列表与替换规则为默认值，已加密保存的密钥保留。") },
        confirmButton = { Button(onClick = onConfirm) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
