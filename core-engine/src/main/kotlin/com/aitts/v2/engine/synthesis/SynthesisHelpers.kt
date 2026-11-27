package com.aitts.v2.engine.synthesis

import com.aitts.v2.engine.audio.AudioEnhancer
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.SpeedPolicy
import com.aitts.v2.model.TtsProviderConfig

internal object SynthesisHelpers {

    fun resolveSpeedAndPitch(cfg: TtsProviderConfig, input: SynthesisInput): TtsProviderConfig {
        val speed = when (input.settings.speedPolicy) {
            SpeedPolicy.MULTIPLY -> cfg.speed * input.systemSpeed * input.settings.globalSpeed
            SpeedPolicy.ENGINE_ONLY -> cfg.speed * input.settings.globalSpeed
            SpeedPolicy.SYSTEM_ONLY -> input.systemSpeed * input.settings.globalSpeed
        }.coerceIn(0.25f, 3.0f)
        val pitch = (cfg.pitch * input.systemPitch * input.settings.globalPitch).coerceIn(0.5f, 2.0f)
        return cfg.copy(speed = speed, pitch = pitch)
    }

    fun rotateCredentials(creds: ProviderCredentials, index: Int): ProviderCredentials {
        if (creds.secondaryApiKey.isBlank() || index % 2 == 0) return creds
        return creds.copy(apiKey = creds.secondaryApiKey)
    }

    fun findFallbackProvider(primary: TtsProviderConfig, input: SynthesisInput): TtsProviderConfig? {
        val candidateId = primary.fallbackProviderId?.takeIf { it.isNotBlank() } ?: input.settings.fallbackProviderId
        return input.allProviders.find { it.id == candidateId && it.id != primary.id }
            ?: input.allProviders.find { it.type == ProviderType.EDGE_TTS && it.id != primary.id }
    }

    fun processChunkGain(
        enhancer: AudioEnhancer,
        pcm: ByteArray,
        effectiveGain: Float
    ): ByteArray = enhancer.processPcm(pcm, channels = 1, gain = effectiveGain, trimSilence = false)

    fun emitInChunks(pcm: ByteArray, sink: SynthesisSink) {
        var off = 0
        val chunkSize = 2048
        while (off < pcm.size) {
            val len = minOf(chunkSize, pcm.size - off)
            sink.onPcmChunk(pcm.copyOfRange(off, off + len))
            off += len
        }
    }

    fun emitSilence(sampleRate: Int, pauseMs: Int, sink: SynthesisSink) {
        val totalBytes = ((sampleRate * 2) * (pauseMs / 1000.0)).toInt() and 0x7FFFFFFE
        if (totalBytes <= 0) return
        val buf = ByteArray(minOf(totalBytes, 2048))
        var rem = totalBytes
        while (rem > 0) {
            val n = minOf(rem, buf.size)
            sink.onPcmChunk(if (n == buf.size) buf else ByteArray(n))
            rem -= n
        }
    }
}
