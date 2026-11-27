package com.aitts.v2.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.parser.BookFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import java.io.File
import java.util.zip.ZipFile

data class BookImageItem(
    val resourcePath: String,
    val fileName: String,
    val mimeType: String
)

data class LoadedBookImage(
    val item: BookImageItem,
    val bitmap: Bitmap,
    val rawBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Lossless illustration discovery, gallery saving (MediaStore), clipboard URI copy,
 * share Intent creation, and batch export for EPUB and HTML ebooks.
 */
class BookImageExportHelper(
    context: Context,
    private val library: BookLibraryRepository,
    private val readiumEngine: ReadiumEngine
) {
    private val appContext = context.applicationContext

    fun listBookImages(record: BookRecord, publication: Publication?): List<BookImageItem> {
        if (publication != null) {
            val combined = publication.resources + publication.readingOrder
            return combined.mapNotNull { link ->
                val rawHref = link.href.toString().substringBefore('#').substringBefore('?')
                val mime = link.mediaType?.toString().orEmpty().ifBlank { mimeFromPath(rawHref) }
                if (!isImageEntry(rawHref, mime)) return@mapNotNull null
                BookImageItem(
                    resourcePath = rawHref,
                    fileName = sanitizeFileName(rawHref.substringAfterLast('/'), mime),
                    mimeType = if (mime.startsWith("image/")) mime else mimeFromPath(rawHref)
                )
            }.distinctBy { it.resourcePath.trimStart('/') }
        }
        if (record.format != BookFormat.EPUB) return emptyList()
        val file = library.bookFile(record)
        if (!file.exists()) return emptyList()
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isImageEntry(it.name, "") }
                    .map { entry ->
                        val mime = mimeFromPath(entry.name)
                        BookImageItem(
                            resourcePath = entry.name,
                            fileName = sanitizeFileName(entry.name.substringAfterLast('/'), mime),
                            mimeType = mime
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun loadFullImage(
        record: BookRecord,
        publication: Publication?,
        resourcePath: String,
        maxDisplayDim: Int = 2048
    ): LoadedBookImage? = withContext(Dispatchers.IO) {
        val raw = readRawImageBytes(record, publication, resourcePath) ?: return@withContext null
        val (bytes, mime) = raw
        val (bmp, bounds) = decodeBoundedBitmap(bytes, maxDisplayDim) ?: return@withContext null
        val item = BookImageItem(
            resourcePath = resourcePath,
            fileName = sanitizeFileName(resourcePath.substringAfterLast('/'), mime),
            mimeType = mime
        )
        LoadedBookImage(item, bmp, bytes, bounds.first, bounds.second)
    }

    suspend fun loadThumbnail(
        record: BookRecord,
        publication: Publication?,
        resourcePath: String,
        maxThumbDim: Int = 360
    ): Bitmap? = withContext(Dispatchers.IO) {
        val raw = readRawImageBytes(record, publication, resourcePath) ?: return@withContext null
        decodeBoundedBitmap(raw.first, maxThumbDim)?.first
    }

    private fun decodeBoundedBitmap(bytes: ByteArray, maxDim: Int): Pair<Bitmap, Pair<Int, Int>>? {
        if (bytes.size > MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return bmp to (bounds.outWidth to bounds.outHeight)
    }

    suspend fun readRawImageBytes(
        record: BookRecord,
        publication: Publication?,
        resourcePath: String
    ): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val bytes = (if (publication != null) readiumEngine.readResourceBytes(publication, resourcePath) else null)
            ?: BookFormatRegistry.parserFor(record.format).readResource(library.bookFile(record), resourcePath)
            ?: return@withContext null
        if (bytes.size > MAX_IMAGE_BYTES) return@withContext null
        bytes to detectMimeType(bytes, resourcePath)
    }

    suspend fun saveImageToGallery(
        bookTitle: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val albumFolder = sanitizeSegment(bookTitle.ifBlank { "Ebook" })
            val safeName = sanitizeFileName(fileName, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relPath = "${Environment.DIRECTORY_PICTURES}/AI-TTS/$albumFolder"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建相册图片条目")
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
                "$relPath/$safeName"
            } else {
                val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(appContext.filesDir, "Pictures")
                val targetDir = File(baseDir, "AI-TTS/$albumFolder").apply { mkdirs() }
                val dest = File(targetDir, safeName)
                dest.writeBytes(bytes)
                MediaScannerConnection.scanFile(appContext, arrayOf(dest.absolutePath), arrayOf(mimeType), null)
                dest.absolutePath
            }
        }
    }

    fun copyImageToClipboard(fileName: String, bytes: ByteArray, mimeType: String): Result<Unit> = runCatching {
        val tempFile = stageSharedImage(fileName, bytes, mimeType)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", tempFile)
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(appContext.contentResolver, tempFile.name, uri))
    }

    fun buildShareImageIntent(fileName: String, bytes: ByteArray, mimeType: String): Result<Intent> = runCatching {
        val tempFile = stageSharedImage(fileName, bytes, mimeType)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", tempFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "分享电子书插图")
    }

    suspend fun exportAllImages(
        record: BookRecord,
        publication: Publication?,
        onProgress: (Int, Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val list = listBookImages(record, publication)
            var saved = 0
            list.forEachIndexed { idx, item ->
                onProgress(idx + 1, list.size)
                val raw = readRawImageBytes(record, publication, item.resourcePath) ?: return@forEachIndexed
                val numbered = "${(idx + 1).toString().padStart(3, '0')}_${item.fileName}"
                if (saveImageToGallery(record.title, numbered, raw.first, raw.second).isSuccess) {
                    saved++
                }
            }
            saved
        }
    }

    private fun stageSharedImage(fileName: String, bytes: ByteArray, mimeType: String): File {
        val dir = File(appContext.cacheDir, "shared_images").apply { mkdirs() }
        val stale = dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(16).orEmpty()
        stale.forEach { it.delete() }
        val safeName = sanitizeFileName(fileName, mimeType)
        return File(dir, "${System.currentTimeMillis()}_$safeName").apply { writeBytes(bytes) }
    }

    private fun isImageEntry(path: String, mime: String): Boolean {
        if (mime.startsWith("image/") && !mime.contains("svg")) return true
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    private fun detectMimeType(bytes: ByteArray, path: String): String {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()) return "image/gif"
        if (bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) return "image/webp"
        return mimeFromPath(path)
    }

    private fun mimeFromPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    private fun sanitizeFileName(raw: String, mimeType: String): String {
        val base = raw.substringBefore('?').substringBefore('#').trim().ifBlank { "illustration" }
            .replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        if (base.contains('.')) return base.take(80)
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        return "${base.take(72)}.$ext"
    }

    private fun sanitizeSegment(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(40).ifBlank { "Book" }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    }
}
