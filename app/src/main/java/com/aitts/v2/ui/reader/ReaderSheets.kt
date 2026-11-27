package com.aitts.v2.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.ReaderSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    isEpub: Boolean,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onImportFont: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportFont)
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabLabels = listOf("外观", "字体", "排版", "交互")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = Color.Black.copy(alpha = 0.20f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 420.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("阅读与排版设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onResetDefaults) { Text("恢复默认") }
            }
            TabRow(selectedTabIndex = selectedTab) {
                tabLabels.forEachIndexed { idx, label ->
                    Tab(selected = selectedTab == idx, onClick = { selectedTab = idx }, text = { Text(label) })
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                when (selectedTab) {
                    0 -> ReaderAppearanceTab(settings = settings, onUpdate = onUpdate)
                    1 -> ReaderFontTab(
                        settings = settings,
                        isEpub = isEpub,
                        onUpdate = onUpdate,
                        onPickCustomFont = { fontPicker.launch(arrayOf("font/*", "*/*")) }
                    )
                    2 -> ReaderLayoutTab(settings = settings, isEpub = isEpub, onUpdate = onUpdate)
                    else -> ReaderInteractionTab(settings = settings, onUpdate = onUpdate)
                }
            }
        }
    }
}
