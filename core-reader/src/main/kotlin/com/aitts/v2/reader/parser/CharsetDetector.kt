package com.aitts.v2.reader.parser

import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.SequenceInputStream
import java.nio.charset.Charset

data class DetectedCharset(
    val charset: Charset,
    val bomLength: Int
)

/**
 * Deterministic charset detector & binary guard for plain-text and XML/HTML e-books.
 */
object CharsetDetector {

    private const val SAMPLE_BYTES = 64 * 1024
    private val gb18030: Charset = runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }
    private val xmlEncodingRe = Regex("""<\?xml\b[^>]*\bencoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val htmlCharsetRe = Regex("""<meta\b[^>]*\bcharset\s*=\s*["']?\s*([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    fun detect(sample: ByteArray, isTruncatedSample: Boolean = false): DetectedCharset {
        detectBom(sample)?.let { return it }
        detectUtf16WithoutBom(sample)?.let { return DetectedCharset(it, bomLength = 0) }
        val stats = analyzeUtf8(sample, allowIncompleteTail = isTruncatedSample)
        val isUtf8 = stats.invalidCount == 0 ||
            (stats.validMultiByteCount >= 20 && stats.validMultiByteCount >= stats.invalidCount * 50)
        return if (isUtf8) {
            DetectedCharset(Charsets.UTF_8, bomLength = 0)
        } else {
            DetectedCharset(gb18030, bomLength = 0)
        }
    }

    fun detectMarkup(bytes: ByteArray, isTruncatedSample: Boolean = false): DetectedCharset {
        detectBom(bytes)?.let { return it }
        val head = String(bytes, 0, minOf(1024, bytes.size), Charsets.ISO_8859_1)
        val declared = xmlEncodingRe.find(head)?.groupValues?.getOrNull(1)
            ?: htmlCharsetRe.find(head)?.groupValues?.getOrNull(1)
        if (!declared.isNullOrBlank()) {
            val cleanDecl = declared.trim()
            val resolved = if (cleanDecl.equals("gb2312", true) || cleanDecl.equals("gbk", true)) {
                gb18030
            } else {
                runCatching { Charset.forName(cleanDecl) }.getOrNull()
            }
            if (resolved != null) {
                val stats = analyzeUtf8(bytes, allowIncompleteTail = isTruncatedSample)
                val finalCharset = if (resolved != Charsets.UTF_8 && stats.invalidCount == 0 && stats.validMultiByteCount >= 4) {
                    Charsets.UTF_8
                } else {
                    resolved
                }
                return DetectedCharset(finalCharset, bomLength = 0)
            }
        }
        return detect(bytes, isTruncatedSample)
    }

    fun looksLikeBinary(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        if (hasBinaryMagic(sample)) return true
        if (detectBom(sample) != null || detectUtf16WithoutBom(sample) != null) return false
        var controlCount = 0
        for (byteVal in sample) {
            val b = byteVal.toInt() and 0xFF
            val isControl = b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C
            if (isControl) controlCount++
        }
        return controlCount * 50 > sample.size
    }

    fun transcodeToUtf8(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = 200L * 1024 * 1024
    ): Long {
        val sample = readSample(input, SAMPLE_BYTES)
        val detected = detect(sample, isTruncatedSample = sample.size == SAMPLE_BYTES)
        val tailPrefix = if (detected.bomLength < sample.size) {
            ByteArrayInputStream(sample, detected.bomLength, sample.size - detected.bomLength)
        } else {
            ByteArrayInputStream(ByteArray(0))
        }
        val fullStream = SequenceInputStream(tailPrefix, input)

        if (detected.charset == Charsets.UTF_8) {
            return copyBounded(fullStream, output, maxBytes)
        }

        val countingOut = BoundedCountingOutputStream(output, maxBytes)
        InputStreamReader(fullStream, detected.charset).use { reader ->
            val writer = OutputStreamWriter(countingOut, Charsets.UTF_8)
            val charBuf = CharArray(8192)
            while (true) {
                val count = reader.read(charBuf)
                if (count <= 0) break
                writer.write(charBuf, 0, count)
            }
            writer.flush()
        }
        return countingOut.bytesWritten
    }

    private class BoundedCountingOutputStream(
        delegate: OutputStream,
        private val maxBytes: Long
    ) : FilterOutputStream(delegate) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            if (++bytesWritten > maxBytes) {
                throw IOException("文本超出最大限制 (${maxBytes / (1024 * 1024)} MB)")
            }
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytesWritten += len
            if (bytesWritten > maxBytes) {
                throw IOException("文本超出最大限制 (${maxBytes / (1024 * 1024)} MB)")
            }
            out.write(b, off, len)
        }
    }

    internal fun isValidUtf8(bytes: ByteArray, allowIncompleteTail: Boolean = false): Boolean =
        analyzeUtf8(bytes, allowIncompleteTail).invalidCount == 0

    private fun detectBom(sample: ByteArray): DetectedCharset? = when {
        hasUtf8Bom(sample) -> DetectedCharset(Charsets.UTF_8, bomLength = 3)
        sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() ->
            DetectedCharset(Charsets.UTF_16BE, bomLength = 2)
        sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() ->
            DetectedCharset(Charsets.UTF_16LE, bomLength = 2)
        else -> null
    }

    private fun hasUtf8Bom(sample: ByteArray): Boolean = sample.size >= 3 &&
        sample[0] == 0xEF.toByte() &&
        sample[1] == 0xBB.toByte() &&
        sample[2] == 0xBF.toByte()

    private fun detectUtf16WithoutBom(sample: ByteArray): Charset? {
        if (sample.size < 16) return null
        var evenNulls = 0
        var oddNulls = 0
        val pairs = minOf(sample.size / 2, 1024)
        for (p in 0 until pairs) {
            if (sample[p * 2] == 0.toByte()) evenNulls++
            if (sample[p * 2 + 1] == 0.toByte()) oddNulls++
        }
        return when {
            oddNulls * 4 >= pairs && evenNulls * 20 < pairs -> Charsets.UTF_16LE
            evenNulls * 4 >= pairs && oddNulls * 20 < pairs -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun hasBinaryMagic(sample: ByteArray): Boolean {
        if (matchesBytes(sample, '%'.code, 'P'.code, 'D'.code, 'F'.code)) return true
        if (matchesBytes(sample, 0x50, 0x4B, 0x03, 0x04)) return true
        if (matchesBytes(sample, 'R'.code, 'a'.code, 'r'.code, '!'.code)) return true
        if (sample.size >= 68) {
            val mobiMagic = String(sample, 60, 8, Charsets.ISO_8859_1)
            if (mobiMagic == "BOOKMOBI" || mobiMagic == "TEXtREAd") return true
        }
        return false
    }

    private fun matchesBytes(sample: ByteArray, vararg expected: Int): Boolean {
        if (sample.size < expected.size) return false
        for (i in expected.indices) {
            if ((sample[i].toInt() and 0xFF) != expected[i]) return false
        }
        return true
    }

    private data class Utf8Stats(val validMultiByteCount: Int, val invalidCount: Int)

    private fun analyzeUtf8(bytes: ByteArray, allowIncompleteTail: Boolean): Utf8Stats {
        var i = 0
        var multiByte = 0
        var invalid = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val expectedLen = utf8SequenceLength(b0)
            if (expectedLen == 0) {
                invalid++
                i++
                continue
            }
            if (i + expectedLen > bytes.size) {
                val tailValid = allowIncompleteTail && validContinuationRange(bytes, i + 1, bytes.size)
                if (!tailValid) invalid++
                break
            }
            if (validContinuationRange(bytes, i + 1, i + expectedLen)) {
                multiByte++
                i += expectedLen
            } else {
                invalid++
                i++
            }
        }
        return Utf8Stats(multiByte, invalid)
    }

    private fun utf8SequenceLength(lead: Int): Int = when {
        lead in 0xC2..0xDF -> 2
        lead in 0xE0..0xEF -> 3
        lead in 0xF0..0xF4 -> 4
        else -> 0
    }

    private fun validContinuationRange(bytes: ByteArray, start: Int, endExclusive: Int): Boolean {
        for (j in start until endExclusive) {
            if ((bytes[j].toInt() and 0xC0) != 0x80) return false
        }
        return true
    }

    private fun readSample(input: InputStream, limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = input.read(buf, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return if (total == buf.size) buf else buf.copyOf(total)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("文件超出最大限制 (${maxBytes / (1024 * 1024)} MB)")
            }
            output.write(buf, 0, read)
        }
        output.flush()
        return total
    }
}
