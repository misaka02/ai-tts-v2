package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import com.aitts.v2.reader.model.ReaderBlock
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Standalone HTML / XHTML article & web-novel parser sharing the tolerant EPUB HTML block extractor.
 */
class HtmlBookParser : BookFormatParser {

    override val format: BookFormat = BookFormat.HTML
    override val extensions: List<String> = BookFormat.HTML.extensions

    override fun canParse(file: File, fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in extensions
    }

    override fun indexBook(file: File, fallbackTitle: String): BookIndex {
        val html = readHtmlText(file)
        val title = EpubHtmlParser.extractTitle(html).ifBlank { fallbackTitle }
        val blocks = EpubHtmlParser.parse(html, "")
        val headingIndices = blocks.mapIndexedNotNull { idx, b ->
            if (b is ReaderBlock.Heading && b.level <= 2) idx to b.text else null
        }
        val chapters = if (headingIndices.size >= 2) {
            val list = mutableListOf<ChapterRef>()
            if (headingIndices.first().first > 0) {
                val firstEnd = headingIndices.first().first
                list.add(ChapterRef(0, "前言 / 导读", "html:0:$firstEnd", 0L, firstEnd.toLong()))
            }
            headingIndices.forEachIndexed { chapIdx, (blockStart, chapTitle) ->
                val blockEnd = if (chapIdx + 1 < headingIndices.size) headingIndices[chapIdx + 1].first else blocks.size
                list.add(ChapterRef(list.size, chapTitle, "html:$blockStart:$blockEnd", blockStart.toLong(), blockEnd.toLong()))
            }
            list
        } else {
            listOf(ChapterRef(0, title, "html:0:${blocks.size}", 0L, blocks.size.toLong()))
        }
        return BookIndex(title = title, chapters = chapters)
    }

    override fun loadChapter(file: File, chapter: ChapterRef): ChapterContent {
        val html = readHtmlText(file)
        val allBlocks = EpubHtmlParser.parse(html, "")
        val start = chapter.startOffset.toInt().coerceIn(0, allBlocks.size)
        val end = if (chapter.endOffset <= 0L) allBlocks.size else chapter.endOffset.toInt().coerceIn(start, allBlocks.size)
        val slice = allBlocks.subList(start, end).mapNotNull { block ->
            if (block is ReaderBlock.Image && !block.resourcePath.startsWith("data:image/", ignoreCase = true)) {
                null
            } else {
                block
            }
        }.mapIndexed { idx, block ->
            when (block) {
                is ReaderBlock.Heading -> block.copy(index = idx)
                is ReaderBlock.Paragraph -> block.copy(index = idx)
                is ReaderBlock.Image -> block.copy(index = idx)
                is ReaderBlock.Divider -> block.copy(index = idx)
            }
        }
        return ChapterContent(chapter.index, chapter.title, slice)
    }

    override fun readResource(file: File, resourcePath: String): ByteArray? {
        if (!resourcePath.startsWith("data:image/", ignoreCase = true)) return null
        val commaIdx = resourcePath.indexOf(',')
        if (commaIdx <= 0) return null
        val header = resourcePath.substring(0, commaIdx)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val payload = resourcePath.substring(commaIdx + 1).replace(Regex("\\s+"), "")
        return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
    }

    private fun readHtmlText(file: File): String {
        val len = file.length()
        if (len > MAX_HTML_BYTES) {
            throw IOException("HTML 文件超出 ${MAX_HTML_BYTES / (1024 * 1024)} MB 上限")
        }
        val bytes = file.readBytes()
        val detected = CharsetDetector.detect(bytes.copyOf(minOf(8192, bytes.size)))
        return String(bytes, detected.bomLength, bytes.size - detected.bomLength, detected.charset)
    }

    companion object {
        private const val MAX_HTML_BYTES = 32L * 1024L * 1024L
    }
}
