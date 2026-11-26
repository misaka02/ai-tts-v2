package com.aitts.v2.data

import android.util.Base64
import com.aitts.v2.model.BackupPayload
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Backup/restore and token sharing manager.
 * Secrets are stripped by default unless the user explicitly opts in via [includeSecrets].
 */
class BackupManager(
    private val repository: AppPreferencesRepository,
    private val secretStore: SecretStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun exportAllJson(includeSecrets: Boolean = false): String {
        val providers = repository.providersFlow.value
        val creds = if (includeSecrets) {
            secretStore.exportAllCredentials(providers.map { it.id })
        } else {
            emptyMap()
        }
        val payload = BackupPayload(
            settings = repository.settingsFlow.value,
            providers = providers,
            rules = repository.rulesFlow.value,
            credentials = creds
        )
        return json.encodeToString(payload)
    }

    fun importAllJson(rawJson: String): Result<Unit> = runCatching {
        val payload = json.decodeFromString<BackupPayload>(rawJson)
        repository.updateSettings(payload.settings)
        repository.saveProviders(payload.providers)
        repository.saveRules(payload.rules)
        payload.credentials.forEach { (id, creds) ->
            secretStore.saveCredentials(id, creds)
        }
    }

    fun exportProviderToken(provider: TtsProviderConfig, includeSecrets: Boolean = false): String {
        val exportObj = if (includeSecrets) {
            val creds = secretStore.getCredentials(provider.id)
            ProviderShareEnvelope(provider, creds)
        } else {
            ProviderShareEnvelope(provider, ProviderCredentials())
        }
        val encoded = Base64.encodeToString(json.encodeToString(exportObj).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "aitts://provider?data=$encoded"
    }

    fun importProviderToken(token: String): Result<TtsProviderConfig> = runCatching {
        val raw = token.trim()
        val jsonStr = when {
            raw.startsWith("aitts://provider?data=") -> {
                val b64 = raw.substringAfter("aitts://provider?data=")
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }
            raw.startsWith("eyJ") -> String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            else -> raw
        }
        val envelope = runCatching { json.decodeFromString<ProviderShareEnvelope>(jsonStr) }.getOrNull()
        val config = envelope?.provider ?: json.decodeFromString<TtsProviderConfig>(jsonStr)
        val importedId = "imported_${UUID.randomUUID().toString().take(6)}"
        val saved = config.copy(id = importedId)
        repository.upsertProvider(saved, envelope?.credentials)
        saved
    }

    @kotlinx.serialization.Serializable
    private data class ProviderShareEnvelope(
        val provider: TtsProviderConfig,
        val credentials: ProviderCredentials = ProviderCredentials()
    )
}
