package com.aitts.v2.reader

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.reader.parser.TxtBookParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TxtBookParserTest {

    private fun testDir(name: String): File =
        File("build/test-tmp/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun `indexes Chinese and English chapters and loads exact chapter blocks`() {
        val dir = testDir("txt_chapters")
        val txt = File(dir, "novel.txt")
        txt.writeText(
            """
            引子
            　　山雨欲来风满楼。

            第一章 初入江湖
            　　这是第一段正文内容。
            　　这是第二段正文内容，包含“对话”。

            Chapter 2 The Turning Point
            Second chapter body paragraph.
            """.trimIndent(),
            Charsets.UTF_8
        )

        val parser = TxtBookParser()
        val index = parser.indexBook(txt, "测试小说")
        assertEquals(3, index.chapters.size)
        assertEquals("引子", index.chapters[0].title)
        assertEquals("第一章 初入江湖", index.chapters[1].title)
        assertEquals("Chapter 2 The Turning Point", index.chapters[2].title)

        val ch1 = parser.loadChapter(txt, index.chapters[1])
        assertTrue(ch1.blocks.first() is ReaderBlock.Heading)
        assertEquals("第一章 初入江湖", ch1.blocks.first().speakableText)
        assertEquals(3, ch1.blocks.size)
        assertEquals("这是第二段正文内容，包含“对话”。", ch1.blocks[2].speakableText)
    }

    @Test
    fun `splits headerless large TXT into bounded virtual chapters and searches hits`() = runTest {
        val dir = testDir("txt_large")
        val txt = File(dir, "headerless.txt")
        val gbFile = txt.apply {
            val line = "长篇小说无章节正文测试段落，包含关键词星辰大海。\n"
            val sb = StringBuilder()
            repeat(1200) { sb.append(line) }
            writeBytes(sb.toString().toByteArray(Charset.forName("GB18030")))
        }

        val parser = TxtBookParser()
        val index = parser.indexBook(gbFile, "无标题大文件")
        assertTrue("Expected virtual chunks > 1, got ${index.chapters.size}", index.chapters.size >= 2)

        val loaded = parser.loadChapter(gbFile, index.chapters[1])
        assertTrue(loaded.blocks.isNotEmpty())
        assertTrue(loaded.blocks.any { it.speakableText?.contains("星辰大海") == true })

        val hits = BookFormatRegistry.searchBook(gbFile, BookFormat.TXT, index.chapters, "星辰大海", maxHits = 10)
        assertEquals(10, hits.size)
    }

    @Test
    fun `indexes UTF-16LE TXT chapter markers without byte offset drift`() {
        val dir = testDir("txt_utf16le")
        val utf16File = File(dir, "novel_utf16.txt")
        val text = "第一章 黎明\r\n太阳升起。\r\n\r\n第二章 正午\r\n微风轻拂。"
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        utf16File.writeBytes(payload)

        val parser = TxtBookParser()
        val index = parser.indexBook(utf16File, "UTF16小说")
        assertEquals(2, index.chapters.size)
        assertEquals("第一章 黎明", index.chapters[0].title)
        assertEquals("第二章 正午", index.chapters[1].title)
        assertEquals("微风轻拂。", parser.loadChapter(utf16File, index.chapters[1]).blocks[1].speakableText)
    }
}
