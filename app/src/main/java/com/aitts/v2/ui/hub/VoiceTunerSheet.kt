package com.aitts.v2.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.AcousticMapper
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTunerSheet(
    provider: TtsProviderConfig,
    isTestingPing: Boolean,
    lastPingMs: Long?,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onStreamingChange: (Boolean) -> Unit,
    onTestPing: () -> Unit,
    onFullEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val descriptor = remember(provider.type) { ProviderDescriptorRegistry.descriptorFor(provider.type) }
    val acoustic = descriptor.acousticSpec
    val speedRange = acoustic.speedSliderRange
    val showPitch = AcousticMapper.supportsNativePitch(provider.type, provider.voiceId)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("声线快速调音 · ${provider.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            InkPaperCard(padding = 14.dp) {
                Text("节点语速 ${"%.2f".format(provider.speed)}×", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = provider.speed.coerceIn(speedRange.start, speedRange.endInclusive),
                    onValueChange = onSpeedChange,
                    valueRange = speedRange
                )
                Text(
                    acoustic.speedModeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showPitch) {
                    acoustic.pitchRange?.let { pRange ->
                        val safePitch = provider.pitch.coerceIn(pRange.start, pRange.endInclusive)
                        Text("节点音调 ${"%.2f".format(safePitch)}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = safePitch, onValueChange = onPitchChange, valueRange = pRange)
                        if (acoustic.pitchHint.isNotBlank()) {
                            Text(
                                acoustic.pitchHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (descriptor.supportsStreaming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用原生流式推流", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = provider.isStreamingEnabled, onCheckedChange = onStreamingChange)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onTestPing, enabled = !isTestingPing, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    val pingText = when {
                        isTestingPing -> "测速中..."
                        lastPingMs != null && lastPingMs >= 0 -> "${lastPingMs}ms"
                        else -> "连接测速"
                    }
                    Text(pingText)
                }
                Button(onClick = onFullEdit, modifier = Modifier.weight(1f)) {
                    Text("完整节点配置")
                }
            }
        }
    }
}
