package com.aitts.v2.reader.moon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class MoonReaderBackupParserTest {

    @Test
    fun `parses zlib compressed books sync json`() {
        val raw = """
            [
              {
                "addTime": "1723626993614",
                "author": "鎌池和馬\n",
                "bookName": "创约 某魔法的禁书目录5",
                "category": "轻小说\n",
                "favorite": "创约",
                "filename": "创约 某魔法的禁书目录5.epub"
              }
            ]
        """.trimIndent()
        val compressed = deflateZlib(raw.toByteArray(Charsets.UTF_8))

        val parsed = MoonReaderBackupParser.parseBooksSync(compressed)
        assertEquals(1, parsed.size)
        assertEquals("创约 某魔法的禁书目录5.epub", parsed[0].filename)
        assertEquals("鎌池和馬", parsed[0].cleanAuthor)
        assertEquals("创约", parsed[0].shelfGroup)
        assertEquals(1723626993614L, parsed[0].addedAtMs)
    }

    @Test
    fun `parses po position format accurately`() {
        val pos = MoonReaderBackupParser.parsePositionPo("1654836912812*1@0#6:2.2%\n")
        assertNotNull(pos)
        assertEquals(1654836912812L, pos!!.timestampMs)
        assertEquals(1, pos.chapterIndex)
        assertEquals(0, pos.splitIndex)
        assertEquals(6, pos.charOffset)
        assertEquals(2.2f, pos.percent, 0.01f)
        assertNull(MoonReaderBackupParser.parsePositionPo("invalid-po"))
    }

    @Test
    fun `parses zlib an annotations preserving trailing hash inside quote`() {
        val rawAn = buildString {
            appendLine("5826809")
            appendLine("indent:true")
            appendLine("trim:false")
            appendLine("#")
            appendLine("7")
            appendLine("创约 某魔法的禁书目录5")
            appendLine("/sdcard/Books/c5.epub")
            appendLine("/sdcard/books/c5.epub")
            appendLine("26")
            appendLine("0")
            appendLine("2909")
            appendLine("49")
            appendLine("1996532479")
            appendLine("1655633690261")
            appendLine("")
            appendLine("想法笔记")
            appendLine("善性的幽灵弗里尔桑德#")
            appendLine("0")
            appendLine("0")
            appendLine("0")
            appendLine("#")
            appendLine("8")
            appendLine("创约 某魔法的禁书目录5")
            appendLine("/sdcard/Books/c5.epub")
            appendLine("/sdcard/books/c5.epub")
            appendLine("30")
            appendLine("0")
            appendLine("100")
            appendLine("0")
            appendLine("0")
            appendLine("1655633699000")
            appendLine("重要书签")
            appendLine("")
            appendLine("")
            appendLine("0")
            appendLine("0")
            appendLine("1")
        }
        val anns = MoonReaderBackupParser.parseAnnotationsAn(deflateZlib(rawAn.toByteArray(Charsets.UTF_8)))
        assertEquals(2, anns.size)
        assertEquals("善性的幽灵弗里尔桑德#", anns[0].quote)
        assertEquals("想法笔记", anns[0].note)
        assertEquals(0xFF00AAFFL, anns[0].colorArgb)
        assertTrue(!anns[0].isBookmark)
        assertTrue(anns[1].isBookmark)

        val hls = MoonReaderBackupParser.toBookHighlights(anns)
        assertEquals(1, hls.size)
        val bms = MoonReaderBackupParser.toBookBookmarks(anns, MoonReaderBackupParser.parsePositionPo("1655633700000*26@0#2909:42.5%"))
        assertEquals(2, bms.size)
    }

    private fun deflateZlib(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }
}
