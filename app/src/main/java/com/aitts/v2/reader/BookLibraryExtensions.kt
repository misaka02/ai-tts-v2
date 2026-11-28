package com.aitts.v2.reader

import android.net.Uri
import com.aitts.v2.reader.model.BookHighlight
import com.aitts.v2.reader.model.BookRecord
import java.io.File

fun BookLibraryRepository.addHighlight(bookId: String, highlight: BookHighlight) {
    mutateBook(bookId) { book ->
        book.copy(highlights = listOf(highlight) + book.highlights.filterNot { it.id == highlight.id })
    }
}

fun BookLibraryRepository.updateHighlightNote(bookId: String, highlightId: String, note: String) {
    mutateBook(bookId) { book ->
        book.copy(
            highlights = book.highlights.map { h ->
                if (h.id == highlightId) h.copy(note = note.trim()) else h
            }
        )
    }
}

fun BookLibraryRepository.updateHighlightColor(bookId: String, highlightId: String, colorHex: Long) {
    mutateBook(bookId) { book ->
        book.copy(
            highlights = book.highlights.map { h ->
                if (h.id == highlightId) h.copy(colorHex = colorHex) else h
            }
        )
    }
}

fun BookLibraryRepository.removeHighlight(bookId: String, highlightId: String) {
    mutateBook(bookId) { book ->
        book.copy(highlights = book.highlights.filterNot { it.id == highlightId })
    }
}

fun BookLibraryRepository.togglePin(bookId: String) {
    mutateBook(bookId) { book -> book.copy(isPinned = !book.isPinned) }
}

fun BookLibraryRepository.updateCategory(bookId: String, category: String) {
    mutateBook(bookId) { book -> book.copy(category = category.trim()) }
}

fun BookLibraryRepository.updateBookMetadata(bookId: String, title: String, author: String, category: String) {
    mutateBook(bookId) { book ->
        book.copy(
            title = title.trim().ifBlank { book.title },
            author = author.trim(),
            category = category.trim()
        )
    }
}

fun BookLibraryRepository.addReadingTime(bookId: String, deltaMs: Long) {
    if (deltaMs <= 0L) return
    mutateBook(bookId) { book -> book.copy(readingTimeMs = book.readingTimeMs + deltaMs) }
}

fun BookLibraryRepository.importCustomFont(uri: Uri): Result<String> = runCatching {
    val fontsDir = File(rootDir, "fonts").apply { mkdirs() }
    val rawName = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull().orEmpty()
    val ext = rawName.substringAfterLast('.', "").lowercase().takeIf { it in setOf("ttf", "otf", "woff", "woff2") } ?: "ttf"
    val outFile = File(fontsDir, "custom_${System.currentTimeMillis()}.$ext")
    appContext.contentResolver.openInputStream(uri)?.use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取字体文件")
    if (outFile.length() < 1024L) {
        outFile.delete()
        error("字体文件无效或过小")
    }
    fontsDir.listFiles()?.filter { it != outFile }?.forEach { it.delete() }
    outFile.absolutePath
}

fun BookLibraryRepository.mergeRemoteStates(remoteRecords: List<BookRecord>): Int {
    var mergedCount = 0
    updateAllBooks { current ->
        val mutable = current.toMutableList()
        for (remote in remoteRecords) {
            val idx = mutable.indexOfFirst { local ->
                val sameKey = remote.sourceSyncKey.isNotBlank() && local.sourceSyncKey == remote.sourceSyncKey
                val nonDefaultFile = !local.fileName.startsWith("source.", true) &&
                    local.fileName.equals(remote.fileName, ignoreCase = true)
                val sameTitleFormat = local.title.equals(remote.title, ignoreCase = true) && local.format == remote.format
                sameKey || nonDefaultFile || sameTitleFormat
            }
            if (idx < 0) continue
            val local = mutable[idx]
            val nextProg = if (remote.progress.updatedAt > local.progress.updatedAt) remote.progress else local.progress
            val updated = local.copy(
                lastReadAt = maxOf(local.lastReadAt, remote.lastReadAt),
                progress = nextProg,
                bookmarks = (local.bookmarks + remote.bookmarks).distinctBy { it.id },
                highlights = (local.highlights + remote.highlights).distinctBy { it.id }
            )
            if (updated != local) {
                mutable[idx] = updated
                mergedCount++
            }
        }
        if (mergedCount > 0) mutable else null
    }
    return mergedCount
}

fun BookLibraryRepository.applyMoonReaderMetadata(
    bookId: String,
    meta: com.aitts.v2.reader.moon.MoonBookMeta?,
    position: com.aitts.v2.reader.moon.MoonReadingPosition?,
    annotations: List<com.aitts.v2.reader.moon.MoonAnnotation>,
    fallbackCoverBytes: ByteArray?
) {
    val existing = getBook(bookId) ?: return
    var nextCoverName = existing.coverFileName
    if (nextCoverName.isNullOrBlank() && fallbackCoverBytes != null && fallbackCoverBytes.size in 64..(6 * 1024 * 1024)) {
        val coverFile = File(bookDir(bookId), "cover_moon.png")
        runCatching { coverFile.writeBytes(fallbackCoverBytes) }.onSuccess { nextCoverName = coverFile.name }
    }
    val importedHighlights = com.aitts.v2.reader.moon.MoonReaderBackupParser.toBookHighlights(annotations)
    val importedBookmarks = com.aitts.v2.reader.moon.MoonReaderBackupParser.toBookBookmarks(annotations, position)
    mutateBook(bookId) { book ->
        val total = book.chapterCount.coerceAtLeast(1)
        val hasLocalZeroProgress = book.progress.chapterIndex == 0 && book.progress.totalProgression <= 0.001f
        val nextProgress = if (position != null && (position.timestampMs > book.progress.updatedAt || hasLocalZeroProgress)) {
            book.progress.copy(
                chapterIndex = position.chapterIndex.coerceIn(0, total - 1),
                charOffset = position.charOffset.coerceAtLeast(0),
                totalProgression = (position.percent / 100f).coerceIn(0f, 1f),
                totalChapters = total,
                updatedAt = maxOf(book.progress.updatedAt, position.timestampMs)
            )
        } else {
            book.progress
        }
        book.copy(
            title = meta?.bookName?.trim()?.takeIf { it.isNotBlank() } ?: book.title,
            author = book.author.ifBlank { meta?.cleanAuthor.orEmpty() },
            category = meta?.shelfGroup?.takeIf { it.isNotBlank() } ?: book.category,
            coverFileName = nextCoverName,
            addedAt = meta?.addedAtMs?.takeIf { it > 0L } ?: book.addedAt,
            lastReadAt = maxOf(book.lastReadAt, position?.timestampMs ?: 0L),
            progress = nextProgress,
            bookmarks = (book.bookmarks + importedBookmarks).distinctBy { it.id },
            highlights = (book.highlights + importedHighlights).distinctBy { it.id }
        )
    }
}
