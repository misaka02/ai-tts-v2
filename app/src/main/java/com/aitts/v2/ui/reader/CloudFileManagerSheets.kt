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
    errorMessage: String?,
    isBusy: Boolean,
    onOneTapSignIn: () -> Unit,
    onOpenSafPicker: () -> Unit,
    onOpenDevTokenSheet: () -> Unit
) {
    InkPaperCard(padding = 16.dp) {
        Text("连接 Google 云端硬盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "无需手动填写 Client ID。支持系统 Google 账号一键授权，或直接调起系统文件选择器无缝浏览已登录的 Google Drive App。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onOneTapSignIn, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("一键授权登录 Google 账号")
        }
        OutlinedButton(onClick = onOpenSafPicker, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("系统网盘直选批量导入 (零配置 SAF)")
        }
        TextButton(onClick = onOpenDevTokenSheet, modifier = Modifier.fillMaxWidth()) {
            Text("高级：使用手动 Access Token 连接")
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
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
        },
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
                Text("仅用于无 Google Play 服务环境的临时调试验证。", style = MaterialTheme.typography.bodySmall)
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
