package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
fun GoogleDriveAuthCard(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    errorMessage: String?,
    isBusy: Boolean,
    isBrowserWaiting: Boolean,
    pendingAuthUrl: String,
    pastedCallbackUrl: String,
    packageName: String,
    signingSha1: String,
    onUpdateClient: (String?, String?, String?) -> Unit,
    onUpdatePastedUrl: (String) -> Unit,
    onImportJson: () -> Unit,
    onOpenPasteDialog: () -> Unit,
    onOpenCloudConsole: () -> Unit,
    onBrowserSignIn: (useExternalBrowser: Boolean) -> Unit,
    onReopenExternalBrowser: () -> Unit,
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
    var showAdvanced by remember { mutableStateOf(false) }

    InkPaperCard(padding = 16.dp) {
        Text("连接 Google 云端硬盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onOneTapSignIn, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("Google Play 一键直接登录")
        }
        OutlinedButton(onClick = onOpenSafPicker, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("系统网盘直选导入电子书 (SAF · 免配置立即可用)")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "包名: $packageName\nSHA-1: ${signingSha1.take(23)}...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCopySigningInfo) { Text("复制包名与 SHA-1") }
        }
        Text(
            text = "Play 登录需在 Google Cloud Console 绑定上述包名与 SHA-1；否则请先用「系统网盘直选」、同页「WebDAV」或下方个人「OAuth 2.0」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onImportJson, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text("导入 JSON / rclone") }
            OutlinedButton(onClick = onOpenPasteDialog, enabled = !isBusy) { Text("粘贴文本") }
            TextButton(onClick = { showGuide = !showGuide }) { Text(if (showGuide) "收起向导" else "4步向导") }
        }
        if (showGuide) DriveOAuthGuideSection(onOpenCloudConsole)

        DriveManualClientSection(
            clientId = clientId, clientSecret = clientSecret, redirectUri = redirectUri,
            expanded = showManualClient, onToggle = { showManualClient = !showManualClient }, onUpdate = onUpdateClient
        )

        DriveBrowserAuthControls(
            isBusy = isBusy, isBrowserWaiting = isBrowserWaiting, pendingAuthUrl = pendingAuthUrl,
            pastedCallbackUrl = pastedCallbackUrl, onUpdatePastedUrl = onUpdatePastedUrl,
            onBrowserSignIn = onBrowserSignIn, onReopenExternalBrowser = onReopenExternalBrowser,
            onCopyAuthUrl = onCopyAuthUrl, onSubmitManualCallback = onSubmitManualCallback,
            onCancelBrowserSignIn = onCancelBrowserSignIn
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "收起 高级 Token 接入 ▲" else "高级：直接填入 Refresh / Access Token ▼")
        }
        if (showAdvanced) {
            DriveAdvancedSection(
                packageName = packageName, signingSha1 = signingSha1, isBusy = isBusy,
                onOpenDevTokenSheet = onOpenDevTokenSheet
            )
        }
    }
}

@Composable
private fun DriveOAuthGuideSection(onOpenCloudConsole: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "1. 打开 Google Cloud Console 并启用「Google Drive API」；\n" +
                "2. 进入「OAuth 同意屏幕」→ 在「测试用户 (Test users)」添加您自己的 Gmail（否则登录必报 403 access_denied）；\n" +
                "3. 在「凭据」新建 OAuth 客户端 ID → 选「桌面应用 (Desktop app)」并下载 JSON（或选 Web 应用并添回调 http://127.0.0.1:53682/callback）；\n" +
                "4. 导入 JSON 后用 Custom Tabs 或外部浏览器授权一次即可自动续期。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onOpenCloudConsole) { Text("打开 Google Cloud 凭据控制台 ↗") }
    }
}

@Composable
private fun DriveManualClientSection(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUpdate: (String?, String?, String?) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val status = if (clientId.isNotBlank()) "已载入 Client ID: ${clientId.take(20)}..." else "未配置桌面端 OAuth 凭据"
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onToggle) { Text(if (expanded) "收起凭据" else "手填凭据") }
    }
    if (expanded) {
        OutlinedTextField(
            value = clientId, onValueChange = { onUpdate(it, null, null) },
            label = { Text("OAuth Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = clientSecret, onValueChange = { onUpdate(null, it, null) },
            label = { Text("Client Secret (必填，Google 换令牌必带)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = redirectUri, onValueChange = { onUpdate(null, null, it) },
            label = { Text("固定回调 URI (仅 Web 应用需填，桌面应用留空)") },
            placeholder = { Text("http://127.0.0.1:53682/callback") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DriveBrowserAuthControls(
    isBusy: Boolean,
    isBrowserWaiting: Boolean,
    pendingAuthUrl: String,
    pastedCallbackUrl: String,
    onUpdatePastedUrl: (String) -> Unit,
    onBrowserSignIn: (Boolean) -> Unit,
    onReopenExternalBrowser: () -> Unit,
    onCopyAuthUrl: () -> Unit,
    onSubmitManualCallback: () -> Unit,
    onCancelBrowserSignIn: () -> Unit
) {
    if (isBrowserWaiting) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (pendingAuthUrl.isNotBlank()) {
                    OutlinedButton(onClick = onCopyAuthUrl, modifier = Modifier.weight(1f)) { Text("复制链接") }
                    OutlinedButton(onClick = onReopenExternalBrowser, modifier = Modifier.weight(1f)) { Text("外部浏览器") }
                }
                OutlinedButton(onClick = onCancelBrowserSignIn) { Text("取消") }
            }
            OutlinedTextField(
                value = pastedCallbackUrl, onValueChange = onUpdatePastedUrl,
                label = { Text("若未自动跳回，粘贴浏览器回调 URL 或授权码 code") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSubmitManualCallback, modifier = Modifier.fillMaxWidth()) { Text("提交回调或授权码完成登录") }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onBrowserSignIn(false) }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("浏览器 OAuth 永久连接 (Custom Tabs)")
            }
            OutlinedButton(onClick = { onBrowserSignIn(true) }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("改用系统外部浏览器授权 (Chrome / Edge)")
            }
        }
    }
}

@Composable
private fun DriveAdvancedSection(
    packageName: String,
    signingSha1: String,
    isBusy: Boolean,
    onOpenDevTokenSheet: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onOpenDevTokenSheet, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("手动填入 Refresh / Access Token")
        }
        Text(
            text = "完整 Play 绑定信息：$packageName / $signingSha1",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DevTokenConnectDialog(
    clientId: String,
    clientSecret: String,
    refreshToken: String,
    accessToken: String,
    onUpdate: (cid: String?, sec: String?, ref: String?, acc: String?) -> Unit,
    onOpenPlayground: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refresh / Access Token 接入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "填写 Client ID + Secret + Refresh Token 可永久自动续期（推荐从 rclone 或 OAuth Playground 获取）；仅填 Access Token 有效期约 1 小时。",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onOpenPlayground) { Text("打开 Google OAuth Playground ↗") }
                OutlinedTextField(
                    value = clientId, onValueChange = { onUpdate(it, null, null, null) },
                    label = { Text("OAuth Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { onUpdate(null, it, null, null) },
                    label = { Text("OAuth Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = refreshToken, onValueChange = { onUpdate(null, null, it, null) },
                    label = { Text("Refresh Token (推荐·永久续期)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accessToken, onValueChange = { onUpdate(null, null, null, it) },
                    label = { Text("Access Token (可选·1小时有效)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConnect) { Text("验证并连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun PasteCredentialsDialog(
    onImportText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 OAuth / rclone 凭证文本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "支持直接粘贴 client_secret.json、token.json 或 rclone.conf 内容，系统将自动识别并在含 Refresh Token 时直接连接。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = rawText, onValueChange = { rawText = it },
                    label = { Text("凭证 JSON 或 rclone.conf 文本") },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (rawText.isNotBlank()) onImportText(rawText) }) { Text("解析并导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
