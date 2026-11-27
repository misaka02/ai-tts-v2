package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookSearchHit
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Unified abstraction for pluggable e-book format decoders.
 */
interface BookFormatParser {
    val format: BookFormat
    val extensions: List<String>

    fun canParse(file: File, fileName: String): Boolean
    fun indexBook(file: File, fallbackTitle: String): BookIndex
    fun loadChapter(file: File, chapter: ChapterRef): ChapterContent
    fun readResource(file: File, resourcePath: String): ByteArray? = null
}

/**
 * Central registry routing files to the appropriate parser and running full-book search.
 */
object BookFormatRegistry {

    private val parsers: List<BookFormatParser> = listOf(
        EpubBookParser(),
        MarkdownBookParser(),
        HtmlBookParser(),
        TxtBookParser()
    )

    fun parserFor(format: BookFormat): BookFormatParser =
        parsers.first { it.format == format }

    fun resolve(file: File, fileName: String): BookFormatParser {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        parsers.firstOrNull { parser -> ext in parser.extensions && parser.canParse(file, fileName) }
            ?.let { return it }
        parsers.firstOrNull { parser -> parser.format != BookFormat.TXT && parser.canParse(file, fileName) }
            ?.let { return it }
        parsers.firstOrNull { parser -> ext in parser.extensions }
            ?.let { return it }
        return parserFor(BookFormat.TXT)
    }

    suspend fun searchBook(
        file: File,
        format: BookFormat,
        chapters: List<ChapterRef>,
        query: String,
        maxHits: Int = 60
    ): List<BookSearchHit> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || !file.exists()) return emptyList()
        val parser = parserFor(format)
        val hits = mutableListOf<BookSearchHit>()
        for (chapter in chapters) {
            currentCoroutineContext().ensureActive()
            val content = runCatching { parser.loadChapter(file, chapter) }.getOrNull() ?: continue
            for (block in content.blocks) {
                val text = block.speakableText ?: continue
                val idx = text.indexOf(cleanQuery, ignoreCase = true)
                if (idx >= 0) {
                    val start = (idx - 16).coerceAtLeast(0)
                    val end = (idx + cleanQuery.length + 34).coerceAtMost(text.length)
                    val prefix = if (start > 0) "…" else ""
                    val suffix = if (end < text.length) "…" else ""
                    hits.add(
                        BookSearchHit(
                            chapterIndex = chapter.index,
                            chapterTitle = chapter.title,
                            blockIndex = block.index,
                            snippet = "$prefix${text.substring(start, end)}$suffix"
                        )
                    )
                    if (hits.size >= maxHits) return hits
                }
            }
        }
        return hits
    }
}
