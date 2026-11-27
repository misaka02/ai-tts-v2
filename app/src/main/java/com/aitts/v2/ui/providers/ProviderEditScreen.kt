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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkSectionHeader
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

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
    var speed by remember { mutableFloatStateOf(initial.speed) }
    var pitch by remember { mutableFloatStateOf(initial.pitch) }
    var sampleRate by remember { mutableStateOf(initial.sampleRate.toString()) }
    var audioFormat by remember { mutableStateOf(initial.audioFormat) }
    var streaming by remember { mutableStateOf(initial.isStreamingEnabled) }
    var prompt by remember { mutableStateOf(initial.promptInstruction) }
    var customHeaders by remember { mutableStateOf(initial.customHeadersJson) }
    var customPayload by remember { mutableStateOf(initial.customPayloadTemplate) }
    var responsePath by remember { mutableStateOf(initial.responseAudioPath) }
    var extraParams by remember { mutableStateOf(initial.extraParams) }
    var showOfflineSheet by remember { mutableStateOf(false) }

    val descriptor = remember(type) { ProviderDescriptorRegistry.descriptorFor(type) }

    fun buildDraft(): TtsProviderConfig {
        val acoustic = descriptor.acousticSpec
        val speedRange = acoustic.speedSliderRange
        val pitchRange = if (AcousticMapper.supportsNativePitch(type, voiceId.trim())) acoustic.pitchRange else null
        return initial.copy(
            type = type,
            name = name.trim().ifBlank { type.displayName },
            baseUrl = baseUrl.trim(),
            modelName = modelName.trim(),
            voiceId = voiceId.trim(),
            speed = speed.coerceIn(speedRange.start, speedRange.endInclusive),
            pitch = if (pitchRange != null) pitch.coerceIn(pitchRange.start, pitchRange.endInclusive) else 1.0f,
            sampleRate = sampleRate.toIntOrNull() ?: 24000,
            audioFormat = audioFormat.trim().ifBlank { "mp3" },
            isStreamingEnabled = streaming && descriptor.supportsStreaming,
            promptInstruction = prompt.trim(),
            customHeadersJson = customHeaders.ifBlank { "{}" },
            customPayloadTemplate = customPayload,
            responseAudioPath = responsePath.trim(),
            extraParams = extraParams
        )
    }

    LaunchedEffect(type) {
        viewModel.loadVoicesAndModels(buildDraft(), ProviderCredentials(apiKey, secondaryKey), forceRefresh = false)
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("配置 · ${type.displayName}", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveProvider(buildDraft(), ProviderCredentials(apiKey.trim(), secondaryKey.trim()))
                        onBack()
                    }) { Icon(Icons.Outlined.Save, contentDescription = "保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InkSectionHeader("协议与范式")
            InkPaperCard(padding = 14.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkStatusPill(descriptor.category.displayName, PillTone.ACCENT)
                    Text(descriptor.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
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
                                streaming = preset.isStreamingEnabled
                                prompt = preset.promptInstruction
                                customHeaders = preset.customHeadersJson
                                customPayload = preset.customPayloadTemplate
                                responsePath = preset.responseAudioPath
                                extraParams = preset.extraParams
                            },
                            label = { Text(candidate.displayName) }
                        )
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("节点名称") }, modifier = Modifier.fillMaxWidth())
            }

            InkSectionHeader("接口与密钥")
            CredentialsEndpointSection(
                type = type,
                descriptor = descriptor,
                baseUrl = baseUrl,
                onBaseUrlChange = { baseUrl = it },
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                secondaryKey = secondaryKey,
                onSecondaryKeyChange = { secondaryKey = it }
            )

            InkSectionHeader("上游实时音色与模型")
            ModelVoiceDiscoverySection(
                type = type,
                modelName = modelName,
                onModelNameChange = { modelName = it },
                voiceId = voiceId,
                onVoiceIdChange = { voiceId = it },
                availableModels = uiState.availableModels,
                availableVoices = uiState.availableVoices,
                downloadedModelIds = uiState.downloadedModelIds,
                fromRemote = uiState.discoveryFromRemote,
                isLoading = uiState.isLoadingVoices,
                onRefresh = { viewModel.loadVoicesAndModels(buildDraft(), ProviderCredentials(apiKey, secondaryKey), forceRefresh = true) },
                onOpenOfflineCatalog = { showOfflineSheet = true }
            )

            InkSectionHeader("声学调节与厂商参数")
            InkPaperCard(padding = 14.dp) {
                val acoustic = descriptor.acousticSpec
                val speedRange = acoustic.speedSliderRange
                val showPitch = AcousticMapper.supportsNativePitch(type, voiceId)
                Text("语速 ${"%.2f".format(speed)}×", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = speed.coerceIn(speedRange.start, speedRange.endInclusive),
                    onValueChange = { speed = it },
                    valueRange = speedRange
                )
                Text(
                    acoustic.speedModeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showPitch) {
                    acoustic.pitchRange?.let { pRange ->
                        val safePitch = pitch.coerceIn(pRange.start, pRange.endInclusive)
                        Text("音调 ${"%.2f".format(safePitch)}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = safePitch, onValueChange = { pitch = it }, valueRange = pRange)
                        if (acoustic.pitchHint.isNotBlank()) {
                            Text(
                                acoustic.pitchHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (type == ProviderType.DOUBAO) {
                    Text(
                        "当前 bigtts 音色由模型自行控制基频；若阅读器传入系统音调将自动走本地 Sonic",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sampleRate,
                        onValueChange = { sampleRate = it },
                        label = { Text("采样率") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = audioFormat,
                        onValueChange = { audioFormat = it },
                        label = { Text("格式(mp3/wav)") },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (descriptor.supportsStreaming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用原生低延迟流式推流", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = streaming, onCheckedChange = { streaming = it })
                    }
                }
                if (descriptor.supportsDirectorPrompt) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("原生指令 / instructions / Prompt") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                VendorParamsSection(
                    specs = descriptor.extraParams,
                    extraParams = extraParams,
                    dynamicVoiceStyles = uiState.stylesByVoice[voiceId].orEmpty(),
                    onParamChange = { k, v -> extraParams = extraParams + (k to v) }
                )
            }

            if (type == ProviderType.CUSTOM_HTTP) {
                CustomHttpSection(
                    customHeaders = customHeaders,
                    onHeadersChange = { customHeaders = it },
                    customPayload = customPayload,
                    onPayloadChange = { customPayload = it },
                    responsePath = responsePath,
                    onResponsePathChange = { responsePath = it },
                    onApplyPreset = { u, h, p, r -> baseUrl = u; customHeaders = h; customPayload = p; responsePath = r }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testAndPreview(buildDraft(), ProviderCredentials(apiKey.trim(), secondaryKey.trim())) },
                    enabled = !uiState.isTestingSingle,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (uiState.isTestingSingle) "试听中..." else "试听校验")
                }
                Button(
                    onClick = {
                        viewModel.saveProvider(buildDraft(), ProviderCredentials(apiKey.trim(), secondaryKey.trim()))
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存节点") }
            }
        }
    }

    if (showOfflineSheet) {
        OfflineModelsSheet(viewModel = viewModel, onDismiss = { showOfflineSheet = false })
    }
}
