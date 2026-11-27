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
import java.nio.charset.Charset

/**
 * Streaming byte-offset TXT parser capable of indexing 50MB+ novels with minimal RAM
 * and opening any chapter in O(1) seek time.
 */
class TxtBookParser : BookFormatParser {

    override val format: BookFormat = BookFormat.TXT
    override val extensions: List<String> = BookFormat.TXT.extensions

    override fun canParse(file: File, fileName: String): Boolean = file.exists() && file.isFile

    override fun indexBook(file: File, fallbackTitle: String): BookIndex {
        val charset = detectFileCharset(file)
        val detectedMarkers = scanChapterMarkers(file, charset)
        val fileLength = file.length().coerceAtLeast(0L)
        val chapters = buildChunkedChapters(file, charset, detectedMarkers, fileLength, fallbackTitle)
        return BookIndex(
            title = fallbackTitle,
            chapters = chapters
        )
    }

    override fun loadChapter(file: File, chapter: ChapterRef): ChapterContent {
        val fileLen = file.length()
        val start = chapter.startOffset.coerceIn(0L, fileLen)
        val end = if (chapter.endOffset <= 0L) fileLen else chapter.endOffset.coerceIn(start, fileLen)
        val byteLen = (end - start).coerceIn(0L, MAX_CHAPTER_BYTES).toInt()
        if (byteLen == 0) {
            return ChapterContent(chapter.index, chapter.title, emptyList())
        }

        val rawBytes = ByteArray(byteLen)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(rawBytes)
        }
        val charset = detectFileCharset(file)
        val offsetInSlice = if (start == 0L) charset.bomLength.coerceAtMost(rawBytes.size) else 0
        val text = String(rawBytes, offsetInSlice, rawBytes.size - offsetInSlice, charset.charset)
        val blocks = parsePlainTextBlocks(text, chapter.title)
        return ChapterContent(chapter.index, chapter.title, blocks)
    }

    private fun detectFileCharset(file: File): DetectedCharset {
        val sample = ByteArray(minOf(64 * 1024L, file.length()).toInt())
        if (sample.isEmpty()) return DetectedCharset(Charsets.UTF_8, 0)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < sample.size) {
                val read = input.read(sample, offset, sample.size - offset)
                if (read <= 0) break
                offset += read
            }
        }
        return CharsetDetector.detect(sample)
    }

    private fun scanChapterMarkers(file: File, detected: DetectedCharset): List<ChapterMarker> {
        val isUtf16Le = detected.charset == Charsets.UTF_16LE
        val isUtf16Be = detected.charset == Charsets.UTF_16BE
        if (isUtf16Le || isUtf16Be) {
            return scanUtf16ChapterMarkers(file, detected, isUtf16Le)
        }
        val markers = mutableListOf<ChapterMarker>()
        BufferedInputStream(file.inputStream(), 32 * 1024).use { bis ->
            var pos = skipBom(bis, detected.bomLength)
            val lineBuf = ByteArrayOutputStream(256)
            var lineStart = pos
            while (true) {
                val b = bis.read()
                if (b < 0) {
                    evaluateLine(lineBuf, lineStart, detected.charset, markers)
                    break
                }
                pos++
                if (b == 0x0A) {
                    evaluateLine(lineBuf, lineStart, detected.charset, markers)
                    lineBuf.reset()
                    lineStart = pos
                } else if (b != 0x0D && lineBuf.size() < MAX_HEADING_SCAN_BYTES) {
                    lineBuf.write(b)
                }
            }
        }
        return markers
    }

    private fun scanUtf16ChapterMarkers(
        file: File,
        detected: DetectedCharset,
        littleEndian: Boolean
    ): List<ChapterMarker> {
        val markers = mutableListOf<ChapterMarker>()
        BufferedInputStream(file.inputStream(), 32 * 1024).use { bis ->
            var pos = skipBom(bis, detected.bomLength)
            val lineBuf = ByteArrayOutputStream(512)
            var lineStart = pos
            while (true) {
                val b0 = bis.read()
                val b1 = bis.read()
                if (b0 < 0 || b1 < 0) {
                    evaluateLine(lineBuf, lineStart, detected.charset, markers)
                    break
                }
                pos += 2L
                val codeUnit = if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
                if (codeUnit == 0x000A) {
                    evaluateLine(lineBuf, lineStart, detected.charset, markers)
                    lineBuf.reset()
                    lineStart = pos
                } else if (codeUnit != 0x000D && lineBuf.size() < MAX_HEADING_SCAN_BYTES * 2) {
                    lineBuf.write(b0)
                    lineBuf.write(b1)
                }
            }
        }
        return markers
    }

    private fun skipBom(bis: BufferedInputStream, bomLength: Int): Long {
        var skipped = 0L
        repeat(bomLength) { if (bis.read() >= 0) skipped++ }
        return skipped
    }

    private fun evaluateLine(
        lineBuf: ByteArrayOutputStream,
        lineStart: Long,
        charset: Charset,
        markers: MutableList<ChapterMarker>
    ) {
        if (lineBuf.size() == 0) return
        val line = String(lineBuf.toByteArray(), charset).trim(' ', '\t', '\u3000', '\r', '\n')
        if (line.length in 2..64 && CHAPTER_HEADING_REGEX.matches(line)) {
            if (markers.isEmpty() || markers.last().offset < lineStart) {
                markers.add(ChapterMarker(title = line, offset = lineStart))
            }
        }
    }

    private fun buildChunkedChapters(
        file: File,
        detected: DetectedCharset,
        markers: List<ChapterMarker>,
        fileLength: Long,
        fallbackTitle: String
    ): List<ChapterRef> {
        if (fileLength == 0L) {
            return listOf(ChapterRef(0, fallbackTitle, "txt:0", 0L, 0L))
        }
        val rawRanges = collectRawRanges(markers, fileLength, fallbackTitle, detected.bomLength.toLong())
        val isUtf16 = detected.charset == Charsets.UTF_16LE || detected.charset == Charsets.UTF_16BE
        val finalList = mutableListOf<ChapterRef>()
        RandomAccessFile(file, "r").use { raf ->
            for ((title, range) in rawRanges) {
                appendSplitRanges(raf, title, range, isUtf16, finalList)
            }
        }
        return finalList
    }

    private fun collectRawRanges(
        markers: List<ChapterMarker>,
        fileLength: Long,
        fallbackTitle: String,
        bomOffset: Long
    ): List<Pair<String, LongRange>> {
        if (markers.isEmpty()) return listOf(fallbackTitle to (0L..fileLength))
        val rawRanges = mutableListOf<Pair<String, LongRange>>()
        if (markers.first().offset - bomOffset > PREFACE_THRESHOLD_BYTES) {
            rawRanges.add("序章 / 前言" to (0L..markers.first().offset))
        }
        for (i in markers.indices) {
            val start = if (i == 0 && markers.first().offset - bomOffset <= PREFACE_THRESHOLD_BYTES) {
                0L
            } else {
                markers[i].offset
            }
            val end = if (i + 1 < markers.size) markers[i + 1].offset else fileLength
            if (end > start) rawRanges.add(markers[i].title to (start..end))
        }
        return rawRanges
    }

    private fun appendSplitRanges(
        raf: RandomAccessFile,
        title: String,
        range: LongRange,
        isUtf16: Boolean,
        out: MutableList<ChapterRef>
    ) {
        if (range.last - range.first <= MAX_VIRTUAL_CHUNK_BYTES) {
            out.add(ChapterRef(out.size, title, "txt:${range.first}", range.first, range.last))
            return
        }
        var cursor = range.first
        var part = 1
        while (cursor < range.last) {
            val nominalEnd = minOf(cursor + MAX_VIRTUAL_CHUNK_BYTES, range.last)
            val alignedNominal = if (isUtf16 && (nominalEnd - cursor) % 2L != 0L) nominalEnd - 1L else nominalEnd
            val partEnd = if (alignedNominal < range.last) {
                snapOffsetToNewline(raf, cursor, alignedNominal, range.last, isUtf16)
            } else {
                range.last
            }
            val label = if (part == 1) title else "$title ($part)"
            out.add(ChapterRef(out.size, label, "txt:$cursor", cursor, partEnd))
            cursor = partEnd
            part++
        }
    }

    private fun snapOffsetToNewline(
        raf: RandomAccessFile,
        minStart: Long,
        nominalEnd: Long,
        hardMax: Long,
        isUtf16: Boolean
    ): Long {
        val searchBackStart = maxOf(minStart + 1024L, nominalEnd - 2048L)
        if (searchBackStart >= nominalEnd) return minOf(nominalEnd, hardMax)
        val buf = ByteArray((nominalEnd - searchBackStart).toInt())
        raf.seek(searchBackStart)
        raf.readFully(buf)
        return if (isUtf16) {
            snapUtf16Offset(buf, searchBackStart, minStart, nominalEnd, hardMax)
        } else {
            snapByteOffset(buf, searchBackStart, nominalEnd, hardMax)
        }
    }

    private fun snapUtf16Offset(
        buf: ByteArray,
        searchBackStart: Long,
        minStart: Long,
        nominalEnd: Long,
        hardMax: Long
    ): Long {
        var i = buf.lastIndex - 1
        while (i >= 0) {
            val isLeLf = buf[i] == 0x0A.toByte() && buf[i + 1] == 0x00.toByte()
            val isBeLf = buf[i] == 0x00.toByte() && buf[i + 1] == 0x0A.toByte()
            val cand = searchBackStart + i + 2
            if ((isLeLf || isBeLf) && (cand - minStart) % 2L == 0L) return cand
            i--
        }
        return minOf(nominalEnd, hardMax)
    }

    private fun snapByteOffset(
        buf: ByteArray,
        searchBackStart: Long,
        nominalEnd: Long,
        hardMax: Long
    ): Long {
        for (i in buf.lastIndex downTo 0) {
            if (buf[i] == 0x0A.toByte()) return searchBackStart + i + 1
        }
        for (back in 0..minOf(3, buf.lastIndex)) {
            if ((buf[buf.lastIndex - back].toInt() and 0xC0) != 0x80) {
                return minOf(nominalEnd - back, hardMax)
            }
        }
        return minOf(nominalEnd, hardMax)
    }

    private fun parsePlainTextBlocks(text: String, chapterTitle: String): List<ReaderBlock> {
        val lines = text.split('\n')
        val blocks = mutableListOf<ReaderBlock>()
        for (rawLine in lines) {
            val clean = rawLine.trim(' ', '\t', '\u3000', '\r')
            if (clean.isEmpty()) continue
            if (blocks.isEmpty() && clean == chapterTitle) {
                blocks.add(ReaderBlock.Heading(index = blocks.size, text = clean, level = 1))
            } else if (clean.length in 2..64 && CHAPTER_HEADING_REGEX.matches(clean) && blocks.size <= 1) {
                blocks.add(ReaderBlock.Heading(index = blocks.size, text = clean, level = 1))
            } else {
                blocks.add(ReaderBlock.Paragraph(index = blocks.size, text = clean))
            }
        }
        return blocks
    }

    private data class ChapterMarker(val title: String, val offset: Long)

    companion object {
        private const val PREFACE_THRESHOLD_BYTES = 128L
        private const val MAX_HEADING_SCAN_BYTES = 512
        private const val MAX_VIRTUAL_CHUNK_BYTES = 24L * 1024L
        private const val MAX_CHAPTER_BYTES = 1024L * 1024L

        private val CHAPTER_HEADING_REGEX = Regex(
            pattern = "^(?:" +
                "第[\\d零一二三四五六七八九十百千万两]+[章节回卷部篇节集](?:[\\s:：、.-].*)?" +
                "|(?:Chapter|CHAPTER|Part|PART|Prologue|Epilogue)\\s*[\\dIVXLC]+(?:[\\s:：.-].*)?" +
                "|序[章言曲幕]?|引子|楔子|尾声|后记|番外(?:[\\s:：.-].*)?" +
                ")$"
        )
    }
}
