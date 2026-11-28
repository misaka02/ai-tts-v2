package com.aitts.v2.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.delay
import org.json.JSONObject
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

/**
 * Captures the non-collapsed system bar insets per screen orientation so hiding/showing
 * immersive bars does not resize the underlying reader container frame by frame.
 */
@Composable
fun rememberStableSystemBarPadding(): PaddingValues {
    val orientation = LocalConfiguration.current.orientation
    val statusPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var maxTop by remember(orientation) { mutableStateOf(statusPad) }
    var maxBottom by remember(orientation) { mutableStateOf(navPad) }
    if (statusPad > maxTop) maxTop = statusPad
    if (navPad > maxBottom) maxBottom = navPad
    return PaddingValues(top = maxTop, bottom = maxBottom)
}

internal fun isBookmarkMatched(
    bookmarks: List<BookBookmark>,
    chapterIndex: Int,
    currentLocatorJson: String,
    currentProgression: Float,
    visibleBlockIndex: Int
): Boolean = bookmarks.any { mark ->
    if (mark.chapterIndex != chapterIndex) return@any false
    if (currentLocatorJson.isBlank() || mark.locatorJson.isBlank()) {
        return@any mark.blockIndex == visibleBlockIndex
    }
    if (mark.locatorJson == currentLocatorJson) return@any true
    val markProg = runCatching {
        JSONObject(mark.locatorJson).optJSONObject("locations")?.optDouble("totalProgression", -1.0) ?: -1.0
    }.getOrDefault(-1.0)
    markProg >= 0.0 && kotlin.math.abs(markProg - currentProgression.toDouble()) <= 0.006
}

internal fun nextQuickDayNightTheme(settings: ReaderSettings, appDefaultIsDark: Boolean): ReaderPaperTheme {
    val curDark = when {
        settings.customBgHex != 0L -> androidx.core.graphics.ColorUtils.calculateLuminance(settings.customBgHex.toInt()) < 0.45
        settings.paperTheme == ReaderPaperTheme.FOLLOW_APP -> appDefaultIsDark
        else -> settings.paperTheme.isDark
    }
    return if (curDark) ReaderPaperTheme.CREAM else ReaderPaperTheme.NIGHT
}

internal fun nextMiniTtsSpeed(current: Float): Float = when {
    current < 1.15f -> 1.25f
    current < 1.45f -> 1.50f
    current < 1.85f -> 2.00f
    else -> 1.00f
}
