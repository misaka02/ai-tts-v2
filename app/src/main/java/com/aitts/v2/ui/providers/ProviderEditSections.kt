package com.aitts.v2.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.VoiceModel
import com.aitts.v2.ui.theme.InkPaperCard

@Composable
internal fun CredentialsEndpointSection(
    type: ProviderType,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    secondaryKey: String,
    onSecondaryKeyChange: (String) -> Unit,
    doubaoAppId: String,
    onDoubaoAppIdChange: (String) -> Unit,
    doubaoCluster: String,
    onDoubaoClusterChange: (String) -> Unit
) {
    var showPrimaryKey by remember { mutableStateOf(false) }
    var showSecondaryKey by remember { mutableStateOf(false) }

    InkPaperCard(padding = 14.dp) {
        if (type == ProviderType.EDGE_TTS) {
            Text(
                "Edge TTS 使用微软公开连接点，无需接口地址或 API Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (type == ProviderType.OFFLINE_SHERPA) {
            Text(
                "Sherpa-ONNX 在设备本地执行推理，不发起云端网络请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("接口地址 (Base URL)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (type.requiresApiKey || type == ProviderType.CUSTOM_HTTP) {
            SecretInputField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = "API Key / AccessToken",
                visible = showPrimaryKey,
                onToggleVisible = { showPrimaryKey = !showPrimaryKey }
            )
            SecretInputField(
                value = secondaryKey,
                onValueChange = onSecondaryKeyChange,
                label = "第二 API Key (并发轮询，可选)",
                visible = showSecondaryKey,
                onToggleVisible = { showSecondaryKey = !showSecondaryKey }
            )
        }

        if (type == ProviderType.DOUBAO) {
            OutlinedTextField(
                value = doubaoAppId,
                onValueChange = onDoubaoAppIdChange,
                label = { Text("火山引擎 AppID (必填)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = doubaoCluster,
                onValueChange = onDoubaoClusterChange,
                label = { Text("Cluster (默认 volcano_tts)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SecretInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelVoiceDiscoverySection(
    type: ProviderType,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    voiceId: String,
    onVoiceIdChange: (String) -> Unit,
    availableModels: List<String>,
    availableVoices: List<VoiceModel>,
    downloadedModelIds: Set<String>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenOfflineCatalog: () -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChange,
                label = { Text("模型 ID") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = voiceId,
                onValueChange = onVoiceIdChange,
                label = { Text("主音色 ID") },
                modifier = Modifier.weight(1f)
            )
        }

        if (type == ProviderType.OFFLINE_SHERPA) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已安装 ${downloadedModelIds.size} 个本地离线模型", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenOfflineCatalog) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("管理离线库")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "可用模型 (${availableModels.size}) / 音色 (${availableVoices.size})",
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isLoading) "拉取中" else "刷新列表")
            }
        }

        if (availableModels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                availableModels.take(12).forEach { m ->
                    val installedSuffix = if (type == ProviderType.OFFLINE_SHERPA && m in downloadedModelIds) " ✓" else ""
                    FilterChip(
                        selected = modelName == m,
                        onClick = { onModelNameChange(m) },
                        label = { Text("${m.take(22)}$installedSuffix") }
                    )
                }
            }
        }

        if (availableVoices.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                availableVoices.take(14).forEach { v ->
                    FilterChip(
                        selected = voiceId == v.id,
                        onClick = { onVoiceIdChange(v.id) },
                        label = { Text(v.name.take(18)) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DualRoleSection(
    dualRole: Boolean,
    onDualRoleChange: (Boolean) -> Unit,
    dialogueVoiceId: String,
    onDialogueChange: (String) -> Unit,
    maleVoiceId: String,
    onMaleChange: (String) -> Unit,
    femaleVoiceId: String,
    onFemaleChange: (String) -> Unit,
    elderVoiceId: String,
    onElderChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用多角色对白音色分流", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = dualRole, onCheckedChange = onDualRoleChange)
        }
        if (dualRole) {
            OutlinedTextField(
                value = dialogueVoiceId,
                onValueChange = onDialogueChange,
                label = { Text("通用对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maleVoiceId,
                onValueChange = onMaleChange,
                label = { Text("男声对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = femaleVoiceId,
                onValueChange = onFemaleChange,
                label = { Text("女声对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = elderVoiceId,
                onValueChange = onElderChange,
                label = { Text("长者/反派音色") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun CustomHttpSection(
    customPayload: String,
    onPayloadChange: (String) -> Unit,
    responsePath: String,
    onResponsePathChange: (String) -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        OutlinedTextField(
            value = customPayload,
            onValueChange = onPayloadChange,
            label = { Text("JSON Body 模板") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = responsePath,
            onValueChange = onResponsePathChange,
            label = { Text("JSON Base64 提取路径 (留空表示二进制流)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
