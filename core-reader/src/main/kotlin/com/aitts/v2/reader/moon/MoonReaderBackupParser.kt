package com.aitts.v2.reader.moon

import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookHighlight
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

@Serializable
data class MoonBookMeta(
    val filename: String = "",
    val bookName: String = "",
    val author: String = "",
    val favorite: String = "",
    val category: String = "",
    val description: String = "",
    val addTime: String = ""
) {
    val cleanAuthor: String get() = author.trim()
    val shelfGroup: String get() = favorite.trim().ifBlank { category.trim() }
    val addedAtMs: Long get() = addTime.trim().toLongOrNull() ?: 0L
}

data class MoonReadingPosition(
    val timestampMs: Long,
    val chapterIndex: Int,
    val splitIndex: Int,
    val charOffset: Int,
    val percent: Float
)

data class MoonAnnotation(
    val id: String,
    val bookTitle: String,
    val path: String,
    val chapterIndex: Int,
    val splitIndex: Int,
    val charOffset: Int,
    val length: Int,
    val colorArgb: Long,
    val timestampMs: Long,
    val bookmarkName: String,
    val note: String,
    val quote: String,
    val isBookmark: Boolean
)

/**
 * Pure-Kotlin parser for Moon+ Reader (静读天下) `.Moon+` Google Drive backups:
 * `books.sync` (zlib JSON), `Cache/<book>.po` (position), and `Cache/<book>.an` (zlib highlights/notes).
 */
object MoonReaderBackupParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val poRegex = Regex("""^(\d+)\*(\d+)@(\d+)#(\d+):([\d.]+)%?$""")

    fun decompressIfZlib(bytes: ByteArray, maxOutBytes: Int = 8 * 1024 * 1024): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x78.toByte()) return bytes
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    if (out.size() + count > maxOutBytes) {
                        error("Moon+ backup payload exceeds $maxOutBytes bytes")
                    }
                    out.write(buf, 0, count)
                }
            }
            if (out.size() > 0) out.toByteArray() else bytes
        } catch (_: Throwable) {
            bytes
        } finally {
            inflater.end()
        }
    }

    fun parseBooksSync(rawBytes: ByteArray): List<MoonBookMeta> {
        val text = decompressIfZlib(rawBytes).toString(Charsets.UTF_8).trim()
        if (!text.startsWith("[")) return emptyList()
        return runCatching { json.decodeFromString<List<MoonBookMeta>>(text) }
            .getOrDefault(emptyList())
            .filter { it.filename.isNotBlank() }
    }

    fun parsePositionPo(rawText: String): MoonReadingPosition? {
        val clean = rawText.trim()
        val m = poRegex.matchEntire(clean) ?: return null
        val ts = m.groupValues[1].toLongOrNull() ?: 0L
        val ch = m.groupValues[2].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val split = m.groupValues[3].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val offset = m.groupValues[4].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pct = (m.groupValues[5].toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
        return MoonReadingPosition(ts, ch, split, offset, pct)
    }

    fun parseAnnotationsAn(rawBytes: ByteArray): List<MoonAnnotation> {
        val text = decompressIfZlib(rawBytes).toString(Charsets.UTF_8)
        val lines = text.trimEnd('\r', '\n').split('\n').map { it.trimEnd('\r') }
        val blocks = splitAnnotationBlocks(lines)
        return blocks.mapNotNull(::parseAnnotationBlock)
    }

    fun toBookHighlights(annotations: List<MoonAnnotation>): List<BookHighlight> =
        annotations.filter { !it.isBookmark && (it.quote.isNotBlank() || it.note.isNotBlank()) }
            .mapIndexed { idx, ann ->
                BookHighlight(
                    id = deterministicId("hl", ann, idx),
                    chapterIndex = ann.chapterIndex,
                    blockIndex = 0,
                    quote = ann.quote.ifBlank { ann.note },
                    note = ann.note,
                    colorHex = ann.colorArgb,
                    createdAt = ann.timestampMs
                )
            }

    fun toBookBookmarks(annotations: List<MoonAnnotation>, pos: MoonReadingPosition? = null): List<BookBookmark> {
        val anMarks = annotations.filter { it.isBookmark || it.bookmarkName.isNotBlank() }
            .mapIndexed { idx, ann ->
                BookBookmark(
                    id = deterministicId("bm", ann, idx),
                    chapterIndex = ann.chapterIndex,
                    blockIndex = 0,
                    chapterTitle = ann.bookmarkName.ifBlank { "第 ${ann.chapterIndex + 1} 节" },
                    snippet = ann.quote.ifBlank { ann.note }.ifBlank { "静读天下书签 (${ann.chapterIndex + 1})" }.take(72),
                    createdAt = ann.timestampMs
                )
            }
        if (pos == null) return anMarks
        val posMark = BookBookmark(
            id = "moon_pos_${pos.chapterIndex}_${pos.charOffset}",
            chapterIndex = pos.chapterIndex,
            blockIndex = 0,
            chapterTitle = "静读天下进度 (${String.format("%.1f", pos.percent)}%)",
            snippet = "从静读天下继承的最后阅读位置：第 ${pos.chapterIndex + 1} 节 / 字符偏移 ${pos.charOffset}",
            createdAt = pos.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        return (listOf(posMark) + anMarks).distinctBy { it.id }
    }

    private fun splitAnnotationBlocks(lines: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            if (line == "#") {
                if (!current.isNullOrEmpty()) result.add(current)
                current = mutableListOf()
            } else {
                current?.add(line)
            }
        }
        if (!current.isNullOrEmpty()) result.add(current)
        return result
    }

    private fun parseAnnotationBlock(fields: List<String>): MoonAnnotation? {
        val clean = fields.dropLastWhile { it.isEmpty() }.let { if (it.size >= 13) it else fields }
        if (clean.size < 13) return null
        val id = clean[0].trim()
        val ch = clean[4].trim().toIntOrNull()?.coerceAtLeast(0) ?: return null
        val split = clean[5].trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val offset = clean[6].trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val len = clean[7].trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rawColor = clean[8].trim().toLongOrNull()
        val colorArgb = normalizeColorArgb(rawColor)
        val ts = clean[9].trim().toLongOrNull() ?: 0L
        val bookmarkName = clean[10].trim()
        val note = clean[11].replace("<BR>", "\n").trim()
        val hasTrailingFlags = clean.size >= 16
        val quoteEnd = if (hasTrailingFlags) clean.size - 3 else clean.size
        val quote = clean.subList(12, quoteEnd).joinToString("\n").replace("<BR>", "\n").trim()
        val isBmFlag = hasTrailingFlags && clean.last().trim() == "1"
        return MoonAnnotation(
            id = id,
            bookTitle = clean[1].trim(),
            path = clean[2].trim(),
            chapterIndex = ch,
            splitIndex = split,
            charOffset = offset,
            length = len,
            colorArgb = colorArgb,
            timestampMs = ts,
            bookmarkName = bookmarkName,
            note = note,
            quote = quote,
            isBookmark = isBmFlag || (bookmarkName.isNotBlank() && quote.isBlank() && note.isBlank())
        )
    }

    private fun normalizeColorArgb(raw: Long?): Long {
        if (raw == null || raw == 0L) return 0xFFFFD54FL
        val unsigned = raw and 0xFFFFFFFFL
        return 0xFF000000L or (unsigned and 0x00FFFFFFL)
    }

    private fun deterministicId(prefix: String, ann: MoonAnnotation, idx: Int): String =
        "moon_${prefix}_${ann.id.ifBlank { idx.toString() }}_${ann.chapterIndex}_${ann.charOffset}_${ann.timestampMs}"
}
