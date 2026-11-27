package com.aitts.v2.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `every default offline provider references a valid OfflineCatalogData spec`() {
        val offlineProviders = PresetCatalog.createDefaultProviders()
            .filter { it.type == ProviderType.OFFLINE_SHERPA }

        assertTrue(offlineProviders.isNotEmpty())
        for (provider in offlineProviders) {
            val spec = OfflineCatalogData.findById(provider.modelName)
            assertNotNull("Missing offline catalog model for ${provider.modelName}", spec)
        }
    }

    @Test
    fun `offline catalog ids are unique and carry explicit ModelArch`() {
        val ids = OfflineCatalogData.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(OfflineCatalogData.findById("matcha-icefall-zh-baker")?.let {
            assertEquals(ModelArch.MATCHA, it.arch)
        })
        assertNotNull(OfflineCatalogData.findById("kokoro-int8-multi-lang-v1_0")?.let {
            assertEquals(ModelArch.KOKORO, it.arch)
        })
    }

    @Test
    fun `backup payload serialization round-trip preserves settings and providers`() {
        val payload = BackupPayload(
            settings = GlobalSettings(maxCacheSizeMb = 250, isAmoledPureBlack = true),
            providers = PresetCatalog.createDefaultProviders(),
            rules = PresetCatalog.defaultRules
        )
        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<BackupPayload>(encoded)
        assertEquals(payload.settings, decoded.settings)
        assertEquals(payload.providers.size, decoded.providers.size)
        assertEquals(payload.rules.size, decoded.rules.size)
    }

    @Test
    fun `client speed scaling policy avoids double scaling when engine supports native speed`() {
        val nativeCaps = ProviderCapabilities(
            supportsNativePcmStreaming = true,
            supportsNativeSpeedInBatch = true,
            supportsNativeSpeedInStream = true
        )
        val mimoCaps = ProviderCapabilities(
            supportsNativePcmStreaming = true,
            supportsNativeSpeedInBatch = true,
            supportsNativeSpeedInStream = false
        )

        val openaiCfg = TtsProviderConfig(id = "1", type = ProviderType.OPENAI_COMPATIBLE, name = "OAI", speed = 1.4f)
        assertFalse(openaiCfg.requiresClientSpeedScaling(isStreaming = false, capabilities = nativeCaps))
        assertFalse(openaiCfg.requiresClientSpeedScaling(isStreaming = true, capabilities = nativeCaps))

        val mimoCfg = TtsProviderConfig(id = "2", type = ProviderType.MIMO, name = "MiMo", speed = 1.4f)
        assertFalse(mimoCfg.requiresClientSpeedScaling(isStreaming = false, capabilities = mimoCaps))
        assertTrue(mimoCfg.requiresClientSpeedScaling(isStreaming = true, capabilities = mimoCaps))

        val customNoSpeed = TtsProviderConfig(
            id = "3",
            type = ProviderType.CUSTOM_HTTP,
            name = "Custom",
            speed = 1.5f,
            customPayloadTemplate = "{\"text\":\"\${text}\"}"
        )
        assertTrue(customNoSpeed.requiresClientSpeedScaling(isStreaming = false, capabilities = ProviderCapabilities()))
    }

    @Test
    fun `legacy GlobalSettings json without floatingSubtitleY defaults cleanly`() {
        val legacyJson = """{"activeProviderId":"edge_tts_xiaoxiao","isFloatingSubtitleEnabled":true}"""
        val decoded = json.decodeFromString<GlobalSettings>(legacyJson)
        assertEquals(-1, decoded.floatingSubtitleY)
        assertTrue(decoded.isFloatingSubtitleEnabled)
    }
}
