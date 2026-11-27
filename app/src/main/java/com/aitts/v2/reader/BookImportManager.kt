package com.aitts.v2.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ReadingProgress
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.reader.parser.CharsetDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.PushbackInputStream
import java.util.UUID

/**
 * Imports EPUB / TXT / Markdown / HTML e-books safely from SAF or external ACTION_VIEW URIs
 * into app-private storage, performing UTF-8 normalization, chapter indexing, and cover extraction.
 */
class BookImportManager(
    context: Context,
    private val library: BookLibraryRepository
) {
    private val appContext = context.applicationContext

    suspend fun importFromUri(uri: Uri): Result<BookRecord> = withContext(Dispatchers.IO) {
        val bookId = "book_${UUID.randomUUID().toString().take(8)}"
        val bookDir = library.bookDir(bookId).apply { mkdirs() }
        try {
            val displayName = resolveDisplayName(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { displayName }.ifBlank { "未命名书籍" }

            val rawStream = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件，请确认文件权限有效")

            val sourceFile = writeNormalizedSource(rawStream, bookDir, ext, displayName)
            val parser = BookFormatRegistry.resolve(sourceFile, displayName)
            val index = parser.indexBook(sourceFile, fallbackTitle)
            library.saveBookIndex(bookId, index)

            val coverFileName = index.coverResourcePath?.let { coverPath ->
                saveCoverIfPresent(parser, sourceFile, coverPath, bookDir)
            }

            val now = System.currentTimeMillis()
            val record = BookRecord(
                id = bookId,
                title = index.title.ifBlank { fallbackTitle },
                author = index.author,
                format = parser.format,
                fileName = sourceFile.name,
                coverFileName = coverFileName,
                fileSizeBytes = sourceFile.length(),
                chapterCount = index.chapters.size.coerceAtLeast(1),
                addedAt = now,
                lastReadAt = now,
                progress = ReadingProgress(
                    chapterIndex = 0,
                    blockIndex = 0,
                    totalChapters = index.chapters.size.coerceAtLeast(1),
                    updatedAt = now
                )
            )
            library.upsertBook(record)
            Result.success(record)
        } catch (t: Throwable) {
            bookDir.deleteRecursively()
            Result.failure(t)
        }
    }

    private fun writeNormalizedSource(
        rawStream: java.io.InputStream,
        bookDir: File,
        ext: String,
        displayName: String
    ): File {
        PushbackInputStream(BufferedInputStream(rawStream, 16 * 1024), 4).use { pb ->
            val header = ByteArray(4)
            val read = pb.read(header)
            if (read > 0) pb.unread(header, 0, read)
            val isZipMagic = read == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()

            val targetExt = when {
                isZipMagic || ext == "epub" -> "epub"
                ext in BookFormat.MARKDOWN.extensions -> "md"
                ext in BookFormat.HTML.extensions -> "html"
                else -> "txt"
            }
            val destFile = File(bookDir, "source.$targetExt")
            destFile.outputStream().use { out ->
                if (targetExt == "epub") {
                    copyBounded(pb, out, MAX_BOOK_BYTES, displayName)
                } else {
                    CharsetDetector.transcodeToUtf8(pb, out, MAX_BOOK_BYTES)
                }
            }
            return destFile
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        displayName: String
    ) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buf)
            if (count <= 0) break
            total += count
            if (total > maxBytes) {
                throw IOException("$displayName 超出 ${maxBytes / (1024 * 1024)} MB 大小上限")
            }
            output.write(buf, 0, count)
        }
        output.flush()
    }

    private fun saveCoverIfPresent(
        parser: com.aitts.v2.reader.parser.BookFormatParser,
        sourceFile: File,
        coverPath: String,
        bookDir: File
    ): String? {
        val bytes = parser.readResource(sourceFile, coverPath) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        val file = File(bookDir, "cover.img")
        file.writeBytes(bytes)
        return file.name
    }

    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        if (!queried.isNullOrBlank()) return queried
        val segment = uri.lastPathSegment?.substringAfterLast('/')
        if (!segment.isNullOrBlank()) return segment
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("epub") -> "epub"
            mime.contains("markdown") -> "md"
            mime.contains("html") -> "html"
            else -> "txt"
        }
        return "imported_book.$ext"
    }

    companion object {
        private const val MAX_BOOK_BYTES = 200L * 1024L * 1024L
        private const val MAX_COVER_BYTES = 6 * 1024 * 1024
    }
}
