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
import androidx.compose.material3.Slider
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
import com.aitts.v2.model.ProviderDescriptor
import com.aitts.v2.model.ProviderParamSpec
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
internal fun CredentialsEndpointSection(
    type: ProviderType,
    descriptor: ProviderDescriptor,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    secondaryKey: String,
    onSecondaryKeyChange: (String) -> Unit
) {
    var showPrimary by remember { mutableStateOf(false) }
    var showSecondary by remember { mutableStateOf(false) }

    InkPaperCard(padding = 14.dp) {
        if (!descriptor.supportsBaseUrl) {
            Text(
                "本机离线引擎在本地执行推理，无需网络接口与 API Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(if (type == ProviderType.EDGE_TTS) "接口地址 / 反代域 (留空用微软官方)" else "接口地址 (Base URL)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (type.requiresApiKey || type == ProviderType.CUSTOM_HTTP) {
            SecretInputField(apiKey, onApiKeyChange, "API Key / AccessToken", showPrimary) { showPrimary = !showPrimary }
            SecretInputField(
                secondaryKey,
                onSecondaryKeyChange,
                "第二 API Key (并发轮询，可选)",
                showSecondary
            ) { showSecondary = !showSecondary }
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
    fromRemote: Boolean,
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
            InkStatusPill(
                label = if (fromRemote) "上游实时同步 · ${availableVoices.size} 音色" else "预置清单 · ${availableVoices.size} 音色",
                tone = if (fromRemote) PillTone.SUCCESS else PillTone.NEUTRAL
            )
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isLoading) "同步中..." else "刷新上游")
            }
        }

        if (availableModels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                availableModels.take(12).forEach { m ->
                    val suffix = if (type == ProviderType.OFFLINE_SHERPA && m in downloadedModelIds) " ✓" else ""
                    FilterChip(selected = modelName == m, onClick = { onModelNameChange(m) }, label = { Text("${m.take(22)}$suffix") })
                }
            }
        }

        if (availableVoices.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                availableVoices.take(16).forEach { v ->
                    FilterChip(selected = voiceId == v.id, onClick = { onVoiceIdChange(v.id) }, label = { Text(v.name.take(18)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VendorParamsSection(
    specs: List<ProviderParamSpec>,
    extraParams: Map<String, String>,
    dynamicVoiceStyles: List<String>,
    onParamChange: (String, String) -> Unit
) {
    specs.forEach { spec ->
        val current = extraParams[spec.key] ?: spec.defaultValue
        when (spec) {
            is ProviderParamSpec.ParamText -> {
                OutlinedTextField(
                    value = current,
                    onValueChange = { onParamChange(spec.key, it) },
                    label = { Text(spec.label) },
                    supportingText = if (spec.hint.isNotBlank()) ({ Text(spec.hint) }) else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is ProviderParamSpec.ParamSelect -> {
                val opts = if (spec.key == "style" && dynamicVoiceStyles.isNotEmpty()) listOf("") + dynamicVoiceStyles else spec.options
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.label, style = MaterialTheme.typography.labelMedium)
                    if (opts.isEmpty()) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = { onParamChange(spec.key, it) },
                            placeholder = { Text(spec.hint) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            opts.forEach { opt ->
                                FilterChip(
                                    selected = current == opt,
                                    onClick = { onParamChange(spec.key, opt) },
                                    label = { Text(opt.ifBlank { "默认" }) }
                                )
                            }
                        }
                    }
                }
            }
            is ProviderParamSpec.ParamSlider -> {
                val num = current.toFloatOrNull() ?: spec.defaultValue.toFloatOrNull() ?: spec.min
                val labelText = if (spec.key == "numThreads") "${spec.label}: ${num.toInt()}" else "${spec.label}: ${"%.2f".format(num)}"
                Text(labelText, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = num.coerceIn(spec.min, spec.max),
                    onValueChange = { v ->
                        val str = if (spec.key == "numThreads") v.toInt().toString() else "%.2f".format(v)
                        onParamChange(spec.key, str)
                    },
                    valueRange = spec.min..spec.max
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomHttpSection(
    customHeaders: String,
    onHeadersChange: (String) -> Unit,
    customPayload: String,
    onPayloadChange: (String) -> Unit,
    responsePath: String,
    onResponsePathChange: (String) -> Unit,
    onApplyPreset: (url: String, headers: String, payload: String, respPath: String) -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        Text("自建模板一键填充", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = {
                    onApplyPreset(
                        "http://127.0.0.1:9880/tts?text=\${text}&text_lang=zh&speed_factor=\${speed}",
                        "{}",
                        "",
                        ""
                    )
                },
                label = { Text("GPT-SoVITS GET") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    onApplyPreset(
                        "http://127.0.0.1:50000/inference_zero_shot",
                        "{}",
                        TtsProviderConfig.DEFAULT_CUSTOM_PAYLOAD,
                        ""
                    )
                },
                label = { Text("CosyVoice POST") }
            )
        }
        OutlinedTextField(
            value = customHeaders,
            onValueChange = onHeadersChange,
            label = { Text("自定义 HTTP Headers (JSON)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = customPayload,
            onValueChange = onPayloadChange,
            label = { Text("JSON Body 模板 (留空则发 GET 请求)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = responsePath,
            onValueChange = onResponsePathChange,
            label = { Text("JSON Base64 提取路径 (留空表示二进制音频流)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
