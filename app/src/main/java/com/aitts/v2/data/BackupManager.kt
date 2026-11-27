package com.aitts.v2.data

import com.aitts.v2.model.BackupPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full JSON backup/restore manager.
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
}
