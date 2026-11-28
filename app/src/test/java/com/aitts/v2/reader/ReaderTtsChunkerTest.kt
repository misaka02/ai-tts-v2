package com.aitts.v2.reader

import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderTtsChunkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsChunkerTest {

    private val sampleBlocks = listOf(
        ReaderBlock.Heading(0, "第一章", 1),
        ReaderBlock.Paragraph(1, "「……」"),
        ReaderBlock.Paragraph(2, "两人无言以对。"),
        ReaderBlock.Paragraph(3, "但话说回来，对方可是那个凶名赫赫的罗森克鲁兹啊！"),
        ReaderBlock.Paragraph(4, "第一句结束。第二句开始了！第三句紧跟其后？")
    )

    @Test
    fun `smart merge combines short blocks up to targetChars and preserves first block index`() {
        val chunks = ReaderTtsChunker.buildChunks(
            chapterIndex = 1, chapterTitle = "Ch1", chapterHref = "p-003.xhtml",
            blocks = sampleBlocks, startBlockIndex = 0,
            mode = ReaderTtsChunkMode.SMART_MERGE, targetChars = 120,
            rules = emptyList(), globalSettings = GlobalSettings()
        )
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].blockIndex)
        assertEquals(4, chunks[0].endBlockIndex)
        assertEquals("p-003.xhtml", chunks[0].chapterHref)
        assertTrue(chunks[0].text.contains("第一章") && chunks[0].text.contains("第三句紧跟其后"))

        val creds = ProviderCredentials(apiKey = "primary", secondaryApiKey = "backup")
        assertEquals("primary", ReaderTtsChunker.rotateCredentials(creds, 0).apiKey)
        assertEquals("backup", ReaderTtsChunker.rotateCredentials(creds, 1).apiKey)
    }

    @Test
    fun `by paragraph and by sentence honor mode and target char cap`() {
        val paraChunks = ReaderTtsChunker.buildChunks(
            0, "Ch0", "p-001.xhtml", sampleBlocks, 1,
            ReaderTtsChunkMode.BY_PARAGRAPH, 100, emptyList(), GlobalSettings()
        )
        assertEquals(4, paraChunks.size)
        assertEquals(listOf(1, 2, 3, 4), paraChunks.map { it.blockIndex })

        val sentChunks = ReaderTtsChunker.buildChunks(
            0, "Ch0", "p-001.xhtml", listOf(ReaderBlock.Paragraph(0, "你好。世界。")), 0,
            ReaderTtsChunkMode.BY_SENTENCE, 80, emptyList(), GlobalSettings()
        )
        assertEquals(2, sentChunks.size)
        assertEquals(listOf("你好。", "世界。"), sentChunks.map { it.text })

        val longText = "第一段很长的测试文本用来验证字数上限，" + "继续补充正文内容。".repeat(12)
        val cappedChunks = ReaderTtsChunker.buildChunks(
            0, "Ch0", "p-001.xhtml", listOf(ReaderBlock.Paragraph(0, longText)), 0,
            ReaderTtsChunkMode.BY_PARAGRAPH, 80, emptyList(), GlobalSettings()
        )
        assertTrue(cappedChunks.size >= 2)
        assertTrue(cappedChunks.all { it.text.length <= 80 })
    }
}
