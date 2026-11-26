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
    val dialogueVoiceId: String = "",
    val maleVoiceId: String = "",
    val femaleVoiceId: String = "",
    val elderVoiceId: String = "",
    val isDualRoleEnabled: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val sampleRate: Int = 24000,
    val audioFormat: String = "mp3",
    val promptInstruction: String = "",
    val isStreamingEnabled: Boolean = false,
    val fallbackProviderId: String? = null,
    val customHeadersJson: String = "{}",
    val customPayloadTemplate: String = DEFAULT_CUSTOM_PAYLOAD,
    val responseAudioPath: String = "",
    val extraParams: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList()
) {
    /**
     * Determines whether client-side Sonic speed scaling is required for the given transport mode.
     * Single source of truth: if the engine natively applies speed in that mode, client leaves audio untouched.
     */
    fun requiresClientSpeedScaling(
        isStreaming: Boolean,
        capabilities: ProviderCapabilities
    ): Boolean {
        if (kotlin.math.abs(speed - 1.0f) < 0.03f) return false
        return when (type) {
            ProviderType.CUSTOM_HTTP -> {
                !customPayloadTemplate.contains("\${speed}") && !baseUrl.contains("\${speed}")
            }
            else -> {
                if (isStreaming) !capabilities.supportsNativeSpeedInStream
                else !capabilities.supportsNativeSpeedInBatch
            }
        }
    }

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

/**
 * Combined provider config + credentials used only at synthesis/test execution time.
 */
data class ResolvedProviderConfig(
    val config: TtsProviderConfig,
    val credentials: ProviderCredentials = ProviderCredentials()
)
