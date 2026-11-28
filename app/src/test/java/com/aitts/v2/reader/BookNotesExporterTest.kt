package com.aitts.v2.reader

import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ReadingProgress
import org.junit.Assert.assertTrue
import org.junit.Test

class BookNotesExporterTest {

    @Test
    fun `formats highlights notes bookmarks and reading duration into clean markdown`() {
        val record = BookRecord(
            id = "b1",
            title = "测试书名",
            author = "张三",
            format = BookFormat.EPUB,
            fileName = "book.epub",
            readingTimeMs = 90 * 60_000L,
            progress = ReadingProgress(chapterIndex = 1, totalChapters = 10, totalProgression = 0.42f),
            bookmarks = listOf(BookBookmark("m1", 1, 0, "第二章", "重要段落摘要", 1700000000000L)),
            highlights = listOf(BookHighlight("h1", 1, 2, "", "这是一句摘录原文", "个人思考笔记", 0xFFFFD54FL, 1700000005000L))
        )
        val md = BookNotesExporter.formatAsMarkdown(record)
        assertTrue(md.contains("# 《测试书名》 读书笔记"))
        assertTrue(md.contains("1 小时 30 分钟"))
        assertTrue(md.contains("> 这是一句摘录原文"))
        assertTrue(md.contains("**笔记**：个人思考笔记"))
        assertTrue(md.contains("**第二章**：重要段落摘要"))
    }
}
