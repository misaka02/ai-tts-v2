package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
fun DriveConnectSection(
    state: GoogleDriveUiState,
    onSelectTab: (Int) -> Unit,
    onUpdateInputs: (clientId: String?, clientSecret: String?, refreshToken: String?, accessToken: String?) -> Unit,
    onStartBrowserOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onConnectDirect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TabRow(selectedTabIndex = state.authTab) {
                Tab(selected = state.authTab == 0, onClick = { onSelectTab(0) }, text = { Text("浏览器 OAuth 登录") })
                Tab(selected = state.authTab == 1, onClick = { onSelectTab(1) }, text = { Text("令牌直连") })
            }
        }

        item {
            InkPaperCard(padding = 14.dp) {
                OutlinedTextField(
                    value = state.clientIdInput,
                    onValueChange = { onUpdateInputs(it, null, null, null) },
                    label = { Text("OAuth Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.clientSecretInput,
                    onValueChange = { onUpdateInputs(null, it, null, null) },
                    label = { Text("Client Secret (桌面应用可选/建议填写)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.authTab == 0) {
                    Text(
                        text = "配置说明：在 Google Cloud Console 创建“桌面应用 (Desktop app)” OAuth 客户端并启用 Google Drive API，" +
                            "填写 Client ID 后点击下方授权，软件将在本地回环地址接收登录回调（默认仅申请只读权限 drive.readonly）。" +
                            "若当前网络无法直连 Google，请先在「偏好」设置中开启代理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.isAuthorizing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在等待浏览器完成 Google 授权...", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onCancelOAuth) { Text("取消") }
                        }
                    } else {
                        Button(onClick = onStartBrowserOAuth, modifier = Modifier.fillMaxWidth()) {
                            Text("打开浏览器授权 Google 网盘")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = state.refreshTokenInput,
                        onValueChange = { onUpdateInputs(null, null, it, null) },
                        label = { Text("Refresh Token (可长期自动续期)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.accessTokenInput,
                        onValueChange = { onUpdateInputs(null, null, null, it) },
                        label = { Text("或直接填写 Access Token (临时验证)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onConnectDirect,
                        enabled = !state.isAuthorizing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.isAuthorizing) "正在校验..." else "验证并连接网盘")
                    }
                }
            }
        }
    }
}
