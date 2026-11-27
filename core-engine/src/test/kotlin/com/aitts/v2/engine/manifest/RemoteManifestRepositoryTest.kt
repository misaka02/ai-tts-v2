package com.aitts.v2.engine.manifest

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.DiscoveryCatalog
import com.aitts.v2.engine.provider.DiscoveryDiskCache
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteManifestRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var httpManager: HttpClientManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpManager = HttpClientManager()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `remote manifest refresh updates endpoints, gecVersion, and persists cache`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "version": 9,
                      "updatedAt": "2026-12-01",
                      "edgeGecVersion": "1-145.0.0.1",
                      "defaultEndpoints": {
                        "OPENAI_COMPATIBLE": "https://api.openai.com/v2/audio/speech",
                        "GEMINI": "https://evil.attacker.com/steal"
                      },
                      "offlineModels": [],
                      "presetVoices": {}
                    }
                    """.trimIndent()
                )
        )

        val cacheFile = File(tempFolder.newFolder("manifest"), "manifest.json")
        val repo = RemoteManifestRepository(cacheFile, httpManager)
        val res = repo.refresh(server.url("/catalog/manifest.json").toString())

        assertTrue(res.isSuccess)
        assertEquals("1-145.0.0.1", repo.edgeGecVersion())
        assertEquals("https://api.openai.com/v2/audio/speech", repo.defaultEndpointFor(ProviderType.OPENAI_COMPATIBLE))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            repo.defaultEndpointFor(ProviderType.GEMINI)
        )

        val reloaded = RemoteManifestRepository(cacheFile, httpManager)
        assertEquals(9, reloaded.current().version)
        assertEquals("1-145.0.0.1", reloaded.edgeGecVersion())
    }

    @Test
    fun `manifest sanitizes path traversal model ids and installer blocks escape`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "version": 10,
                      "offlineModels": [
                        {
                          "id": "../escape",
                          "name": "Evil",
                          "arch": "VITS",
                          "category": "test",
                          "sizeMb": 1,
                          "description": "evil",
                          "speakerCount": 1,
                          "sampleRate": 22050,
                          "defaultVoiceId": "spk_0",
                          "speakers": ["spk_0"],
                          "githubUrl": "https://example.com/a.tar.bz2",
                          "mirrorUrl": ""
                        },
                        {
                          "id": "safe-model-v1",
                          "name": "Safe",
                          "arch": "VITS",
                          "category": "test",
                          "sizeMb": 10,
                          "description": "safe",
                          "speakerCount": 1,
                          "sampleRate": 22050,
                          "defaultVoiceId": "spk_0",
                          "speakers": ["spk_0"],
                          "githubUrl": "https://example.com/safe.tar.bz2",
                          "mirrorUrl": ""
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val cacheFile = File(tempFolder.newFolder("manifest_sec"), "manifest.json")
        val repo = RemoteManifestRepository(cacheFile, httpManager)
        val res = repo.refresh(server.url("/catalog/manifest.json").toString())
        assertTrue(res.isSuccess)
        assertEquals(listOf("safe-model-v1"), repo.offlineModels().map { it.id })

        val installer = OfflineModelInstaller(tempFolder.newFolder("models"), httpManager)
        assertEquals(null, OfflineModelInstaller.sanitizeModelId("../escape"))
        val failure = kotlin.runCatching { installer.getModelDir("../escape") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `discovery cache isolates entries across different credentials`() {
        val cache = DiscoveryDiskCache(tempFolder.newFolder("disc_cache"))
        val catA = DiscoveryCatalog(voices = listOf(VoiceModel("v_a", "Account A")), fromRemote = true)
        val catB = DiscoveryCatalog(voices = listOf(VoiceModel("v_b", "Account B")), fromRemote = true)

        cache.put(ProviderType.FISH_AUDIO, "https://api.fish.audio/v1/tts", "speech-1.5", catA, "key_a|")
        cache.put(ProviderType.FISH_AUDIO, "https://api.fish.audio/v1/tts", "speech-1.5", catB, "key_b|")

        assertEquals("v_a", cache.get(ProviderType.FISH_AUDIO, "https://api.fish.audio/v1/tts", "speech-1.5", "key_a|")?.voices?.firstOrNull()?.id)
        assertEquals("v_b", cache.get(ProviderType.FISH_AUDIO, "https://api.fish.audio/v1/tts", "speech-1.5", "key_b|")?.voices?.firstOrNull()?.id)
    }
}
