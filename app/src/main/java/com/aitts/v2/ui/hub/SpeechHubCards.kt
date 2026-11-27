package com.aitts.v2.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.InkWaveformCanvas
import com.aitts.v2.ui.theme.PillTone

@Composable
internal fun ActiveVoiceStrip(
    provider: TtsProviderConfig,
    onPickVoice: () -> Unit,
    onTuneVoice: () -> Unit
) {
    val tag = when (provider.type) {
        ProviderType.OFFLINE_SHERPA -> "离线"
        ProviderType.EDGE_TTS -> "免费"
        else -> "云端"
    }
    val voiceLabel = provider.voiceId.ifBlank { "默认音色" }
    val stats = "$voiceLabel · ${"%.2f".format(provider.speed)}× · 音调 ${"%.2f".format(provider.pitch)}"
    InkPaperCard(padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    InkStatusPill(tag, PillTone.ACCENT)
                }
                Text(
                    text = stats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPickVoice) { Text("换声线") }
                IconButton(onClick = onTuneVoice) {
                    Icon(Icons.Outlined.Tune, contentDescription = "声线调音", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
internal fun ManuscriptCard(
    state: HubUiState,
    onLoadLocal: () -> Unit,
    onLoadOnline: () -> Unit,
    onTextChange: (String) -> Unit
) {
    InkPaperCard(padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.quoteSource,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onLoadLocal) { Text("书摘") }
                TextButton(onClick = onLoadOnline, enabled = !state.isFetchingQuote) {
                    Text(if (state.isFetchingQuote) "读取中" else "一言")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value = state.inputText,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text("输入或粘贴小说段落进行试听...", style = MaterialTheme.typography.bodyMedium) },
            minLines = 6,
            maxLines = 10,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (state.activeSegmentText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "朗读第 ${state.segmentIndex}/${state.totalSegments} 段 · ${state.activeSegmentText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "${state.inputText.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun TransportFooter(
    state: HubUiState,
    rms: Float,
    subtitleEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onToggleSubtitle: () -> Unit,
    onExport: () -> Unit
) {
    val subTint = if (subtitleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    InkPaperCard(padding = 14.dp) {
        InkWaveformCanvas(rms = rms, isPlaying = state.isPlaying, isSynthesizing = state.isSynthesizing)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onTogglePlay,
                shape = CircleShape,
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                if (state.isSynthesizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("合成中...")
                } else if (state.isPlaying) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止朗读")
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始朗读")
                }
            }
            OutlinedButton(
                onClick = onToggleSubtitle,
                shape = CircleShape,
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.ClosedCaption, contentDescription = "字幕开关", tint = subTint, modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = onExport,
                enabled = state.canExport,
                shape = CircleShape,
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.Download, contentDescription = "导出 WAV", modifier = Modifier.size(18.dp))
            }
        }
    }
}
