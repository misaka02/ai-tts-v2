package com.aitts.v2.reader.parser

import java.io.ByteArrayInputStream
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
 * Lightweight deterministic charset detector for plain-text e-books (UTF-8 / UTF-16 / GB18030).
 */
object CharsetDetector {

    private const val SAMPLE_BYTES = 64 * 1024
    private val gb18030: Charset = runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }

    fun detect(sample: ByteArray): DetectedCharset {
        if (hasUtf8Bom(sample)) return DetectedCharset(Charsets.UTF_8, bomLength = 3)
        if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
            return DetectedCharset(Charsets.UTF_16BE, bomLength = 2)
        }
        if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
            return DetectedCharset(Charsets.UTF_16LE, bomLength = 2)
        }
        val allowPartialTail = sample.size >= SAMPLE_BYTES
        return if (isValidUtf8(sample, allowPartialTail)) {
            DetectedCharset(Charsets.UTF_8, bomLength = 0)
        } else {
            DetectedCharset(gb18030, bomLength = 0)
        }
    }

    private fun hasUtf8Bom(sample: ByteArray): Boolean = sample.size >= 3 &&
        sample[0] == 0xEF.toByte() &&
        sample[1] == 0xBB.toByte() &&
        sample[2] == 0xBF.toByte()

    fun transcodeToUtf8(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = 200L * 1024 * 1024
    ): Long {
        val sample = readSample(input, SAMPLE_BYTES)
        val detected = detect(sample)
        val tailPrefix = if (detected.bomLength < sample.size) {
            ByteArrayInputStream(sample, detected.bomLength, sample.size - detected.bomLength)
        } else {
            ByteArrayInputStream(ByteArray(0))
        }
        val fullStream = SequenceInputStream(tailPrefix, input)

        if (detected.charset == Charsets.UTF_8) {
            return copyBounded(fullStream, output, maxBytes)
        }

        var writtenBytes = 0L
        InputStreamReader(fullStream, detected.charset).use { reader ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                val charBuf = CharArray(8192)
                while (true) {
                    val count = reader.read(charBuf)
                    if (count <= 0) break
                    val encoded = String(charBuf, 0, count).toByteArray(Charsets.UTF_8)
                    writtenBytes += encoded.size
                    if (writtenBytes > maxBytes) {
                        throw IOException("文本超出最大限制 (${maxBytes / (1024 * 1024)} MB)")
                    }
                    output.write(encoded)
                }
                writer.flush()
            }
        }
        return writtenBytes
    }

    internal fun isValidUtf8(bytes: ByteArray, allowIncompleteTail: Boolean = false): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val expectedLen = utf8SequenceLength(b0)
            if (expectedLen == 0) return false
            if (i + expectedLen > bytes.size) {
                return allowIncompleteTail && validContinuationRange(bytes, i + 1, bytes.size)
            }
            if (!validContinuationRange(bytes, i + 1, i + expectedLen)) return false
            i += expectedLen
        }
        return true
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
