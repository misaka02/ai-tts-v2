package com.aitts.v2.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aitts.v2.engine.audio.CompressedAudioDecoder
import com.aitts.v2.engine.audio.DecodedPcm
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Zero-disk in-memory compressed audio decoder backed by MediaExtractor + MediaCodec.
 * Properly updates sampleRate/channelCount on MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
 * and returns Result.failure rather than emitting undecoded noise bytes.
 */
class AndroidAudioDecoder : CompressedAudioDecoder {

    override fun decode(encodedBytes: ByteArray, fallbackSampleRate: Int): Result<DecodedPcm> {
        if (encodedBytes.isEmpty()) return Result.failure(IOException("Encoded audio is empty"))

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return runCatching {
            extractor.setDataSource(InMemoryDataSource(encodedBytes))
            val (trackIndex, trackFormat) = findAudioTrack(extractor)
                ?: throw IOException("No audio track found in compressed stream")

            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val initRate = trackFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, fallbackSampleRate)
            val initCh = trackFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }
            drainCodecLoop(codec!!, extractor, encodedBytes.size, initRate, initCh)
        }.also {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i to fmt
        }
        return null
    }

    private fun drainCodecLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        encodedSize: Int,
        initialRate: Int,
        initialChannels: Int
    ): DecodedPcm {
        var sampleRate = initialRate
        var channels = initialChannels
        val outStream = ByteArrayOutputStream(minOf(maxOf(encodedSize * 10, 16384), 4 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 5_000L

        while (!outputDone) {
            if (!inputDone) {
                inputDone = feedInputBuffer(codec, extractor, timeoutUs)
            }
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFmt = codec.outputFormat
                    sampleRate = outFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = outFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                }
                outIdx >= 0 -> {
                    readOutputBuffer(codec, outIdx, info, outStream)
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }
        val pcm = outStream.toByteArray()
        if (pcm.isEmpty()) throw IOException("MediaCodec produced empty PCM output")
        return DecodedPcm(pcm16Data = pcm, sampleRate = sampleRate, channelCount = channels)
    }

    private fun feedInputBuffer(codec: MediaCodec, extractor: MediaExtractor, timeoutUs: Long): Boolean {
        val inIdx = codec.dequeueInputBuffer(timeoutUs)
        if (inIdx < 0) return false
        val inBuf = codec.getInputBuffer(inIdx)
        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
        return if (sampleSize < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
            false
        }
    }

    private fun readOutputBuffer(
        codec: MediaCodec,
        outIdx: Int,
        info: MediaCodec.BufferInfo,
        outStream: ByteArrayOutputStream
    ) {
        val outBuf = codec.getOutputBuffer(outIdx) ?: return
        if (info.size <= 0) return
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        val chunk = ByteArray(info.size)
        outBuf.get(chunk)
        outStream.write(chunk)
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private class InMemoryDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val available = (data.size - position).toInt()
            val toCopy = minOf(size, available)
            System.arraycopy(data, position.toInt(), buffer, offset, toCopy)
            return toCopy
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {
            // In-memory byte array requires no native resource release.
        }
    }
}
