package com.aitts.v2.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.aitts.v2.reader.model.BookRecord
import com.aitts.v2.reader.parser.BookFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory-bounded, downsampled bitmap loader for EPUB inline illustrations and cover art.
 */
class EpubImageLoader(
    private val library: BookLibraryRepository
) {
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    suspend fun loadCover(record: BookRecord, maxWidthPx: Int = 360): Bitmap? {
        val coverFile = library.coverFile(record) ?: return null
        if (!coverFile.exists()) return null
        val key = "${record.id}|__cover__|${coverFile.lastModified()}"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = coverFile.readBytes()
                decodeSampled(bytes, maxWidthPx)?.also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    suspend fun loadBookResource(
        record: BookRecord,
        resourcePath: String,
        maxWidthPx: Int = 1080
    ): Bitmap? {
        if (resourcePath.isBlank()) return null
        val key = "${record.id}|$resourcePath"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val file = library.bookFile(record)
                val parser = BookFormatRegistry.parserFor(record.format)
                val bytes = parser.readResource(file, resourcePath) ?: return@withContext null
                decodeSampled(bytes, maxWidthPx)?.also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxWidthPx: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val rawWidth = boundsOpts.outWidth
        val rawHeight = boundsOpts.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while (rawWidth / sampleSize > maxWidthPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    companion object {
        private const val MAX_CACHE_KB = 12 * 1024
    }
}
