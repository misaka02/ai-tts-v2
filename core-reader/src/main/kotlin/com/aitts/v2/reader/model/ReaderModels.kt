package com.aitts.v2.reader.model

import kotlinx.serialization.Serializable

@Serializable
enum class BookFormat(val label: String, val extensions: List<String>) {
    EPUB("EPUB", listOf("epub")),
    TXT("TXT", listOf("txt", "text", "log")),
    MARKDOWN("Markdown", listOf("md", "markdown")),
    HTML("HTML", listOf("html", "htm", "xhtml"))
}

@Serializable
data class ReadingProgress(
    val chapterIndex: Int = 0,
    val blockIndex: Int = 0,
    val totalChapters: Int = 1,
    val updatedAt: Long = 0L
) {
    val progressPercent: Int
        get() {
            val total = totalChapters.coerceAtLeast(1)
            val current = (chapterIndex + 1).coerceIn(1, total)
            return ((current.toFloat() / total) * 100f).toInt().coerceIn(0, 100)
        }
}

@Serializable
data class BookBookmark(
    val id: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long
)

@Serializable
data class BookRecord(
    val id: String,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val fileName: String,
    val coverFileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val chapterCount: Int = 1,
    val addedAt: Long = 0L,
    val lastReadAt: Long = 0L,
    val progress: ReadingProgress = ReadingProgress(),
    val bookmarks: List<BookBookmark> = emptyList()
)

@Serializable
data class ChapterRef(
    val index: Int,
    val title: String,
    val locator: String,
    val startOffset: Long = 0L,
    val endOffset: Long = 0L
)

@Serializable
data class BookIndex(
    val title: String,
    val author: String = "",
    val language: String = "",
    val description: String = "",
    val chapters: List<ChapterRef>,
    val coverResourcePath: String? = null
)

sealed interface ReaderBlock {
    val index: Int
    val speakableText: String?

    data class Heading(
        override val index: Int,
        val text: String,
        val level: Int = 1
    ) : ReaderBlock {
        override val speakableText: String get() = text
    }

    data class Paragraph(
        override val index: Int,
        val text: String,
        val isQuote: Boolean = false
    ) : ReaderBlock {
        override val speakableText: String get() = text
    }

    data class Image(
        override val index: Int,
        val resourcePath: String,
        val altText: String = ""
    ) : ReaderBlock {
        override val speakableText: String? get() = null
    }

    data class Divider(
        override val index: Int
    ) : ReaderBlock {
        override val speakableText: String? get() = null
    }
}

data class ChapterContent(
    val chapterIndex: Int,
    val title: String,
    val blocks: List<ReaderBlock>
)

data class BookSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val blockIndex: Int,
    val snippet: String
)
