package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Atomic file-backed repository managing imported books, chapter indexes,
 * reading progress, bookmarks, and reader typography/TTS preferences.
 */
class BookLibraryRepository(context: Context) {

    private val booksRootDir = File(context.applicationContext.filesDir, "books").apply { mkdirs() }
    private val libraryFile = File(booksRootDir, "library.json")
    private val settingsFile = File(booksRootDir, "reader_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeLock = Any()

    private val _booksFlow = MutableStateFlow(loadBooksFromDisk())
    val booksFlow: StateFlow<List<BookRecord>> = _booksFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(loadSettingsFromDisk())
    val settingsFlow: StateFlow<ReaderSettings> = _settingsFlow.asStateFlow()

    fun bookDir(bookId: String): File {
        val safeId = bookId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(booksRootDir, safeId)
    }

    fun bookFile(record: BookRecord): File = File(bookDir(record.id), record.fileName)

    fun coverFile(record: BookRecord): File? =
        record.coverFileName?.takeIf { it.isNotBlank() }?.let { File(bookDir(record.id), it) }

    fun getBook(bookId: String): BookRecord? = _booksFlow.value.firstOrNull { it.id == bookId }

    fun saveBookIndex(bookId: String, index: BookIndex) {
        val dir = bookDir(bookId).apply { mkdirs() }
        writeAtomic(File(dir, "index.json"), json.encodeToString(index))
    }

    fun loadBookIndex(bookId: String): BookIndex? {
        val indexFile = File(bookDir(bookId), "index.json")
        if (!indexFile.exists()) return null
        return runCatching { json.decodeFromString<BookIndex>(indexFile.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun upsertBook(record: BookRecord) {
        synchronized(writeLock) {
            val updated = _booksFlow.value.filterNot { it.id == record.id } + record
            commitBooks(updated)
        }
    }

    fun updateProgress(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        totalChapters: Int,
        charOffset: Int = 0
    ) {
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val current = _booksFlow.value
            val idx = current.indexOfFirst { it.id == bookId }
            if (idx < 0) return
            val existing = current[idx]
            val next = existing.copy(
                lastReadAt = now,
                chapterCount = maxOf(1, totalChapters),
                progress = ReadingProgress(
                    chapterIndex = chapterIndex.coerceAtLeast(0),
                    blockIndex = blockIndex.coerceAtLeast(0),
                    charOffset = charOffset.coerceAtLeast(0),
                    totalChapters = maxOf(1, totalChapters),
                    updatedAt = now
                )
            )
            val mutable = current.toMutableList()
            mutable[idx] = next
            commitBooks(mutable)
        }
    }

    fun toggleBookmark(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        chapterTitle: String,
        snippet: String
    ): Boolean {
        var added = false
        synchronized(writeLock) {
            val current = _booksFlow.value
            val idx = current.indexOfFirst { it.id == bookId }
            if (idx < 0) return false
            val book = current[idx]
            val existing = book.bookmarks.firstOrNull {
                it.chapterIndex == chapterIndex && it.blockIndex == blockIndex
            }
            val nextBookmarks = if (existing != null) {
                added = false
                book.bookmarks.filterNot { it.id == existing.id }
            } else {
                added = true
                val cleanSnippet = snippet.trim().take(72).ifBlank { chapterTitle }
                val mark = BookBookmark(
                    id = UUID.randomUUID().toString().take(8),
                    chapterIndex = chapterIndex,
                    blockIndex = blockIndex,
                    chapterTitle = chapterTitle,
                    snippet = cleanSnippet,
                    createdAt = System.currentTimeMillis()
                )
                listOf(mark) + book.bookmarks
            }
            val mutable = current.toMutableList()
            mutable[idx] = book.copy(bookmarks = nextBookmarks)
            commitBooks(mutable)
        }
        return added
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        synchronized(writeLock) {
            val current = _booksFlow.value
            val idx = current.indexOfFirst { it.id == bookId }
            if (idx < 0) return
            val book = current[idx]
            val mutable = current.toMutableList()
            mutable[idx] = book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId })
            commitBooks(mutable)
        }
    }

    fun deleteBook(bookId: String) {
        synchronized(writeLock) {
            commitBooks(_booksFlow.value.filterNot { it.id == bookId })
            bookDir(bookId).deleteRecursively()
        }
    }

    fun updateSettings(settings: ReaderSettings) {
        synchronized(writeLock) {
            _settingsFlow.value = settings
            writeAtomic(settingsFile, json.encodeToString(settings))
        }
    }

    private fun commitBooks(books: List<BookRecord>) {
        val sorted = books.sortedByDescending { maxOf(it.lastReadAt, it.addedAt) }
        _booksFlow.value = sorted
        writeAtomic(libraryFile, json.encodeToString(sorted))
    }

    private fun loadBooksFromDisk(): List<BookRecord> {
        if (!libraryFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<BookRecord>>(libraryFile.readText(Charsets.UTF_8))
                .sortedByDescending { maxOf(it.lastReadAt, it.addedAt) }
        }.getOrDefault(emptyList())
    }

    private fun loadSettingsFromDisk(): ReaderSettings {
        if (!settingsFile.exists()) return ReaderSettings()
        return runCatching {
            json.decodeFromString<ReaderSettings>(settingsFile.readText(Charsets.UTF_8))
        }.getOrDefault(ReaderSettings())
    }

    private fun writeAtomic(target: File, content: String) {
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
