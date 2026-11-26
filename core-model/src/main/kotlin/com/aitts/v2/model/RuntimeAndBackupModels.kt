package com.aitts.v2.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechHistoryItem(
    val id: String,
    val text: String,
    val providerName: String,
    val voiceId: String,
    val costMs: Long,
    val characterCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val isFallbackUsed: Boolean = false
)

@Serializable
enum class LogLevel(val badge: String) {
    INFO("INFO"),
    SUCCESS("OK"),
    WARN("WARN"),
    ERROR("ERR"),
    METRIC("PERF")
}

@Serializable
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val title: String,
    val details: String? = null,
    val sessionId: String? = null
) {
    fun formatLine(): String {
        val sess = if (!sessionId.isNullOrBlank()) " [$sessionId]" else ""
        val tail = if (!details.isNullOrBlank()) " | $details" else ""
        return "[$timestamp] [${level.badge}] [$tag]$sess $title$tail"
    }
}

data class RequestLogSession(
    val sessionId: String,
    val startTime: String,
    val endTime: String,
    val summaryTitle: String,
    val subtitle: String?,
    val isComplete: Boolean,
    val isError: Boolean,
    val entries: List<LogEntry>
)

@Serializable
enum class ModelArch(val displayName: String) {
    VITS("VITS"),
    MATCHA("Matcha"),
    KOKORO("Kokoro"),
    ZIPVOICE("ZipVoice")
}

@Serializable
data class OfflineModelSpec(
    val id: String,
    val name: String,
    val arch: ModelArch,
    val category: String,
    val sizeMb: Int,
    val description: String,
    val speakerCount: Int,
    val sampleRate: Int,
    val defaultVoiceId: String,
    val speakers: List<String>,
    val githubUrl: String,
    val mirrorUrl: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class BackupPayload(
    val version: Int = 2,
    val settings: GlobalSettings,
    val providers: List<TtsProviderConfig>,
    val rules: List<ReplacementRule>,
    val credentials: Map<String, ProviderCredentials> = emptyMap()
)
