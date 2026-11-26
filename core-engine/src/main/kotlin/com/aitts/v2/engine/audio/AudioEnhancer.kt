package com.aitts.v2.engine.audio

import com.aitts.v2.model.AudioProcessingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Stateful PCM16 post-processor:
 * - Preserves filter state across streaming chunks to prevent click artifacts.
 * - Guarantees bit-exact passthrough when mode is PASSTHROUGH, gain is 1.0f, and silence trim is off.
 */
class AudioEnhancer {

    private var prevLeft = 0.0f
    private var prevRight = 0.0f

    fun processPcm(
        pcmData: ByteArray,
        channels: Int = 1,
        mode: AudioProcessingMode = AudioProcessingMode.PASSTHROUGH,
        customClarity: Boolean = false,
        customGain: Float = 1.0f,
        trimSilence: Boolean = false
    ): ByteArray {
        if (pcmData.size < 2) return pcmData

        val activePcm = if (trimSilence) trimDeadAirSilence(pcmData, channels) else pcmData
        val enableClarity = when (mode) {
            AudioProcessingMode.CUSTOM -> customClarity
            else -> mode.clarityBoost
        }
        val gain = when (mode) {
            AudioProcessingMode.CUSTOM -> customGain
            else -> mode.defaultGain
        }.coerceIn(0.1f, 2.5f)

        if (!enableClarity && kotlin.math.abs(gain - 1.0f) < 0.005f) {
            return activePcm
        }

        val frameSize = 2 * channels
        val totalFrames = activePcm.size / frameSize
        if (totalFrames <= 0) return activePcm

        val input = ByteBuffer.wrap(activePcm, 0, totalFrames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(totalFrames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        val alpha = 0.38f

        for (i in 0 until totalFrames) {
            val l = input.short.toFloat()
            val fl = if (enableClarity) (l - alpha * prevLeft).also { prevLeft = l } else l
            val compL = tanh((fl * gain / 32767.0f).toDouble()).toFloat() * 32767.0f
            output.putShort(compL.toInt().coerceIn(-32768, 32767).toShort())

            if (channels > 1) {
                val r = input.short.toFloat()
                val fr = if (enableClarity) (r - alpha * prevRight).also { prevRight = r } else r
                val compR = tanh((fr * gain / 32767.0f).toDouble()).toFloat() * 32767.0f
                output.putShort(compR.toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        return output.array()
    }

    fun reset() {
        prevLeft = 0.0f
        prevRight = 0.0f
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
