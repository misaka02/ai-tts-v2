package com.aitts.v2.reader

import com.aitts.v2.reader.parser.CharsetDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class CharsetDetectorTest {

    @Test
    fun `detects UTF-8 BOM, UTF-8 CJK, UTF-16LE and GB18030 accurately`() {
        val sampleText = "第一章 风起青萍之末，少年踏入书院。"
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sampleText.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(utf8Bom).charset)
        assertEquals(3, CharsetDetector.detect(utf8Bom).bomLength)

        val utf8Plain = sampleText.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(utf8Plain).charset)
        assertEquals(0, CharsetDetector.detect(utf8Plain).bomLength)

        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sampleText.toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, CharsetDetector.detect(utf16Le).charset)
        assertEquals(2, CharsetDetector.detect(utf16Le).bomLength)

        val gbBytes = sampleText.toByteArray(Charset.forName("GB18030"))
        assertEquals("GB18030", CharsetDetector.detect(gbBytes).charset.name())
    }

    @Test
    fun `transcodeToUtf8 strips BOM and converts GB18030 to clean UTF-8`() {
        val source = "楔子\n天地玄黄，宇宙洪荒。日月盈昃，辰宿列张。\n"
        val gbBytes = source.toByteArray(Charset.forName("GB18030"))
        val out = ByteArrayOutputStream()
        val written = CharsetDetector.transcodeToUtf8(ByteArrayInputStream(gbBytes), out)
        assertTrue(written > 0)
        assertEquals(source, out.toString(Charsets.UTF_8.name()))

        val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + source.toByteArray(Charsets.UTF_16BE)
        val out2 = ByteArrayOutputStream()
        CharsetDetector.transcodeToUtf8(ByteArrayInputStream(utf16BeBom), out2)
        assertEquals(source, out2.toString(Charsets.UTF_8.name()))
    }
}
