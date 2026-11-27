package com.aitts.v2.engine.audio

import com.aitts.v2.engine.pipeline.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Lossless-first PCM16 post-processor implementing [AudioProcessor].
 * Guarantees bit-exact passthrough unless silence trimming or sleep-timer gain fade is active.
 */
class AudioEnhancer : AudioProcessor {

    override fun process(
        pcm16: ByteArray,
        sampleRate: Int,
        gain: Float,
        trimSilence: Boolean
    ): ByteArray = processPcm(pcm16, channels = 1, gain = gain, trimSilence = trimSilence)

    fun processPcm(
        pcmData: ByteArray,
        channels: Int = 1,
        gain: Float = 1.0f,
        trimSilence: Boolean = false
    ): ByteArray {
        if (pcmData.size < 2) return pcmData

        val activePcm = if (trimSilence) trimDeadAirSilence(pcmData, channels) else pcmData
        val clampedGain = gain.coerceIn(0.05f, 2.0f)
        if (abs(clampedGain - 1.0f) < 0.005f) {
            return activePcm
        }

        val frameSize = 2 * channels
        val totalFrames = activePcm.size / frameSize
        if (totalFrames <= 0) return activePcm

        val input = ByteBuffer.wrap(activePcm, 0, totalFrames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(totalFrames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames * channels) {
            val scaled = (input.short * clampedGain).toInt().coerceIn(-32768, 32767).toShort()
            output.putShort(scaled)
        }
        return output.array()
    }

    companion object {
        fun trimDeadAirSilence(pcmData: ByteArray, channels: Int = 1, thresholdAbs: Short = 300): ByteArray {
            val frameSize = 2 * channels
            val totalFrames = pcmData.size / frameSize
            if (totalFrames < 240) return pcmData

            val samples = ShortArray(totalFrames * channels)
            ByteBuffer.wrap(pcmData, 0, totalFrames * frameSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples)

            var startFrame = 0
            while (startFrame < totalFrames && !frameActive(samples, startFrame, channels, thresholdAbs)) {
                startFrame++
            }
            var endFrame = totalFrames - 1
            while (endFrame > startFrame && !frameActive(samples, endFrame, channels, thresholdAbs)) {
                endFrame--
            }

            val marginFrames = 120
            startFrame = maxOf(0, startFrame - marginFrames)
            endFrame = minOf(totalFrames - 1, endFrame + marginFrames)
            val trimmedFrames = endFrame - startFrame + 1
            if (trimmedFrames <= 0 || trimmedFrames == totalFrames) return pcmData

            val fadeFrames = minOf(60, trimmedFrames / 2)
            val outSamples = ShortArray(trimmedFrames * channels)
            for (i in 0 until trimmedFrames) {
                val srcFrame = startFrame + i
                val fade = when {
                    i < fadeFrames -> i.toFloat() / fadeFrames
                    i > trimmedFrames - fadeFrames -> (trimmedFrames - 1 - i).toFloat() / fadeFrames
                    else -> 1.0f
                }
                for (ch in 0 until channels) {
                    outSamples[i * channels + ch] = (samples[srcFrame * channels + ch] * fade).toInt().toShort()
                }
            }

            val outBytes = ByteArray(outSamples.size * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outSamples)
            return outBytes
        }

        private fun frameActive(samples: ShortArray, frame: Int, channels: Int, threshold: Short): Boolean {
            for (ch in 0 until channels) {
                if (abs(samples[frame * channels + ch].toInt()) > threshold) return true
            }
            return false
        }
    }
}
