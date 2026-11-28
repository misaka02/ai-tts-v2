package com.aitts.v2.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.aitts.v2.reader.model.BookRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats bookmarks and highlights of a BookRecord into clean Markdown for clipboard or sharing.
 */
object BookNotesExporter {

    fun formatAsMarkdown(record: BookRecord): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("# 《").append(record.title).append("》 读书笔记\n\n")
        if (record.author.isNotBlank()) sb.append("- **作者**：").append(record.author).append('\n')
        sb.append("- **进度**：").append(record.progress.progressPercent).append("%")
        if (record.readingTimeMs > 0L) {
            sb.append(" · **累计阅读**：").append(formatReadingDuration(record.readingTimeMs))
        }
        sb.append("\n\n")

        if (record.highlights.isNotEmpty()) {
            sb.append("## 划线与笔记 (${record.highlights.size})\n\n")
            record.highlights.sortedBy { it.createdAt }.forEach { h ->
                sb.append("> ").append(h.quote.trim()).append('\n')
                if (h.note.isNotBlank()) sb.append("- **笔记**：").append(h.note.trim()).append('\n')
                if (h.createdAt > 0L) sb.append("- *时间：").append(fmt.format(Date(h.createdAt))).append("*\n")
                sb.append('\n')
            }
        }

        if (record.bookmarks.isNotEmpty()) {
            sb.append("## 书签 (${record.bookmarks.size})\n\n")
            record.bookmarks.sortedBy { it.createdAt }.forEach { m ->
                sb.append("- **").append(m.chapterTitle).append("**：").append(m.snippet.trim()).append('\n')
            }
            sb.append('\n')
        }

        if (record.highlights.isEmpty() && record.bookmarks.isEmpty()) {
            sb.append("*暂无书签或划线记录*\n")
        }
        return sb.toString()
    }

    fun formatReadingDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMin / 60L
        val mins = totalMin % 60L
        return if (hours > 0L) "${hours} 小时 ${mins} 分钟" else "${mins} 分钟"
    }

    fun copyMarkdown(context: Context, record: BookRecord) {
        val md = formatAsMarkdown(record)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("${record.title} 笔记", md))
    }

    fun buildShareIntent(record: BookRecord): Intent {
        val md = formatAsMarkdown(record)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "《${record.title}》读书笔记")
            putExtra(Intent.EXTRA_TEXT, md)
        }
        return Intent.createChooser(send, "导出读书笔记")
    }
}
