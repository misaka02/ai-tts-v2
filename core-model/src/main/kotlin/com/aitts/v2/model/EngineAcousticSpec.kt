package com.aitts.v2.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-engine native acoustic capabilities and valid upstream parameter bounds.
 */
data class EngineAcousticSpec(
    val nativeSpeedRange: ClosedFloatingPointRange<Float>?,
    val pitchRange: ClosedFloatingPointRange<Float>?,
    val pitchHint: String = ""
) {
    val supportsPitch: Boolean
        get() = pitchRange != null

    val speedSliderRange: ClosedFloatingPointRange<Float>
        get() = if (nativeSpeedRange == null) {
            0.4f..2.5f
        } else {
            minOf(0.4f, nativeSpeedRange.start)..maxOf(2.5f, nativeSpeedRange.endInclusive)
        }

    val speedModeLabel: String
        get() = if (nativeSpeedRange == null) {
            "本地 Sonic 精确变速 (0.4x ~ 2.5x)"
        } else {
            val lo = formatRate(nativeSpeedRange.start)
            val hi = formatRate(nativeSpeedRange.endInclusive)
            "原生语速 ${lo}x ~ ${hi}x（超出部分由本地 Sonic 精准补足）"
        }

    private fun formatRate(v: Float): String =
        java.lang.String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

/**
 * Split of a target playback speed/pitch between upstream engine and local Sonic residual scaling.
 */
data class SpeedResolution(
    val upstreamSpeed: Float,
    val clientSonicFactor: Float,
    val clientSonicPitch: Float = 1.0f
) {
    val needsClientSonic: Boolean
        get() = abs(clientSonicFactor - 1.0f) >= 0.03f || abs(clientSonicPitch - 1.0f) >= 0.03f
}

/**
 * Single source of truth mapping user acoustic controls into each vendor's protocol bounds.
 */
object AcousticMapper {

    fun supportsNativePitch(type: ProviderType, voiceId: String): Boolean {
        val spec = ProviderDescriptorRegistry.descriptorFor(type).acousticSpec
        if (!spec.supportsPitch) return false
        if (type == ProviderType.DOUBAO && voiceId.contains("bigtts", ignoreCase = true)) return false
        return true
    }

    fun resolveSpeed(
        cfg: TtsProviderConfig,
        isStreaming: Boolean,
        capabilities: ProviderCapabilities
    ): SpeedResolution {
        val target = cfg.speed.coerceIn(0.25f, 3.0f)
        val sonicPitch = resolveClientPitch(cfg)
        if (cfg.type == ProviderType.CUSTOM_HTTP) {
            val hasPlaceholder = cfg.customPayloadTemplate.contains("\${speed}") || cfg.baseUrl.contains("\${speed}")
            return if (hasPlaceholder) {
                SpeedResolution(cfg.speed.coerceIn(0.25f, 4.0f), 1.0f, sonicPitch)
            } else {
                SpeedResolution(1.0f, target, sonicPitch)
            }
        }
        val range = ProviderDescriptorRegistry.descriptorFor(cfg.type).acousticSpec.nativeSpeedRange
            ?: return SpeedResolution(1.0f, target, sonicPitch)
        val canNative = if (isStreaming) {
            capabilities.supportsNativeSpeedInStream
        } else {
            capabilities.supportsNativeSpeedInBatch
        }
        if (!canNative) {
            return SpeedResolution(1.0f, target, sonicPitch)
        }
        val upstream = target.coerceIn(range.start, range.endInclusive)
        val rawResidual = (target / upstream).coerceIn(0.5f, 2.0f)
        val residual = if (abs(rawResidual - 1.0f) < 0.03f) 1.0f else rawResidual
        return SpeedResolution(upstream, residual, sonicPitch)
    }

    private fun resolveClientPitch(cfg: TtsProviderConfig): Float {
        val targetPitch = cfg.pitch.coerceIn(0.5f, 2.0f)
        if (abs(targetPitch - 1.0f) < 0.03f) return 1.0f
        if (cfg.type == ProviderType.CUSTOM_HTTP) {
            val hasPlaceholder = cfg.customPayloadTemplate.contains("\${pitch}") || cfg.baseUrl.contains("\${pitch}")
            return if (hasPlaceholder) 1.0f else targetPitch
        }
        if (!supportsNativePitch(cfg.type, cfg.voiceId)) return targetPitch
        val pRange = ProviderDescriptorRegistry.descriptorFor(cfg.type).acousticSpec.pitchRange
            ?: return targetPitch
        val upstreamPitch = targetPitch.coerceIn(pRange.start, pRange.endInclusive)
        val residual = (targetPitch / upstreamPitch).coerceIn(0.5f, 2.0f)
        return if (abs(residual - 1.0f) < 0.03f) 1.0f else residual
    }

    fun edgeSsmlRate(speed: Float): String = formatPercentOffset(speed.coerceIn(0.5f, 2.0f), -50, 100)

    fun edgeSsmlPitchHz(pitch: Float): String {
        val clamped = pitch.coerceIn(0.5f, 1.5f)
        val hz = ((clamped - 1.0f) * 100f).roundToInt().coerceIn(-50, 50)
        return if (hz >= 0) "+${hz}Hz" else "${hz}Hz"
    }

    fun azureSsmlRate(speed: Float): String = formatPercentOffset(speed.coerceIn(0.5f, 3.0f), -50, 200)

    fun azureSsmlPitchPct(pitch: Float): String {
        val clamped = pitch.coerceIn(0.5f, 1.5f)
        val pct = ((clamped - 1.0f) * 100f).roundToInt().coerceIn(-50, 50)
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    fun minimaxSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.0f)

    fun minimaxPitchSemitones(pitch: Float): Int {
        val clamped = pitch.coerceIn(0.5f, 1.5f)
        return ((clamped - 1.0f) * 24f).roundToInt().coerceIn(-12, 12)
    }

    fun doubaoSpeedRatio(speed: Float): Float = speed.coerceIn(0.5f, 2.0f)

    fun doubaoPitchRatio(pitch: Float, voiceId: String): Float? =
        if (voiceId.contains("bigtts", ignoreCase = true)) null else pitch.coerceIn(0.5f, 1.5f)

    fun openAiSpeed(speed: Float): Float = speed.coerceIn(0.25f, 4.0f)

    fun fishSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.0f)

    fun sherpaSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.5f)

    private fun formatPercentOffset(clampedSpeed: Float, minPct: Int, maxPct: Int): String {
        val pct = ((clampedSpeed - 1.0f) * 100f).roundToInt().coerceIn(minPct, maxPct)
        return if (pct >= 0) "+$pct%" else "$pct%"
    }
}
