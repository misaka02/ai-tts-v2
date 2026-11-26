package com.aitts.v2.engine.synthesis

import com.aitts.v2.engine.audio.AudioEnhancer
import com.aitts.v2.model.AudioProcessingMode
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.SegmentRole
import com.aitts.v2.model.SentenceSegment
import com.aitts.v2.model.SpeechHistoryItem
import com.aitts.v2.model.SpeedPolicy
import com.aitts.v2.model.TtsProviderConfig
import java.util.UUID

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

    fun buildSegmentConfig(base: TtsProviderConfig, seg: SentenceSegment, settings: GlobalSettings): TtsProviderConfig {
        var cfg = if (!base.isDualRoleEnabled) {
            base
        } else {
            val overrideVoice = when (seg.role) {
                SegmentRole.ELDER_DIALOGUE -> base.elderVoiceId.ifBlank { base.dialogueVoiceId }
                SegmentRole.FEMALE_DIALOGUE -> base.femaleVoiceId.ifBlank { base.dialogueVoiceId }
                SegmentRole.MALE_DIALOGUE -> base.maleVoiceId.ifBlank { base.dialogueVoiceId }
                SegmentRole.DIALOGUE -> base.dialogueVoiceId
                SegmentRole.NARRATOR -> ""
            }
            if (overrideVoice.isNotBlank()) base.copy(voiceId = overrideVoice) else base
        }
        if (settings.isEmotionProsodyEnabled && seg.emotion.promptInstruction.isNotBlank()) {
            val basePrompt = cfg.promptInstruction.trim()
            val blended = if (basePrompt.isNotBlank()) "$basePrompt ${seg.emotion.promptInstruction}" else seg.emotion.promptInstruction
            cfg = cfg.copy(promptInstruction = blended)
        }
        return cfg
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

    fun processChunkEnhancement(
        enhancer: AudioEnhancer,
        pcm: ByteArray,
        settings: GlobalSettings,
        effectiveGain: Float
    ): ByteArray {
        val mode = if (kotlin.math.abs(effectiveGain - 1.0f) >= 0.02f &&
            settings.audioProcessingMode == AudioProcessingMode.PASSTHROUGH
        ) {
            AudioProcessingMode.CUSTOM
        } else {
            settings.audioProcessingMode
        }
        val gain = if (mode == AudioProcessingMode.CUSTOM) {
            (settings.loudnessGainFactor * effectiveGain).coerceIn(0.05f, 2.5f)
        } else {
            effectiveGain
        }
        return enhancer.processPcm(pcm, 1, mode, settings.voiceClarityBoostEnabled, gain, trimSilence = false)
    }

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

    fun recordHistory(
        input: SynthesisInput,
        providerName: String,
        voiceId: String,
        text: String,
        startMs: Long,
        fallback: Boolean
    ) {
        input.onHistoryRecorded(
            SpeechHistoryItem(
                id = UUID.randomUUID().toString().take(8),
                text = text.take(60),
                providerName = providerName,
                voiceId = voiceId.ifBlank { "默认" },
                costMs = System.currentTimeMillis() - startMs,
                characterCount = text.length,
                isFallbackUsed = fallback
            )
        )
    }
}
