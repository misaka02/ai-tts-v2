package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import com.aitts.v2.reader.model.ReaderBlock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Markdown e-book parser supporting ATX headings chapter splitting, quotes,
 * horizontal rules, and inline Markdown stripping.
 */
class MarkdownBookParser : BookFormatParser {

    override val format: BookFormat = BookFormat.MARKDOWN
    override val extensions: List<String> = BookFormat.MARKDOWN.extensions

    override fun canParse(file: File, fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in extensions

    override fun indexBook(file: File, fallbackTitle: String): BookIndex {
        val (bookTitle, markers) = scanMarkdownMarkers(file, fallbackTitle)
        val totalLen = file.length()
        val rawRanges = buildRawRanges(markers, totalLen, bookTitle)
        val chapters = splitOversizedRanges(file, rawRanges)
        return BookIndex(title = bookTitle, chapters = chapters)
    }

    private fun scanMarkdownMarkers(file: File, fallbackTitle: String): Pair<String, List<Pair<String, Long>>> {
        var bookTitle = fallbackTitle
        val markers = mutableListOf<Pair<String, Long>>()
        var inCodeFence = false
        val reader = BufferedInputStream(file.inputStream(), 32 * 1024)
        reader.use { bis ->
            val buf = ByteArrayOutputStream(256)
            var pos = 0L
            while (true) {
                val lineStart = pos
                val bytesRead = readLineBytes(bis, buf)
                if (bytesRead < 0) break
                pos += bytesRead
                val trimmed = String(buf.toByteArray(), Charsets.UTF_8).trim(' ', '\t', '\r')
                val action = evaluateMarkdownLine(trimmed, inCodeFence)
                if (action == -1) inCodeFence = !inCodeFence
                val match = if (action == 1) extractHeading(trimmed, bookTitle, fallbackTitle, markers.isEmpty()) else null
                if (match != null && match.second) bookTitle = match.first
                if (match != null) markers.add(match.first to lineStart)
            }
        }
        return bookTitle to markers
    }

    private fun readLineBytes(bis: BufferedInputStream, buf: ByteArrayOutputStream): Long {
        buf.reset()
        var count = 0L
        while (true) {
            val b = bis.read()
            if (b < 0) return if (count == 0L) -1L else count
            count++
            if (b == 0x0A) return count
            if (buf.size() < 4096) buf.write(b)
        }
    }

    private fun evaluateMarkdownLine(trimmed: String, inCodeFence: Boolean): Int = when {
        trimmed.startsWith("```") -> -1
        inCodeFence -> 0
        trimmed.startsWith("#") -> 1
        else -> 0
    }

    private fun extractHeading(
        trimmed: String,
        currentTitle: String,
        fallbackTitle: String,
        firstMarker: Boolean
    ): Pair<String, Boolean>? {
        val h1 = H1_RE.matchEntire(trimmed)
        if (h1 != null) {
            val heading = stripInlineMarkdown(h1.groupValues[1]).trim()
            val updateBookTitle = firstMarker && currentTitle == fallbackTitle && heading.isNotEmpty()
            return heading.ifEmpty { fallbackTitle } to updateBookTitle
        }
        val h2 = H2_RE.matchEntire(trimmed)
        if (h2 != null) {
            val heading = stripInlineMarkdown(h2.groupValues[1]).trim()
            return heading.ifEmpty { "章节" } to false
        }
        return null
    }

    private fun buildRawRanges(
        markers: List<Pair<String, Long>>,
        totalLen: Long,
        bookTitle: String
    ): List<Pair<String, LongRange>> {
        if (markers.isEmpty()) return listOf(bookTitle to (0L..totalLen))
        val ranges = mutableListOf<Pair<String, LongRange>>()
        if (markers.first().second > 64L) {
            ranges.add("前言" to (0L..markers.first().second))
        }
        for (i in markers.indices) {
            val start = markers[i].second.coerceAtMost(totalLen)
            val end = if (i + 1 < markers.size) markers[i + 1].second.coerceAtMost(totalLen) else totalLen
            if (end > start) ranges.add(markers[i].first to (start..end))
        }
        return ranges
    }

    private fun splitOversizedRanges(
        file: File,
        rawRanges: List<Pair<String, LongRange>>
    ): List<ChapterRef> {
        val chapters = mutableListOf<ChapterRef>()
        RandomAccessFile(file, "r").use { raf ->
            for ((title, range) in rawRanges) {
                if (range.last - range.first <= MAX_CHUNK_BYTES) {
                    chapters.add(ChapterRef(chapters.size, title, "md:${range.first}", range.first, range.last))
                    continue
                }
                var cursor = range.first
                var part = 1
                while (cursor < range.last) {
                    val nominalEnd = minOf(cursor + MAX_CHUNK_BYTES, range.last)
                    val partEnd = if (nominalEnd < range.last) snapLineEnd(raf, cursor, nominalEnd) else nominalEnd
                    val label = if (part == 1) title else "$title ($part)"
                    chapters.add(ChapterRef(chapters.size, label, "md:$cursor", cursor, partEnd))
                    cursor = partEnd
                    part++
                }
            }
        }
        return chapters
    }

    private fun snapLineEnd(raf: RandomAccessFile, minStart: Long, nominalEnd: Long): Long {
        val lookbackStart = maxOf(minStart + 512L, nominalEnd - 4096L)
        if (lookbackStart < nominalEnd) {
            val buf = ByteArray((nominalEnd - lookbackStart).toInt())
            raf.seek(lookbackStart)
            raf.readFully(buf)
            for (i in buf.lastIndex downTo 0) {
                if (buf[i] == 0x0A.toByte()) return lookbackStart + i + 1
            }
            for (back in 0..minOf(3, buf.lastIndex)) {
                if ((buf[buf.lastIndex - back].toInt() and 0xC0) != 0x80) {
                    return nominalEnd - back
                }
            }
        }
        return nominalEnd
    }

    override fun loadChapter(file: File, chapter: ChapterRef): ChapterContent {
        val fileLen = file.length()
        val start = chapter.startOffset.coerceIn(0L, fileLen)
        val end = if (chapter.endOffset <= 0L) fileLen else chapter.endOffset.coerceIn(start, fileLen)
        val span = (end - start).coerceIn(0L, MAX_CHUNK_BYTES * 2L).toInt()
        if (span == 0) return ChapterContent(chapter.index, chapter.title, emptyList())

        val bytes = ByteArray(span)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(bytes)
        }
        val blocks = parseMarkdownBlocks(bytes.toString(Charsets.UTF_8))
        return ChapterContent(chapter.index, chapter.title, blocks)
    }

    private fun parseMarkdownBlocks(text: String): List<ReaderBlock> {
        val blocks = mutableListOf<ReaderBlock>()
        var inCodeFence = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r').trim()
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (line.isEmpty()) continue
            if (!inCodeFence && HR_RE.matches(line)) {
                blocks.add(ReaderBlock.Divider(blocks.size))
                continue
            }
            val heading = if (!inCodeFence) HEADING_RE.matchEntire(line) else null
            if (heading != null) {
                val level = heading.groupValues[1].length.coerceIn(1, 4)
                val clean = stripInlineMarkdown(heading.groupValues[2]).trim()
                if (clean.isNotEmpty()) blocks.add(ReaderBlock.Heading(blocks.size, clean, level))
                continue
            }
            val isQuote = !inCodeFence && line.startsWith(">")
            val body = if (isQuote) line.removePrefix(">").trim() else line
            val clean = if (inCodeFence) line else stripInlineMarkdown(body).trim()
            if (clean.isNotEmpty()) blocks.add(ReaderBlock.Paragraph(blocks.size, clean, isQuote))
        }
        return blocks
    }

    private fun stripInlineMarkdown(text: String): String = text
        .replace(IMAGE_RE, "")
        .replace(LINK_RE, "$1")
        .replace(INLINE_CODE_RE, "$1")
        .replace(BOLD_ITALIC_RE, "")

    companion object {
        private const val MAX_CHUNK_BYTES = 512L * 1024L
        private val H1_RE = Regex("^#\\s+(.+)$")
        private val H2_RE = Regex("^##\\s+(.+)$")
        private val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
        private val HR_RE = Regex("^(?:[-*_]\\s*){3,}$")
        private val IMAGE_RE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
        private val LINK_RE = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        private val INLINE_CODE_RE = Regex("`([^`]+)`")
        private val BOLD_ITALIC_RE = Regex("[*_~]+")
    }
}
