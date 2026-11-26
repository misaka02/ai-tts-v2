package com.aitts.v2.engine.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedPcm(
    val pcm16Data: ByteArray,
    val sampleRate: Int,
    val channelCount: Int
)

private data class WavHeaderInfo(
    val formatCode: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataSize: Int
)

/**
 * Single source of truth for WAV/RIFF parsing and encoding (supports PCM16 and IEEE Float32 WAV).
 */
object WavCodec {

    fun parseWav(data: ByteArray): DecodedPcm? {
        if (AudioFormatDetector.detect(data) != DetectedAudioFormat.WAV) return null
        return runCatching {
            val info = scanChunks(data) ?: return null
            val pcm16 = extractPcm16(data, info) ?: return null
            DecodedPcm(pcm16Data = pcm16, sampleRate = info.sampleRate, channelCount = info.channels)
        }.getOrNull()
    }

    private fun scanChunks(data: ByteArray): WavHeaderInfo? {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var formatCode = 1
        var channels = 1
        var sampleRate = 24000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= data.size) {
            val chunkId = String(data, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            val bodyStart = offset + 8
            if (chunkSize < 0 || bodyStart > data.size) break

            if (chunkId == "fmt ") {
                if (bodyStart + 16 > data.size) return null
                formatCode = buffer.getShort(bodyStart).toInt() and 0xFFFF
                channels = (buffer.getShort(bodyStart + 2).toInt() and 0xFFFF).coerceAtLeast(1)
                sampleRate = buffer.getInt(bodyStart + 4).coerceAtLeast(8000)
                bitsPerSample = buffer.getShort(bodyStart + 14).toInt() and 0xFFFF
                if (formatCode != 1 && formatCode != 3) return null
            } else if (chunkId == "data") {
                dataOffset = bodyStart
                dataSize = if (chunkSize > 0 && bodyStart + chunkSize <= data.size) chunkSize else data.size - bodyStart
                break
            }
            val padded = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
            offset = bodyStart + padded
        }
        return if (dataOffset >= 0 && dataSize > 0) {
            WavHeaderInfo(formatCode, channels, sampleRate, bitsPerSample, dataOffset, dataSize)
        } else null
    }

    private fun extractPcm16(data: ByteArray, info: WavHeaderInfo): ByteArray? = when {
        info.formatCode == 1 && info.bitsPerSample == 16 -> {
            val aligned = info.dataSize and 0x7FFFFFFE
            ByteArray(aligned).also { System.arraycopy(data, info.dataOffset, it, 0, aligned) }
        }
        info.formatCode == 3 && info.bitsPerSample == 32 -> {
            convertFloat32ToPcm16(data, info.dataOffset, info.dataSize)
        }
        else -> null
    }

    private fun convertFloat32ToPcm16(data: ByteArray, offset: Int, size: Int): ByteArray {
        val sampleCount = size / 4
        val inBuf = ByteBuffer.wrap(data, offset, sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(sampleCount * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) {
            val f = inBuf.float.coerceIn(-1.0f, 1.0f)
            outBuf.putShort((f * 32767.0f).toInt().toShort())
        }
        return out
    }

    fun encodeToWav(pcm16Data: ByteArray, sampleRate: Int = 24000, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val totalAudioLen = pcm16Data.size
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArray(44 + totalAudioLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(totalAudioLen + 36)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * bitsPerSample / 8).toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(totalAudioLen)
        System.arraycopy(pcm16Data, 0, out, 44, totalAudioLen)
        return out
    }
}
