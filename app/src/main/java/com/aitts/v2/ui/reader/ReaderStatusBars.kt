package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReaderMiniHeaderBar(
    chapterTitle: String,
    horizontalPaddingDp: Float,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = horizontalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ReaderMiniFooterBar(
    leftLabel: String,
    rightLabel: String,
    showBatteryTime: Boolean,
    horizontalPaddingDp: Float,
    textColor: Color,
    accentColor: Color,
    highlightRight: Boolean = false
) {
    val timeBattery = if (showBatteryTime) rememberTimeAndBatteryLabel() else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = horizontalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (timeBattery.isNotBlank()) "$leftLabel  ·  $timeBattery" else leftLabel,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rightLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlightRight) accentColor else textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun rememberTimeAndBatteryLabel(): String {
    val context = LocalContext.current
    var label by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
        while (true) {
            val nowText = formatter.format(Date())
            val pct = runCatching {
                bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }.getOrDefault(-1)
            label = if (pct in 0..100) "$nowText · $pct%" else nowText
            delay(30_000L)
        }
    }
    return label
}
