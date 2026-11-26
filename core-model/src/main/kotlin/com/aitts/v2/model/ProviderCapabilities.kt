package com.aitts.v2.model

/**
 * Explicit runtime capability declaration for a [TtsProvider].
 */
data class ProviderCapabilities(
    val supportsNativePcmStreaming: Boolean = false,
    val supportsNativeSpeedInBatch: Boolean = true,
    val supportsNativeSpeedInStream: Boolean = true,
    val supportsDirectorPrompt: Boolean = false,
    val supportsVoiceDiscovery: Boolean = false,
    val supportsModelDiscovery: Boolean = false
)
