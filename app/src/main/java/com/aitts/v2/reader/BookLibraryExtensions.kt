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
