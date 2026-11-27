package com.aitts.v2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.network.NetworkConfig
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.PresetCatalog
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "aitts_v2_prefs")

/**
 * Clean Jetpack DataStore repository managing settings, providers, and rules (zero history disk thrashing).
 */
class AppPreferencesRepository(
    private val context: Context,
    private val secretStore: SecretStore,
    private val httpManager: HttpClientManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settingsFlow = MutableStateFlow(GlobalSettings())
    val settingsFlow: StateFlow<GlobalSettings> = _settingsFlow.asStateFlow()

    private val _providersFlow = MutableStateFlow(PresetCatalog.createDefaultProviders())
    val providersFlow: StateFlow<List<TtsProviderConfig>> = _providersFlow.asStateFlow()

    private val _rulesFlow = MutableStateFlow(PresetCatalog.defaultRules)
    val rulesFlow: StateFlow<List<ReplacementRule>> = _rulesFlow.asStateFlow()

    init {
        runBlocking {
            val prefs = context.appDataStore.data.first()
            prefs[KEY_SETTINGS]?.let { str ->
                runCatching { json.decodeFromString<GlobalSettings>(str) }.getOrNull()?.let { loaded ->
                    _settingsFlow.value = loaded
                    httpManager.updateIfChanged(NetworkConfig.fromSettings(loaded))
                }
            }
            prefs[KEY_PROVIDERS]?.let { str ->
                runCatching { json.decodeFromString<List<TtsProviderConfig>>(str) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { _providersFlow.value = it }
            }
            prefs[KEY_RULES]?.let { str ->
                runCatching { json.decodeFromString<List<ReplacementRule>>(str) }.getOrNull()
                    ?.let { _rulesFlow.value = it }
            }
        }
    }

    fun getActiveProvider(): TtsProviderConfig {
        val activeId = _settingsFlow.value.activeProviderId
        return _providersFlow.value.find { it.id == activeId }
            ?: _providersFlow.value.firstOrNull { it.enabled }
            ?: _providersFlow.value.firstOrNull()
            ?: PresetCatalog.createDefaultProviders().first()
    }

    fun getCredentials(providerId: String): ProviderCredentials = secretStore.getCredentials(providerId)

    fun saveCredentials(providerId: String, credentials: ProviderCredentials): Boolean =
        secretStore.saveCredentials(providerId, credentials)

    fun updateSettings(settings: GlobalSettings) {
        _settingsFlow.value = settings
        httpManager.updateIfChanged(NetworkConfig.fromSettings(settings))
        persist(KEY_SETTINGS, json.encodeToString(settings))
    }

    fun setActiveProviderId(id: String) {
        updateSettings(_settingsFlow.value.copy(activeProviderId = id))
        val updated = _providersFlow.value.map { p ->
            if (p.id == id && !p.enabled) p.copy(enabled = true) else p
        }
        saveProviders(updated)
    }

    fun saveProviders(providers: List<TtsProviderConfig>) {
        _providersFlow.value = providers
        persist(KEY_PROVIDERS, json.encodeToString(providers))
    }

    fun upsertProvider(provider: TtsProviderConfig, credentials: ProviderCredentials? = null) {
        val list = _providersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list.add(0, provider)
        saveProviders(list)
        if (credentials != null) {
            secretStore.saveCredentials(provider.id, credentials)
        }
    }

    fun moveProvider(id: String, delta: Int) {
        val list = _providersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val target = idx + delta
        if (idx >= 0 && target in list.indices) {
            val item = list.removeAt(idx)
            list.add(target, item)
            saveProviders(list)
        }
    }

    fun pinProviderToTop(id: String) {
        val list = _providersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx > 0) {
            val item = list.removeAt(idx)
            list.add(0, item)
            saveProviders(list)
        }
    }

    fun duplicateProvider(id: String) {
        val list = _providersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val src = list[idx]
            val newId = "${src.type.name.lowercase()}_${UUID.randomUUID().toString().take(6)}"
            list.add(idx + 1, src.copy(id = newId, name = "${src.name} (副本)"))
            saveProviders(list)
            secretStore.saveCredentials(newId, secretStore.getCredentials(id))
        }
    }

    fun deleteProvider(id: String) {
        val remaining = _providersFlow.value.filterNot { it.id == id }
        saveProviders(remaining)
        secretStore.removeCredentials(id)
        if (_settingsFlow.value.activeProviderId == id) {
            val nextActive = remaining.firstOrNull { it.enabled }?.id ?: remaining.firstOrNull()?.id.orEmpty()
            updateSettings(_settingsFlow.value.copy(activeProviderId = nextActive))
        }
    }

    fun sortProvidersByLatency(latencyMap: Map<String, Long>) {
        val sorted = _providersFlow.value.sortedBy { latencyMap[it.id] ?: Long.MAX_VALUE }
        saveProviders(sorted)
    }

    fun saveRules(rules: List<ReplacementRule>) {
        _rulesFlow.value = rules
        persist(KEY_RULES, json.encodeToString(rules))
    }

    fun upsertRule(rule: ReplacementRule) {
        val list = _rulesFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        saveRules(list)
    }

    fun deleteRule(id: String) {
        saveRules(_rulesFlow.value.filterNot { it.id == id })
    }

    fun resetToDefaults() {
        updateSettings(GlobalSettings())
        saveProviders(PresetCatalog.createDefaultProviders())
        saveRules(PresetCatalog.defaultRules)
    }

    private fun persist(key: Preferences.Key<String>, value: String) {
        scope.launch {
            context.appDataStore.edit { prefs -> prefs[key] = value }
        }
    }

    companion object {
        private val KEY_SETTINGS = stringPreferencesKey("global_settings")
        private val KEY_PROVIDERS = stringPreferencesKey("tts_providers")
        private val KEY_RULES = stringPreferencesKey("replacement_rules")
    }
}
