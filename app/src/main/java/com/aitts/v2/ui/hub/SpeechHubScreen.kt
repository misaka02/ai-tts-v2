package com.aitts.v2.ui.hub

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitts.v2.permission.PermissionHelper
import com.aitts.v2.ui.settings.LogViewerSheet
import com.aitts.v2.ui.theme.InkPaperCard
import com.aitts.v2.ui.theme.InkStatusPill
import com.aitts.v2.ui.theme.PillTone

@Composable
fun SpeechHubScreen(
    viewModel: SpeechHubViewModel,
    onOpenProviders: () -> Unit,
    onEditActiveProvider: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProviderFlow.collectAsStateWithLifecycle()
    val rms by viewModel.rmsFlow.collectAsStateWithLifecycle()
    val sleepActive by viewModel.sleepActiveFlow.collectAsStateWithLifecycle()
    val sleepSec by viewModel.sleepRemainingFlow.collectAsStateWithLifecycle()
    val logSessions by viewModel.logSessionsFlow.collectAsStateWithLifecycle()
    val rawLogs by viewModel.rawLogsFlow.collectAsStateWithLifecycle()

    var showLogs by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showQuickPicker by remember { mutableStateOf(false) }
    var showTuner by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions(context) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HubMasthead(
                state = state,
                sleepActive = sleepActive,
                sleepSec = sleepSec,
                onOpenTtsSettings = { PermissionHelper.openSystemTtsSettings(context) },
                onOpenSleep = { showSleepDialog = true },
                onOpenLogs = { showLogs = true }
            )
        }

        if (sleepActive) {
            item {
                InkPaperCard(highlighted = true, padding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "睡眠定时 ${sleepSec / 60} 分 ${sleepSec % 60} 秒 (末 15 秒渐弱)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { viewModel.setSleepTimer(0) }) { Text("取消") }
                    }
                }
            }
        }

        item {
            ActiveVoiceStrip(
                provider = activeProvider,
                onPickVoice = { showQuickPicker = true },
                onTuneVoice = { showTuner = true }
            )
        }

        item {
            ManuscriptCard(
                state = state,
                onLoadLocal = viewModel::loadLocalQuote,
                onLoadOnline = viewModel::loadOnlineQuote,
                onTextChange = viewModel::updateInputText
            )
        }

        item {
            TransportFooter(
                state = state,
                rms = rms,
                subtitleEnabled = settings.isFloatingSubtitleEnabled,
                onTogglePlay = viewModel::togglePlayPreview,
                onToggleSubtitle = { viewModel.toggleFloatingSubtitle(context, !settings.isFloatingSubtitleEnabled) },
                onExport = { viewModel.exportLastAudio(context) }
            )
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            isActive = sleepActive,
            remainingSeconds = sleepSec,
            onSelectMinutes = { viewModel.setSleepTimer(it); showSleepDialog = false },
            onStop = { viewModel.setSleepTimer(0); showSleepDialog = false },
            onDismiss = { showSleepDialog = false }
        )
    }
    if (showLogs) {
        LogViewerSheet(sessions = logSessions, rawLogs = rawLogs, onClear = viewModel::clearLogs, onDismiss = { showLogs = false })
    }
    if (showQuickPicker) {
        QuickProviderPickerSheet(
            providers = providers,
            activeId = activeProvider.id,
            onSelect = { viewModel.selectActiveProvider(it); showQuickPicker = false },
            onManageAll = { showQuickPicker = false; onOpenProviders() },
            onDismiss = { showQuickPicker = false }
        )
    }
    if (showTuner) {
        VoiceTunerSheet(
            provider = activeProvider,
            isTestingPing = state.isTestingPing,
            lastPingMs = state.lastPingMs,
            onSpeedChange = { viewModel.updateActiveVoice(speed = it) },
            onPitchChange = { viewModel.updateActiveVoice(pitch = it) },
            onStreamingChange = { viewModel.updateActiveVoice(streaming = it) },
            onTestPing = viewModel::testActiveLatency,
            onFullEdit = { showTuner = false; onEditActiveProvider(activeProvider.id) },
            onDismiss = { showTuner = false }
        )
    }
}

@Composable
private fun HubMasthead(
    state: HubUiState,
    sleepActive: Boolean,
    sleepSec: Int,
    onOpenTtsSettings: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("听书台", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.permissionState.isSystemDefaultTts) {
                    InkStatusPill("系统默认引擎已就绪", PillTone.SUCCESS, onClick = onOpenTtsSettings)
                } else {
                    InkStatusPill("设为系统默认 TTS", PillTone.WARNING, onClick = onOpenTtsSettings)
                }
                state.lastLatencyMs?.let { ms -> InkStatusPill("首响 ${ms}ms", PillTone.ACCENT) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepActive) {
                Text("${sleepSec / 60}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onOpenSleep) {
                Icon(
                    Icons.Outlined.Bedtime,
                    contentDescription = "睡眠定时",
                    tint = if (sleepActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenLogs) {
                Icon(Icons.Outlined.Terminal, contentDescription = "日志", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
