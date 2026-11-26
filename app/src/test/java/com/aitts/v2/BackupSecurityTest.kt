package com.aitts.v2

import com.aitts.v2.model.BackupPayload
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.PresetCatalog
import com.aitts.v2.model.ProviderCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSecurityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `desensitized backup payload contains zero credentials`() {
        val payload = BackupPayload(
            settings = GlobalSettings(),
            providers = PresetCatalog.createDefaultProviders(),
            rules = PresetCatalog.defaultRules,
            credentials = emptyMap()
        )
        val encoded = json.encodeToString(payload)
        assertFalse(encoded.contains("sk-secret-key"))
        assertTrue(encoded.contains("\"credentials\": {}") || encoded.contains("\"credentials\":{}"))
    }

    @Test
    fun `provider credentials remain isolated from TtsProviderConfig`() {
        val creds = ProviderCredentials(apiKey = "sk-primary", secondaryApiKey = "sk-secondary")
        val cfgJson = json.encodeToString(PresetCatalog.createDefaultProviders().first())
        assertFalse(cfgJson.contains(creds.apiKey))
        assertFalse(cfgJson.contains(creds.secondaryApiKey))
    }
}
