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
    fun `descriptor registry covers every ProviderType and default catalog has single ready edge node`() {
        val all = ProviderDescriptorRegistry.allDescriptors()
        assertEquals(ProviderType.entries.size, all.size)

        val defaults = PresetCatalog.createDefaultProviders()
        assertEquals(1, defaults.size)
        assertEquals(ProviderType.EDGE_TTS, defaults.single().type)
        assertTrue(defaults.single().enabled)
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
        val batchOnlyCaps = ProviderCapabilities(
            supportsNativePcmStreaming = true,
            supportsNativeSpeedInBatch = true,
            supportsNativeSpeedInStream = false
        )

        val openaiCfg = TtsProviderConfig(id = "1", type = ProviderType.OPENAI_COMPATIBLE, name = "OAI", speed = 1.4f)
        assertFalse(openaiCfg.requiresClientSpeedScaling(isStreaming = false, capabilities = nativeCaps))
        assertFalse(openaiCfg.requiresClientSpeedScaling(isStreaming = true, capabilities = nativeCaps))

        val azureCfg = TtsProviderConfig(id = "az", type = ProviderType.AZURE, name = "Azure", speed = 1.4f)
        assertFalse(azureCfg.requiresClientSpeedScaling(isStreaming = false, capabilities = batchOnlyCaps))
        assertTrue(azureCfg.requiresClientSpeedScaling(isStreaming = true, capabilities = batchOnlyCaps))

        val mimoCfg = TtsProviderConfig(id = "2", type = ProviderType.MIMO, name = "MiMo", speed = 1.4f)
        assertTrue(mimoCfg.requiresClientSpeedScaling(isStreaming = false, capabilities = nativeCaps))
        assertTrue(mimoCfg.requiresClientSpeedScaling(isStreaming = true, capabilities = batchOnlyCaps))

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
    fun `acoustic mapper handles vendor bounds and residual sonic factor accurately`() {
        val mmCaps = ProviderCapabilities(
            supportsNativePcmStreaming = true,
            supportsNativeSpeedInBatch = true,
            supportsNativeSpeedInStream = true
        )
        val mmOverflow = TtsProviderConfig(id = "mm", type = ProviderType.MINIMAX, name = "MM", speed = 2.5f)
        val mmPlan = mmOverflow.resolveSpeed(isStreaming = false, capabilities = mmCaps)
        assertEquals(2.0f, mmPlan.upstreamSpeed, 0.01f)
        assertEquals(1.25f, mmPlan.clientSonicFactor, 0.01f)
        assertTrue(mmPlan.needsClientSonic)

        val geminiCaps = ProviderCapabilities(
            supportsNativePcmStreaming = true,
            supportsNativeSpeedInBatch = false,
            supportsNativeSpeedInStream = false
        )
        val gemPlan = TtsProviderConfig(id = "gem", type = ProviderType.GEMINI, name = "Gem", speed = 1.6f, pitch = 1.25f)
            .resolveSpeed(isStreaming = true, capabilities = geminiCaps)
        assertEquals(1.0f, gemPlan.upstreamSpeed, 0.01f)
        assertEquals(1.6f, gemPlan.clientSonicFactor, 0.01f)
        assertEquals(1.25f, gemPlan.clientSonicPitch, 0.01f)
        assertTrue(gemPlan.needsClientSonic)

        val azSpec = ProviderDescriptorRegistry.descriptorFor(ProviderType.AZURE).acousticSpec
        assertEquals(3.0f, azSpec.speedSliderRange.endInclusive, 0.01f)
        assertFalse(AcousticMapper.supportsNativePitch(ProviderType.DOUBAO, "zh_female_shuangkuaisisi_moon_bigtts"))
        assertTrue(AcousticMapper.supportsNativePitch(ProviderType.DOUBAO, "BV001_streaming"))

        assertEquals("-50Hz", AcousticMapper.edgeSsmlPitchHz(0.2f))
        assertEquals("+50Hz", AcousticMapper.edgeSsmlPitchHz(1.9f))
        assertEquals("-50%", AcousticMapper.azureSsmlPitchPct(0.1f))
        assertEquals("+50%", AcousticMapper.azureSsmlPitchPct(2.0f))
        assertEquals(-12, AcousticMapper.minimaxPitchSemitones(0.5f))
        assertEquals(0, AcousticMapper.minimaxPitchSemitones(1.0f))
        assertEquals(12, AcousticMapper.minimaxPitchSemitones(1.5f))
        assertEquals(null, AcousticMapper.doubaoPitchRatio(1.4f, "zh_female_shuangkuaisisi_moon_bigtts"))
        assertEquals(1.4f, AcousticMapper.doubaoPitchRatio(1.4f, "BV001_streaming")!!, 0.01f)
    }

    @Test
    fun `legacy GlobalSettings json with removed fields deserializes cleanly`() {
        val legacyJson = """
            {
              "activeProviderId": "edge_tts_xiaoxiao",
              "isFloatingSubtitleEnabled": true,
              "audioProcessingMode": "WARM",
              "isAcronymNormalizationEnabled": true,
              "floatingSubtitleNovelPreset": "PARCHMENT",
              "ultraLowLatencyMode": true
            }
        """.trimIndent()
        val decoded = json.decodeFromString<GlobalSettings>(legacyJson)
        assertEquals(-1, decoded.floatingSubtitleY)
        assertFalse(decoded.isNumberNormalizationEnabled)
        assertTrue(decoded.isFloatingSubtitleEnabled)
    }
}
