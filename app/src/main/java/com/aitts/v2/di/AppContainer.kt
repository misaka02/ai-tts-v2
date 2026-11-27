package com.aitts.v2.di

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aitts.v2.audio.AndroidAudioDecoder
import com.aitts.v2.audio.StreamingAudioTrackPlayer
import com.aitts.v2.data.AppPreferencesRepository
import com.aitts.v2.data.BackupManager
import com.aitts.v2.data.QuoteRepository
import com.aitts.v2.data.SecretStore
import com.aitts.v2.engine.audio.AudioPipeline
import com.aitts.v2.engine.cache.AudioDiskCache
import com.aitts.v2.engine.logging.EngineLogger
import com.aitts.v2.engine.manifest.RemoteManifestRepository
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.DiscoveryDiskCache
import com.aitts.v2.engine.provider.TtsProviderRegistry
import com.aitts.v2.engine.synthesis.SynthesisOrchestrator
import com.aitts.v2.offline.PlatformOfflineBackend
import com.aitts.v2.reader.BookImportManager
import com.aitts.v2.reader.BookLibraryRepository
import com.aitts.v2.reader.EpubImageLoader
import com.aitts.v2.reader.ReaderTtsController
import com.aitts.v2.service.FloatingSubtitleOverlay
import com.aitts.v2.service.SleepTimerController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/**
 * Clean manual dependency container for the entire app.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val logger = EngineLogger(externalSink = { line -> Log.d("AiTtsV2", line) })
    val httpManager = HttpClientManager()
    val secretStore = SecretStore(appContext)
    val preferences = AppPreferencesRepository(appContext, secretStore, httpManager)
    val backupManager = BackupManager(preferences, secretStore)
    val quoteRepository = QuoteRepository(httpManager)

    private val incomingTextChannel = Channel<String>(capacity = 16)
    val incomingTextEvents = incomingTextChannel.receiveAsFlow()

    private val incomingBookUriChannel = Channel<Uri>(capacity = 8)
    val incomingBookUriEvents = incomingBookUriChannel.receiveAsFlow()

    var stopHubPreviewCallback: (() -> Unit)? = null

    fun submitIncomingText(text: String) {
        incomingTextChannel.trySend(text)
    }

    fun submitIncomingBookUri(uri: Uri) {
        incomingBookUriChannel.trySend(uri)
    }

    val diskCache = AudioDiskCache(File(appContext.cacheDir, "tts_audio_cache_v2"))
    val discoveryCache = DiscoveryDiskCache(File(appContext.cacheDir, "tts_discovery_cache"))
    val manifestRepository = RemoteManifestRepository(
        cacheFile = File(appContext.filesDir, "upstream_manifest.json"),
        httpManager = httpManager
    )

    val audioDecoder = AndroidAudioDecoder()
    val audioPipeline = AudioPipeline(audioDecoder)
    val previewPlayer = StreamingAudioTrackPlayer()

    val offlineModelsDir: File = appContext.getExternalFilesDir("offline_models")
        ?: File(appContext.filesDir, "offline_models")
    val modelInstaller = OfflineModelInstaller(offlineModelsDir, httpManager)
    val offlineIncluded: Boolean = PlatformOfflineBackend.isNativeRuntimeBundled()

    val providerRegistry = TtsProviderRegistry(
        httpManager = httpManager,
        offlineProvider = PlatformOfflineBackend.createProvider(appContext, modelInstaller),
        gecVersionProvider = { manifestRepository.edgeGecVersion() }
    )

    val orchestrator = SynthesisOrchestrator(
        registry = providerRegistry,
        audioPipeline = audioPipeline,
        diskCache = diskCache,
        httpManager = httpManager,
        logger = logger
    )

    val sleepTimer = SleepTimerController(appContext)

    val subtitleOverlay: FloatingSubtitleOverlay by lazy {
        FloatingSubtitleOverlay(
            appContext = appContext,
            settingsProvider = { preferences.settingsFlow.value },
            onClosedByUser = {
                val cur = preferences.settingsFlow.value
                if (cur.isFloatingSubtitleEnabled) {
                    preferences.updateSettings(cur.copy(isFloatingSubtitleEnabled = false))
                }
            },
            onPositionChanged = { y ->
                val cur = preferences.settingsFlow.value
                preferences.updateSettings(cur.copy(floatingSubtitleY = y))
            }
        )
    }

    val bookLibrary = BookLibraryRepository(appContext)
    val bookImportManager = BookImportManager(appContext, bookLibrary)
    val epubImageLoader = EpubImageLoader(bookLibrary)
    val readerTts = ReaderTtsController(
        context = appContext,
        orchestrator = orchestrator,
        previewPlayer = previewPlayer,
        preferences = preferences,
        bookLibrary = bookLibrary,
        sleepTimer = sleepTimer,
        subtitleOverlay = subtitleOverlay,
        onBeforeStart = { stopHubPreviewCallback?.invoke() }
    )
}
