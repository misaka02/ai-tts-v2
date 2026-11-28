package com.aitts.v2.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.engine.cloud.CloudFileNode
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
fun CloudConnectedBar(label: String, onSync: () -> Unit, onDisconnect: () -> Unit) {
    InkPaperCard(padding = 12.dp) {
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSync, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("双向同步本地专有目录")
            }
            OutlinedButton(onClick = onDisconnect) { Text("断开") }
        }
    }
}

@Composable
fun FileNodeRow(
    node: CloudFileNode,
    selected: Boolean,
    formattedSize: String,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    InkPaperCard(padding = 10.dp) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
            if (!node.isFolder) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(formattedSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRename) { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = "重命名") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除") }
        }
    }
}

@Composable
fun GoogleDriveAuthCard(
    clientId: String,
    clientSecret: String,
    errorMessage: String?,
    isBusy: Boolean,
    isBrowserWaiting: Boolean,
    pendingAuthUrl: String,
    pastedCallbackUrl: String,
    packageName: String,
    signingSha1: String,
    onUpdateClient: (String?, String?) -> Unit,
    onUpdatePastedUrl: (String) -> Unit,
    onImportJson: () -> Unit,
    onOpenCloudConsole: () -> Unit,
    onBrowserSignIn: () -> Unit,
    onCopyAuthUrl: () -> Unit,
    onSubmitManualCallback: () -> Unit,
    onCancelBrowserSignIn: () -> Unit,
    onOneTapSignIn: () -> Unit,
    onCopySigningInfo: () -> Unit,
    onOpenSafPicker: () -> Unit,
    onOpenDevTokenSheet: () -> Unit
) {
    var showManualClient by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showSignInfo by remember { mutableStateOf(false) }

    InkPaperCard(padding = 16.dp) {
        Text("连接 Google 云端硬盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onOpenSafPicker, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("系统网盘直选导入电子书 (SAF · 免配置立即可用)")
        }
        Text(
            text = "若需完整网盘目录管理与进度双向同步，支持导入个人 client_secret.json / rclone.conf / token.json 或开启同页 WebDAV (Alist)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onImportJson, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                Text("导入 JSON / rclone")
            }
            TextButton(onClick = { showManualClient = !showManualClient }) { Text(if (showManualClient) "收起" else "手填ID") }
            TextButton(onClick = { showGuide = !showGuide }) { Text(if (showGuide) "收起向导" else "配置向导") }
        }
        if (showGuide) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "1. 前往 Google Cloud Console 启用 Google Drive API；\n" +
                        "2. 「凭据」新建 OAuth 客户端 ID，应用类型选「桌面应用 (Desktop app)」；\n" +
                        "3. 下载 JSON 后点击上方「导入 JSON / rclone」，再用浏览器授权一次即可自动续期。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenCloudConsole) { Text("打开 Google Cloud 凭据控制台 ↗") }
            }
        }
        if (showManualClient || clientId.isNotBlank()) {
            Text(
                text = if (clientId.isNotBlank()) "已持久化 Client ID: ${clientId.take(24)}..." else "尚未配置桌面端 OAuth Client ID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (showManualClient) {
            OutlinedTextField(
                value = clientId, onValueChange = { onUpdateClient(it, null) },
                label = { Text("OAuth Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = clientSecret, onValueChange = { onUpdateClient(null, it) },
                label = { Text("Client Secret (可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        if (isBrowserWaiting) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (pendingAuthUrl.isNotBlank()) {
                        OutlinedButton(onClick = onCopyAuthUrl, modifier = Modifier.weight(1f)) { Text("复制链接") }
                    }
                    OutlinedButton(onClick = onCancelBrowserSignIn, modifier = Modifier.weight(1f)) { Text("取消等待") }
                }
                OutlinedTextField(
                    value = pastedCallbackUrl, onValueChange = onUpdatePastedUrl,
                    label = { Text("若未自动跳回，粘贴浏览器回调 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = onSubmitManualCallback, modifier = Modifier.fillMaxWidth()) { Text("提交回调 URL 完成登录") }
            }
        } else {
            Button(onClick = onBrowserSignIn, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("浏览器 OAuth 永久连接 (Custom Tabs)")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onOneTapSignIn, enabled = !isBusy) { Text("Play 服务授权") }
            TextButton(onClick = { showSignInfo = !showSignInfo }) { Text(if (showSignInfo) "隐藏SHA-1" else "SHA-1诊断") }
            TextButton(onClick = onOpenDevTokenSheet) { Text("手动Token") }
        }
        if (showSignInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("包名: $packageName\nSHA-1: $signingSha1", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onCopySigningInfo) { Text("一键复制包名与 SHA-1") }
            }
        }
    }
}

@Composable
fun WebDavConnectCard(
    url: String,
    user: String,
    pass: String,
    rootPath: String,
    isBusy: Boolean,
    onUpdate: (String?, String?, String?, String?) -> Unit,
    onConnect: () -> Unit
) {
    InkPaperCard(padding = 16.dp) {
        Text("连接 WebDAV 网盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "原生兼容 Alist、坚果云、Nextcloud、群晖 NAS 等标准 WebDAV 服务，连接后可像本地目录一样浏览并双向同步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = url,
            onValueChange = { onUpdate(it, null, null, null) },
            label = { Text("服务器地址 (如 https://dav.example.com/dav)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = user,
                onValueChange = { onUpdate(null, it, null, null) },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { onUpdate(null, null, it, null) },
                label = { Text("应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = rootPath,
            onValueChange = { onUpdate(null, null, null, it) },
            label = { Text("默认根目录") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onConnect, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("校验并连接 WebDAV")
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun DeleteFileConfirmDialog(
    node: CloudFileNode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除确认") },
        text = { Text("确定要删除「${node.name}」吗？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun DevTokenConnectDialog(
    token: String,
    onUpdateToken: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动 Token 接入 (高级)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅用于已有短期 Access Token 的临时验证。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = token,
                    onValueChange = onUpdateToken,
                    label = { Text("Google Drive Access Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConnect) { Text("连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
