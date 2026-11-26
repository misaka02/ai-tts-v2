package com.aitts.v2.engine.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-fidelity 16-bit PCM channel mixer and linear resampler.
 */
object PcmResampler {

    fun resample(
        pcmData: ByteArray,
        sourceSampleRate: Int,
        sourceChannels: Int = 1,
        targetSampleRate: Int = 24000,
        targetChannels: Int = 1
    ): ByteArray {
        if (pcmData.size < 2 || sourceSampleRate <= 0 || targetSampleRate <= 0) return pcmData
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) return pcmData

        val inFrameSize = 2 * sourceChannels
        val totalInFrames = pcmData.size / inFrameSize
        if (totalInFrames <= 0) return ByteArray(0)

        val inSamples = ShortArray(totalInFrames * sourceChannels)
        ByteBuffer.wrap(pcmData, 0, totalInFrames * inFrameSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(inSamples)

        val channelConverted = convertChannels(inSamples, totalInFrames, sourceChannels, targetChannels)
        if (sourceSampleRate == targetSampleRate) {
            return shortsToBytes(channelConverted)
        }

        val inFrames = channelConverted.size / targetChannels
        val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val outFrames = (inFrames / ratio).toInt()
        if (outFrames <= 0) return ByteArray(0)

        val outSamples = ShortArray(outFrames * targetChannels)
        for (ch in 0 until targetChannels) {
            for (outIdx in 0 until outFrames) {
                val pos = outIdx * ratio
                val idx0 = pos.toInt().coerceIn(0, inFrames - 1)
                val idx1 = minOf(idx0 + 1, inFrames - 1)
                val frac = (pos - idx0).coerceIn(0.0, 1.0)
                val s0 = channelConverted[idx0 * targetChannels + ch].toInt()
                val s1 = channelConverted[idx1 * targetChannels + ch].toInt()
                val interpolated = (s0 * (1.0 - frac) + s1 * frac).toInt()
                outSamples[outIdx * targetChannels + ch] = interpolated.coerceIn(-32768, 32767).toShort()
            }
        }
        return shortsToBytes(outSamples)
    }

    private fun convertChannels(
        inSamples: ShortArray,
        frames: Int,
        srcCh: Int,
        dstCh: Int
    ): ShortArray = when {
        srcCh == 2 && dstCh == 1 -> ShortArray(frames) { i ->
            ((inSamples[i * 2].toInt() + inSamples[i * 2 + 1].toInt()) / 2).toShort()
        }
        srcCh == 1 && dstCh == 2 -> ShortArray(frames * 2).also { out ->
            for (i in 0 until frames) {
                val s = inSamples[i]
                out[i * 2] = s
                out[i * 2 + 1] = s
            }
        }
        else -> inSamples
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return out
    }
}
