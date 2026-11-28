package com.aitts.v2.ui.reader

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aitts.v2.reader.BookNotesExporter
import com.aitts.v2.reader.ReaderTtsState
import com.aitts.v2.reader.model.BookRecord

@Composable
fun ReaderJumpDialog(
    currentChapter: Int,
    totalChapters: Int,
    currentPercent: Int,
    onJumpPercent: (Float) -> Unit,
    onJumpChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var percentText by remember { mutableStateOf(currentPercent.toString()) }
    var chapterText by remember { mutableStateOf((currentChapter + 1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("精确跳转") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = percentText,
                        onValueChange = { s -> percentText = s.filter { it.isDigit() }.take(3) },
                        label = { Text("全书进度 (0-100%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val pct = percentText.toIntOrNull()?.coerceIn(0, 100) ?: return@TextButton
                        onJumpPercent(pct / 100f)
                        onDismiss()
                    }) { Text("跳转") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = chapterText,
                        onValueChange = { s -> chapterText = s.filter { it.isDigit() }.take(5) },
                        label = { Text("指定章节 (1-$totalChapters)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val chap = chapterText.toIntOrNull()?.coerceIn(1, totalChapters.coerceAtLeast(1)) ?: return@TextButton
                        onJumpChapter(chap - 1)
                        onDismiss()
                    }) { Text("前往") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun ReaderBookInfoDialog(
    book: BookRecord,
    chapterCount: Int,
    imageCount: Int,
    formattedSize: String,
    onExportNotesCopy: () -> Unit,
    onExportNotesShare: () -> Unit,
    onReindexBook: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书籍信息与笔记导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("书名：《${book.title}》", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (book.author.isNotBlank()) Text("作者：${book.author}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "格式：${book.format.label} · $formattedSize · $chapterCount 章 · $imageCount 张插图",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "累计阅读：${BookNotesExporter.formatReadingDuration(book.readingTimeMs)} · 进度 ${book.progress.progressPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "书签 ${book.bookmarks.size} 个 · 划线笔记 ${book.highlights.size} 条",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = onExportNotesCopy, modifier = Modifier.weight(1f)) { Text("复制 MD 笔记") }
                    OutlinedButton(onClick = onExportNotesShare, modifier = Modifier.weight(1f)) { Text("分享笔记") }
                }
                OutlinedButton(onClick = onReindexBook, modifier = Modifier.fillMaxWidth()) { Text("重新解析并修复章节目录索引") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun ReaderMiniTtsBar(
    ttsState: ReaderTtsState,
    speed: Float,
    onCycleSpeed: () -> Unit,
    onOpenConsole: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = ttsState.activeSentenceText.ifBlank { if (ttsState.isSynthesizing) "准备朗读..." else "正在听书" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCycleSpeed) { Text("${"%.2f".format(speed)}×") }
            TextButton(onClick = onOpenConsole) { Text("控制台") }
            IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Stop, contentDescription = "停止听书", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ReaderAutoScrollOverlay(
    speed: Int,
    isPaused: Boolean,
    onSpeedDelta: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        contentColor = Color.White,
        modifier = modifier.padding(bottom = 44.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { onSpeedDelta(-1) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Remove, contentDescription = "减速", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Text("自动阅读 $speed", style = MaterialTheme.typography.labelMedium, color = Color.White)
            IconButton(onClick = { onSpeedDelta(1) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "加速", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onTogglePause, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = "暂停/继续",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "退出自动阅读", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ReaderBrightnessHud(brightnessFraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = "亮度 ${(brightnessFraction * 100).toInt().coerceIn(5, 100)}%",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White
        )
    }
}

@Composable
fun ReaderEdgeBrightnessStrip(
    currentBrightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onHudChange: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var startBrightness by remember { mutableFloatStateOf(0.5f) }
    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight(0.75f)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        val b = if (currentBrightness >= 0f) {
                            currentBrightness
                        } else {
                            (context as? Activity)?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        }
                        startBrightness = b
                        onHudChange(b)
                    },
                    onVerticalDrag = { _, dragAmount ->
                        val next = (startBrightness - dragAmount / 550f).coerceIn(0.05f, 1f)
                        startBrightness = next
                        onHudChange(next)
                        onBrightnessChange(next)
                    },
                    onDragEnd = { onHudChange(null) },
                    onDragCancel = { onHudChange(null) }
                )
            }
    )
}

@Composable
fun ReaderJumpBackChip(
    label: String = "回到朗读处",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.padding(end = 16.dp, bottom = 92.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ReaderCornerTuneButton(
    chromeBg: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = chromeBg.copy(alpha = 0.78f),
        contentColor = contentColor,
        modifier = modifier.padding(top = 36.dp, end = 12.dp)
    ) {
        IconButton(onClick = onClick) { Icon(Icons.Outlined.Tune, contentDescription = "打开阅读菜单") }
    }
}

@Composable
fun ReaderErrorPlaceholder(
    message: String,
    textColor: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(message, color = textColor)
        Button(onClick = onBack) { Text("返回书架") }
    }
}
