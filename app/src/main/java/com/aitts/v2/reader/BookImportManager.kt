package com.aitts.v2.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.model.ReadingProgress
import com.aitts.v2.reader.parser.BookFormatParser
import com.aitts.v2.reader.parser.BookFormatRegistry
import com.aitts.v2.reader.parser.CharsetDetector
import com.aitts.v2.reader.parser.EpubBookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.UUID

/**
 * Imports EPUB / TXT / Markdown / HTML e-books safely from SAF, ACTION_VIEW, or cloud streams
 * into app-private storage, validating binary headers and normalizing text encoding to UTF-8.
 */
class BookImportManager(
    context: Context,
    private val library: BookLibraryRepository
) {
    private val appContext = context.applicationContext

    suspend fun importFromUri(uri: Uri, sourceSyncKey: String = ""): Result<BookRecord> = withContext(Dispatchers.IO) {
        try {
            val displayName = resolveDisplayName(uri)
            val rawStream = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件，请确认文件权限有效")
            importFromStream(displayName, rawStream, sourceSyncKey)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importFromStream(
        displayName: String,
        rawStream: InputStream,
        sourceSyncKey: String = ""
    ): Result<BookRecord> = withContext(Dispatchers.IO) {
        if (sourceSyncKey.isNotBlank()) {
            library.booksFlow.value.firstOrNull { it.sourceSyncKey == sourceSyncKey }?.let { existing ->
                return@withContext Result.success(existing)
            }
        }
        val bookId = "book_${UUID.randomUUID().toString().take(8)}"
        val bookDir = library.bookDir(bookId).apply { mkdirs() }
        try {
            val cleanName = displayName.trim().ifBlank { "imported_book.txt" }
            val ext = cleanName.substringAfterLast('.', "").lowercase()
            val fallbackTitle = cleanName.substringBeforeLast('.').ifBlank { cleanName }.ifBlank { "未命名书籍" }

            val sourceFile = writeNormalizedSource(rawStream, bookDir, ext, cleanName)
            val parser = BookFormatRegistry.resolve(sourceFile, cleanName)
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
                sourceSyncKey = sourceSyncKey,
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
        rawStream: InputStream,
        bookDir: File,
        ext: String,
        displayName: String
    ): File {
        PushbackInputStream(BufferedInputStream(rawStream, 16 * 1024), PEEK_BYTES).use { pb ->
            val header = ByteArray(PEEK_BYTES)
            val read = readUpTo(pb, header)
            if (read > 0) pb.unread(header, 0, read)
            val peekSlice = if (read == header.size) header else header.copyOf(read.coerceAtLeast(0))
            val isZipMagic = peekSlice.size >= 4 &&
                peekSlice[0] == 0x50.toByte() &&
                peekSlice[1] == 0x4B.toByte() &&
                peekSlice[2] == 0x03.toByte() &&
                peekSlice[3] == 0x04.toByte()

            val targetExt = when {
                isZipMagic || ext == "epub" -> "epub"
                ext in BookFormat.MARKDOWN.extensions -> "md"
                ext in BookFormat.HTML.extensions -> "html"
                else -> "txt"
            }

            if (targetExt != "epub" && CharsetDetector.looksLikeBinary(peekSlice)) {
                throw IOException("暂不支持此二进制文件格式（当前仅支持 EPUB / TXT / Markdown / HTML）")
            }

            val destFile = File(bookDir, "source.$targetExt")
            destFile.outputStream().use { out ->
                if (targetExt == "epub") {
                    copyBounded(pb, out, MAX_BOOK_BYTES, displayName)
                } else {
                    CharsetDetector.transcodeToUtf8(pb, out, MAX_BOOK_BYTES)
                }
            }
            if (targetExt == "epub" && !EpubBookParser().canParse(destFile, displayName)) {
                throw IOException("文件不是有效的 EPUB 电子书（缺少 META-INF/container.xml）")
            }
            return destFile
        }
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count <= 0) break
            total += count
        }
        return total
    }

    private fun copyBounded(
        input: InputStream,
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
        parser: BookFormatParser,
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
        private const val PEEK_BYTES = 4096
        private const val MAX_BOOK_BYTES = 200L * 1024L * 1024L
        private const val MAX_COVER_BYTES = 6 * 1024 * 1024
    }
}
