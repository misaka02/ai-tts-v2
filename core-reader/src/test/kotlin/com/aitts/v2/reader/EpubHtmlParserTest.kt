package com.aitts.v2.reader

import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.parser.EpubFootnoteExtractor
import com.aitts.v2.reader.parser.EpubHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubHtmlParserTest {

    @Test
    fun `smartRubyReading substitutes rt for non-lexical symbol bases and skips standard furigana`() {
        val html = """
            <html><body>
              <p><ruby>◎<rt class="ex">正是</rt></ruby>因为如此，作为死人的右手，<ruby>幻想杀手<rt>Imagine Breaker</rt></ruby>的力量还在。</p>
            </body></html>
        """.trimIndent()
        val smartBlocks = EpubHtmlParser.parse(html, "OEBPS/Text", smartRubyReading = true)
        val rawBlocks = EpubHtmlParser.parse(html, "OEBPS/Text", smartRubyReading = false)

        val smartText = (smartBlocks.single() as ReaderBlock.Paragraph).text
        val rawText = (rawBlocks.single() as ReaderBlock.Paragraph).text

        assertEquals("正是因为如此，作为死人的右手，幻想杀手的力量还在。", smartText)
        assertEquals("◎因为如此，作为死人的右手，幻想杀手的力量还在。", rawText)
    }

    @Test
    fun `strips inline noteref symbols from prose while preserving footnote extraction`() {
        val bodyHtml = """
            <html><body>
              <p>面对哈耳庇厄<a epub:type="noteref" class="noteref" href="p-note.xhtml#note0402">㊟</a>的袭击。</p>
            </body></html>
        """.trimIndent()
        val noteHtml = """
            <html><body>
              <p><a id="note0402" href="p-003.xhtml#ref0402">↩</a><span epub:type="footnote">哈耳庇厄，希腊神话中的鹰身女妖。</span></p>
            </body></html>
        """.trimIndent()

        val parsed = EpubHtmlParser.parse(bodyHtml, "OEBPS/Text", smartRubyReading = true, stripNoterefMarkers = true)
        assertEquals("面对哈耳庇厄的袭击。", (parsed.single() as ReaderBlock.Paragraph).text)

        val visibleHtml = EpubHtmlParser.parse(bodyHtml, "OEBPS/Text")
        assertTrue((visibleHtml.single() as ReaderBlock.Paragraph).text.contains("㊟"))

        val extracted = EpubFootnoteExtractor.extractById(noteHtml, "note0402")
        assertTrue(extracted.contains("鹰身女妖"))
    }
}
