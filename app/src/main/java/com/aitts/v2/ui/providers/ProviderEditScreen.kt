package com.aitts.v2.ui.providers

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(
    providerId: String,
    viewModel: ProvidersViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val existing = remember(providerId) { viewModel.getProvider(providerId) }
    val existingCreds = remember(providerId) { viewModel.getCredentials(providerId) }
    val initial = existing ?: TtsProviderConfig(id = providerId, type = ProviderType.OPENAI_COMPATIBLE, name = "新语音节点")

    var type by remember { mutableStateOf(initial.type) }
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(existingCreds.apiKey) }
    var secondaryKey by remember { mutableStateOf(existingCreds.secondaryApiKey) }
    var modelName by remember { mutableStateOf(initial.modelName) }
    var voiceId by remember { mutableStateOf(initial.voiceId) }
    var dialogueVoiceId by remember { mutableStateOf(initial.dialogueVoiceId) }
    var maleVoiceId by remember { mutableStateOf(initial.maleVoiceId) }
    var femaleVoiceId by remember { mutableStateOf(initial.femaleVoiceId) }
    var elderVoiceId by remember { mutableStateOf(initial.elderVoiceId) }
    var dualRole by remember { mutableStateOf(initial.isDualRoleEnabled) }
    var speed by remember { mutableFloatStateOf(initial.speed) }
    var pitch by remember { mutableFloatStateOf(initial.pitch) }
    var sampleRate by remember { mutableStateOf(initial.sampleRate.toString()) }
    var audioFormat by remember { mutableStateOf(initial.audioFormat) }
    var streaming by remember { mutableStateOf(initial.isStreamingEnabled) }
    var prompt by remember { mutableStateOf(initial.promptInstruction) }
    var doubaoAppId by remember { mutableStateOf(initial.extraParams["appId"].orEmpty()) }
    var doubaoCluster by remember { mutableStateOf(initial.extraParams["cluster"] ?: "volcano_tts") }
    var customPayload by remember { mutableStateOf(initial.customPayloadTemplate) }
    var responsePath by remember { mutableStateOf(initial.responseAudioPath) }

    fun buildDraftConfig(): TtsProviderConfig {
        val extras = if (type == ProviderType.DOUBAO) {
            mapOf("appId" to doubaoAppId.trim(), "cluster" to doubaoCluster.trim().ifBlank { "volcano_tts" })
        } else {
            initial.extraParams
        }
        return initial.copy(
            type = type,
            name = name.trim().ifBlank { type.displayName },
            baseUrl = baseUrl.trim(),
            modelName = modelName.trim(),
            voiceId = voiceId.trim(),
            dialogueVoiceId = dialogueVoiceId.trim(),
            maleVoiceId = maleVoiceId.trim(),
            femaleVoiceId = femaleVoiceId.trim(),
            elderVoiceId = elderVoiceId.trim(),
            isDualRoleEnabled = dualRole,
            speed = speed,
            pitch = pitch,
            sampleRate = sampleRate.toIntOrNull() ?: 24000,
            audioFormat = audioFormat.trim().ifBlank { "mp3" },
            isStreamingEnabled = streaming,
            promptInstruction = prompt.trim(),
            customPayloadTemplate = customPayload,
            responseAudioPath = responsePath.trim(),
            extraParams = extras
        )
    }

    LaunchedEffect(type) {
        viewModel.loadVoicesAndModels(buildDraftConfig(), ProviderCredentials(apiKey, secondaryKey))
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置 · ${type.displayName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveProvider(buildDraftConfig(), ProviderCredentials(apiKey.trim(), secondaryKey.trim()))
                        onBack()
                    }) { Icon(Icons.Default.Save, contentDescription = "保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("供应商协议类型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderType.entries.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = {
                            type = candidate
                            val preset = viewModel.defaultsForType(candidate)
                            baseUrl = preset.baseUrl
                            modelName = preset.modelName
                            voiceId = preset.voiceId
                            sampleRate = preset.sampleRate.toString()
                            audioFormat = preset.audioFormat
                        },
                        label = { Text(candidate.name) }
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("节点名称") },
                modifier = Modifier.fillMaxWidth()
            )
            if (type != ProviderType.EDGE_TTS && type != ProviderType.OFFLINE_SHERPA) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址 (Base URL)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (type.requiresApiKey || type == ProviderType.CUSTOM_HTTP) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key / AccessToken") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secondaryKey,
                    onValueChange = { secondaryKey = it },
                    label = { Text("第二 API Key (并发轮询，可选)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (type == ProviderType.DOUBAO) {
                OutlinedTextField(
                    value = doubaoAppId,
                    onValueChange = { doubaoAppId = it },
                    label = { Text("火山引擎 AppID (必填)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = doubaoCluster,
                    onValueChange = { doubaoCluster = it },
                    label = { Text("Cluster (默认 volcano_tts)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型 ID") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = voiceId,
                    onValueChange = { voiceId = it },
                    label = { Text("主音色 ID") },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("快捷选择音色 (${uiState.availableVoices.size})", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = {
                    viewModel.loadVoicesAndModels(buildDraftConfig(), ProviderCredentials(apiKey, secondaryKey))
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("刷新列表")
                }
            }
            if (uiState.availableVoices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.availableVoices.take(12).forEach { v ->
                        FilterChip(selected = voiceId == v.id, onClick = { voiceId = v.id }, label = { Text(v.name.take(18)) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语速 ${"%.2f".format(speed)}x", modifier = Modifier.width(88.dp))
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.4f..2.5f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音调 ${"%.2f".format(pitch)}", modifier = Modifier.width(88.dp))
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sampleRate,
                    onValueChange = { sampleRate = it },
                    label = { Text("采样率") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = audioFormat,
                    onValueChange = { audioFormat = it },
                    label = { Text("音频格式 (mp3/wav)") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用流式低延迟推流")
                Switch(checked = streaming, onCheckedChange = { streaming = it })
            }
            if (type == ProviderType.MIMO || type == ProviderType.GEMINI) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("导演指令 / Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            DualRoleSection(
                dualRole = dualRole,
                onDualRoleChange = { dualRole = it },
                dialogueVoiceId = dialogueVoiceId,
                onDialogueChange = { dialogueVoiceId = it },
                maleVoiceId = maleVoiceId,
                onMaleChange = { maleVoiceId = it },
                femaleVoiceId = femaleVoiceId,
                onFemaleChange = { femaleVoiceId = it },
                elderVoiceId = elderVoiceId,
                onElderChange = { elderVoiceId = it }
            )

            if (type == ProviderType.CUSTOM_HTTP) {
                CustomHttpSection(
                    customPayload = customPayload,
                    onPayloadChange = { customPayload = it },
                    responsePath = responsePath,
                    onResponsePathChange = { responsePath = it }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.testAndPreview(buildDraftConfig(), ProviderCredentials(apiKey.trim(), secondaryKey.trim()))
                    },
                    enabled = !uiState.isTestingSingle,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (uiState.isTestingSingle) "试听中..." else "测试试听")
                }
                Button(
                    onClick = {
                        viewModel.saveProvider(buildDraftConfig(), ProviderCredentials(apiKey.trim(), secondaryKey.trim()))
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存配置") }
            }
        }
    }
}
