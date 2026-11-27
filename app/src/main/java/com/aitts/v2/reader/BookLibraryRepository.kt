package com.aitts.v2.reader

import android.content.Context
import com.aitts.v2.reader.model.BookBookmark
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ReaderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persists library metadata, reading positions, bookmarks, highlights, and settings atomically.
 */
class BookLibraryRepository(context: Context) {

    internal val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeLock = Any()

    internal val rootDir = File(appContext.filesDir, "books").apply { mkdirs() }
    private val libraryFile = File(rootDir, "library.json")
    private val settingsFile = File(rootDir, "reader_settings.json")

    private val _booksFlow = MutableStateFlow(loadBooksFromDisk())
    val booksFlow: StateFlow<List<BookRecord>> = _booksFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(loadSettingsFromDisk())
    val settingsFlow: StateFlow<ReaderSettings> = _settingsFlow.asStateFlow()

    fun bookDir(bookId: String): File {
        val safeId = bookId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(rootDir, safeId).apply { mkdirs() }
    }
    fun bookFile(record: BookRecord): File = File(bookDir(record.id), record.fileName)
    fun coverFile(record: BookRecord): File? =
        record.coverFileName?.takeIf { it.isNotBlank() }?.let { File(bookDir(record.id), it) }
    fun indexFile(bookId: String): File = File(bookDir(bookId), "index.json")
    fun getBook(bookId: String): BookRecord? = _booksFlow.value.firstOrNull { it.id == bookId }

    fun loadBookIndex(bookId: String): BookIndex? {
        val file = indexFile(bookId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<BookIndex>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun saveBookIndex(bookId: String, index: BookIndex) {
        synchronized(writeLock) { writeAtomic(indexFile(bookId), json.encodeToString(index)) }
    }

    fun upsertBook(record: BookRecord) {
        synchronized(writeLock) {
            val updated = _booksFlow.value.filterNot { it.id == record.id } + record
            commitBooks(updated)
        }
    }

    fun updateProgress(bookId: String, chapterIndex: Int, blockIndex: Int, totalChapters: Int, charOffset: Int = 0) {
        mutateBook(bookId) { existing ->
            val now = System.currentTimeMillis()
            existing.copy(
                lastReadAt = now,
                chapterCount = maxOf(1, totalChapters),
                progress = existing.progress.copy(
                    chapterIndex = chapterIndex.coerceAtLeast(0),
                    blockIndex = blockIndex.coerceAtLeast(0),
                    charOffset = charOffset.coerceAtLeast(0),
                    totalChapters = maxOf(1, totalChapters),
                    updatedAt = now
                )
            )
        }
    }

    fun updateReadiumProgress(bookId: String, chapterIndex: Int, locatorJson: String, totalProgression: Float, totalChapters: Int) {
        mutateBook(bookId) { existing ->
            val now = System.currentTimeMillis()
            existing.copy(
                lastReadAt = now,
                chapterCount = maxOf(1, totalChapters),
                progress = existing.progress.copy(
                    chapterIndex = chapterIndex.coerceAtLeast(0),
                    locatorJson = locatorJson,
                    totalProgression = totalProgression.coerceIn(0f, 1f),
                    totalChapters = maxOf(1, totalChapters),
                    updatedAt = now
                )
            )
        }
    }

    fun toggleBookmark(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        chapterTitle: String,
        snippet: String,
        locatorJson: String = ""
    ): Boolean {
        var added = false
        mutateBook(bookId) { book ->
            val existing = book.bookmarks.firstOrNull {
                if (locatorJson.isNotBlank() && it.locatorJson.isNotBlank()) {
                    it.chapterIndex == chapterIndex && it.locatorJson == locatorJson
                } else {
                    it.chapterIndex == chapterIndex && it.blockIndex == blockIndex
                }
            }
            val next = if (existing != null) {
                added = false
                book.bookmarks.filterNot { it.id == existing.id }
            } else {
                added = true
                val mark = BookBookmark(
                    id = UUID.randomUUID().toString().take(8),
                    chapterIndex = chapterIndex,
                    blockIndex = blockIndex,
                    chapterTitle = chapterTitle,
                    snippet = snippet.trim().take(72).ifBlank { chapterTitle },
                    createdAt = System.currentTimeMillis(),
                    locatorJson = locatorJson
                )
                listOf(mark) + book.bookmarks
            }
            book.copy(bookmarks = next)
        }
        return added
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        mutateBook(bookId) { book -> book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId }) }
    }

    fun deleteBooks(bookIds: Set<String>) {
        if (bookIds.isEmpty()) return
        synchronized(writeLock) {
            commitBooks(_booksFlow.value.filterNot { it.id in bookIds })
            bookIds.forEach { bookDir(it).deleteRecursively() }
        }
    }

    fun updateSettings(settings: ReaderSettings) {
        synchronized(writeLock) {
            _settingsFlow.value = settings
            writeAtomic(settingsFile, json.encodeToString(settings))
        }
    }

    internal fun mutateBook(bookId: String, transform: (BookRecord) -> BookRecord) {
        synchronized(writeLock) {
            val current = _booksFlow.value
            val idx = current.indexOfFirst { it.id == bookId }
            if (idx < 0) return
            val mutable = current.toMutableList()
            mutable[idx] = transform(current[idx])
            commitBooks(mutable)
        }
    }

    internal fun updateAllBooks(transform: (List<BookRecord>) -> List<BookRecord>?) {
        synchronized(writeLock) {
            transform(_booksFlow.value)?.let { commitBooks(it) }
        }
    }

    private fun commitBooks(books: List<BookRecord>) {
        val sorted = books.sortedWith(
            compareByDescending<BookRecord> { it.isPinned }.thenByDescending { maxOf(it.lastReadAt, it.addedAt) }
        )
        _booksFlow.value = sorted
        writeAtomic(libraryFile, json.encodeToString(sorted))
    }

    private fun loadBooksFromDisk(): List<BookRecord> {
        if (!libraryFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<BookRecord>>(libraryFile.readText(Charsets.UTF_8)).sortedWith(
                compareByDescending<BookRecord> { it.isPinned }.thenByDescending { maxOf(it.lastReadAt, it.addedAt) }
            )
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
