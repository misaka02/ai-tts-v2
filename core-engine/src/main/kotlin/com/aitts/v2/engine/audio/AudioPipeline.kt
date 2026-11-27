package com.aitts.v2.engine.audio

import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.TtsProviderConfig
import java.io.IOException

/**
 * Platform-injected decoder for compressed formats (MP3 / AAC / OGG / FLAC).
 */
fun interface CompressedAudioDecoder {
    fun decode(encodedBytes: ByteArray, fallbackSampleRate: Int): Result<DecodedPcm>
}

/**
 * Unified audio decoding, resampling, speed scaling, and enhancement pipeline.
 */
class AudioPipeline(
    private val compressedDecoder: CompressedAudioDecoder
) {

    fun decodeToStandardPcm(
        rawBytes: ByteArray,
        fallbackSampleRate: Int,
        targetSampleRate: Int = 24000
    ): Result<ByteArray> {
        if (rawBytes.isEmpty()) {
            return Result.failure(IOException("Audio payload is empty"))
        }
        val decoded = when (AudioFormatDetector.detect(rawBytes)) {
            DetectedAudioFormat.WAV -> {
                WavCodec.parseWav(rawBytes)
                    ?: return Result.failure(IOException("Invalid or unsupported WAV header"))
            }
            DetectedAudioFormat.RAW_PCM -> {
                DecodedPcm(
                    pcm16Data = rawBytes.copyOf(rawBytes.size and 0x7FFFFFFE),
                    sampleRate = fallbackSampleRate,
                    channelCount = 1
                )
            }
            else -> {
                val decRes = compressedDecoder.decode(rawBytes, fallbackSampleRate)
                if (decRes.isFailure) {
                    return Result.failure(
                        decRes.exceptionOrNull() ?: IOException("Failed to decode compressed audio")
                    )
                }
                decRes.getOrThrow()
            }
        }

        if (decoded.pcm16Data.isEmpty()) {
            return Result.failure(IOException("Decoded PCM payload is empty"))
        }

        val resampled = PcmResampler.resample(
            pcmData = decoded.pcm16Data,
            sourceSampleRate = decoded.sampleRate,
            sourceChannels = decoded.channelCount,
            targetSampleRate = targetSampleRate,
            targetChannels = 1
        )
        return Result.success(resampled)
    }

    fun applyBatchPostProcessing(
        pcm24kMono: ByteArray,
        config: TtsProviderConfig,
        capabilities: ProviderCapabilities,
        settings: GlobalSettings,
        effectiveGain: Float
    ): ByteArray {
        val speedPlan = config.resolveSpeed(isStreaming = false, capabilities = capabilities)
        val scaled = if (speedPlan.needsClientSonic) {
            SonicSpeedScaler.scaleBatch(pcm24kMono, 24000, 1, speedPlan.clientSonicFactor, speedPlan.clientSonicPitch)
        } else {
            pcm24kMono
        }
        return AudioEnhancer().processPcm(
            pcmData = scaled,
            channels = 1,
            gain = effectiveGain,
            trimSilence = settings.trimDeadSilence
        )
    }
}
