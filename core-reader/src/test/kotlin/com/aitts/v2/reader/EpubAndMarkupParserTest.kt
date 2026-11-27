package com.aitts.v2.reader

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.reader.parser.EpubBookParser
import com.aitts.v2.reader.parser.HtmlBookParser
import com.aitts.v2.reader.parser.MarkdownBookParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubAndMarkupParserTest {

    private fun testDir(name: String): File =
        File("build/test-tmp/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun `parses EPUB 3 Nav, metadata, cover, ruby furigana, images and blocks zip-slip`() {
        val dir = testDir("epub3_test")
        val epubFile = File(dir, "sample.epub")
        val fakeCover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val fakeInlineImg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9, 8)

        writeZip(
            epubFile,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().toByteArray(),
                "OEBPS/content.opf" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>星海漫游指南</dc:title>
                        <dc:creator>林深</dc:creator>
                        <dc:language>zh-CN</dc:language>
                      </metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
                        <item id="ill" href="images/ill.jpg" media-type="image/jpeg"/>
                        <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="nav"/>
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                """.trimIndent().toByteArray(),
                "OEBPS/nav.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <nav epub:type="toc"><ol><li><a href="text/ch1.xhtml#top">第一章 启程 &amp; 遇险</a></li></ol></nav>
                      </body>
                    </html>
                """.trimIndent().toByteArray(),
                "OEBPS/images/cover.png" to fakeCover,
                "OEBPS/images/ill.jpg" to fakeInlineImg,
                "OEBPS/text/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><style>body { color: #000; }</style><title>ignored</title></head>
                      <body>
                        <h1>第一章 启程</h1>
                        <p>东京（<ruby>東京<rt>とうきょう</rt></ruby>）出发！</p>
                        <blockquote>不要恐慌 &mdash; 银河指南</blockquote>
                        <img src="../images/ill.jpg" alt="飞船插图"/>
                        <script>alert("noise")</script>
                      </body>
                    </html>
                """.trimIndent().toByteArray()
            )
        )

        val parser = BookFormatRegistry.resolve(epubFile, "sample.epub")
        assertTrue(parser is EpubBookParser)
        val index = parser.indexBook(epubFile, "fallback")
        assertEquals("星海漫游指南", index.title)
        assertEquals("林深", index.author)
        assertEquals("OEBPS/images/cover.png", index.coverResourcePath)
        assertEquals(1, index.chapters.size)
        assertEquals("第一章 启程 & 遇险", index.chapters[0].title)

        val content = parser.loadChapter(epubFile, index.chapters[0])
        assertEquals(4, content.blocks.size)
        assertEquals("第一章 启程", (content.blocks[0] as ReaderBlock.Heading).text)
        assertEquals("东京（東京）出发！", (content.blocks[1] as ReaderBlock.Paragraph).text)
        val quote = content.blocks[2] as ReaderBlock.Paragraph
        assertTrue(quote.isQuote)
        assertEquals("不要恐慌 — 银河指南", quote.text)
        val img = content.blocks[3] as ReaderBlock.Image
        assertEquals("OEBPS/images/ill.jpg", img.resourcePath)
        assertArrayEquals(fakeCover, parser.readResource(epubFile, index.coverResourcePath!!))
        assertArrayEquals(fakeInlineImg, parser.readResource(epubFile, img.resourcePath))
        assertNull(parser.readResource(epubFile, "../../etc/passwd"))
    }

    @Test
    fun `parses large multi-12KB Chinese UTF-8 EPUB chapter and GBK XHTML without mojibake`() {
        val dir = testDir("epub_long_cjk")
        val epubFile = File(dir, "long_cjk.epub")
        val repeatedParagraph = "江南可采莲，莲叶何田田。鱼戏莲叶间，鱼戏莲叶东，鱼戏莲叶西，鱼戏莲叶南，鱼戏莲叶北。"
        val chapterHtml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><h1>长卷正文</h1>")
            repeat(80) { idx -> append("<p>${idx + 1}. $repeatedParagraph</p>") }
            append("</body></html>")
        }
        val gbkChapterBytes = "<html><head><meta charset=\"gb2312\"/></head><body><p>镕基旧事：大漠孤烟直，长河落日圆。</p></body></html>"
            .toByteArray(java.nio.charset.Charset.forName("GB18030"))

        writeZip(
            epubFile,
            mapOf(
                "META-INF/container.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                """.trimIndent().toByteArray(),
                "content.opf" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package version="2.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>长卷集</dc:title></metadata>
                      <manifest>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                    </package>
                """.trimIndent().toByteArray(),
                "ch1.xhtml" to chapterHtml.toByteArray(Charsets.UTF_8),
                "ch2.xhtml" to gbkChapterBytes
            )
        )

        val parser = EpubBookParser()
        val index = parser.indexBook(epubFile, "长卷集")
        assertEquals(2, index.chapters.size)

        val c1 = parser.loadChapter(epubFile, index.chapters[0])
        assertTrue(c1.blocks.size == 81)
        assertEquals("长卷正文", c1.blocks[0].speakableText)
        assertTrue(c1.blocks[1].speakableText?.contains("江南可采莲") == true)
        assertFalse(c1.blocks.any { it.speakableText?.contains('\uFFFD') == true })

        val c2 = parser.loadChapter(epubFile, index.chapters[1])
        assertEquals("镕基旧事：大漠孤烟直，长河落日圆。", c2.blocks[0].speakableText)
    }

    @Test
    fun `parses EPUB 2 NCX TOC, Markdown and HTML files cleanly`() {
        val dir = testDir("markup_and_ncx")
        val mdFile = File(dir, "notes.md")
        mdFile.writeText(
            """
            # 笔记总览
            ```bash
            # not a heading inside code fence
            ## also ignored
            ```
            第一段包含**加粗**和[链接](https://example.com)。

            ## 第二部分
            > 引用一段话
            ---
            正文结尾。
            """.trimIndent()
        )
        val mdParser = MarkdownBookParser()
        val mdIndex = mdParser.indexBook(mdFile, "fallback")
        assertEquals(BookFormat.MARKDOWN, mdParser.format)
        assertEquals(2, mdIndex.chapters.size)
        val mdCh2 = mdParser.loadChapter(mdFile, mdIndex.chapters[1])
        assertEquals(4, mdCh2.blocks.size)
        assertTrue(mdCh2.blocks[1] is ReaderBlock.Paragraph && (mdCh2.blocks[1] as ReaderBlock.Paragraph).isQuote)
        assertTrue(mdCh2.blocks[2] is ReaderBlock.Divider)

        val htmlFile = File(dir, "article.html")
        htmlFile.writeText(
            """
            <html><head><title>网页长文</title></head><body>
              <p>这是引言段落。</p>
              <h2>第一节</h2>
              <p>第一行<br/>第二行</p>
              <img src="data:image/png;base64,iVBORw0KGgo=" alt="内嵌图"/>
              <img src="relative_missing.png"/>
              <h2>第二节</h2>
              <p>尾声</p>
            </body></html>
            """.trimIndent()
        )
        val htmlParser = HtmlBookParser()
        val htmlIdx = htmlParser.indexBook(htmlFile, "fallback")
        assertEquals("网页长文", htmlIdx.title)
        assertEquals(3, htmlIdx.chapters.size)
        assertEquals("前言 / 导读", htmlIdx.chapters[0].title)
        val sec1 = htmlParser.loadChapter(htmlFile, htmlIdx.chapters[1])
        assertEquals(4, sec1.blocks.size)
        val dataImg = sec1.blocks[3] as ReaderBlock.Image
        assertTrue((htmlParser.readResource(htmlFile, dataImg.resourcePath)?.size ?: 0) > 0)
    }

    private fun writeZip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
