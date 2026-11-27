package com.aitts.v2.model

import kotlinx.serialization.Serializable

/**
 * Export-safe TTS provider profile configuration.
 * API keys are stored separately in [ProviderCredentials] via Keystore-backed SecretStore.
 */
@Serializable
data class TtsProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val modelName: String = "",
    val voiceId: String = "",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sampleRate: Int = 24000,
    val audioFormat: String = "mp3",
    val promptInstruction: String = "",
    val isStreamingEnabled: Boolean = false,
    val fallbackProviderId: String? = null,
    val customHeadersJson: String = "{}",
    val customPayloadTemplate: String = DEFAULT_CUSTOM_PAYLOAD,
    val responseAudioPath: String = "",
    val extraParams: Map<String, String> = emptyMap()
) {
    /**
     * Splits requested speed into vendor-compliant upstream speed and client Sonic residual factor.
     */
    fun resolveSpeed(
        isStreaming: Boolean,
        capabilities: ProviderCapabilities
    ): SpeedResolution = AcousticMapper.resolveSpeed(this, isStreaming, capabilities)

    /**
     * Determines whether client-side Sonic speed scaling is required for the given transport mode.
     * Single source of truth: covers both non-native engines and out-of-range residual speeds.
     */
    fun requiresClientSpeedScaling(
        isStreaming: Boolean,
        capabilities: ProviderCapabilities
    ): Boolean = resolveSpeed(isStreaming, capabilities).needsClientSonic

    companion object {
        const val DEFAULT_CUSTOM_PAYLOAD =
            "{\n  \"model\": \"\${model}\",\n  \"input\": \"\${text}\",\n  \"voice\": \"\${voice}\",\n  \"speed\": \${speed}\n}"
    }
}

/**
 * Sensitive API credentials bound to a provider profile ID.
 */
@Serializable
data class ProviderCredentials(
    val apiKey: String = "",
    val secondaryApiKey: String = ""
)
