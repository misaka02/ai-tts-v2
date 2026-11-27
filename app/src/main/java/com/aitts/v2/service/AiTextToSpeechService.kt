package com.aitts.v2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.aitts.v2.AiTtsApplication
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.synthesis.SynthesisInput
import com.aitts.v2.engine.synthesis.SynthesisSink
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Standard Android TextToSpeechService with AudioFocus management and session-scoped cancellation.
 */
class AiTextToSpeechService : TextToSpeechService() {

    private lateinit var container: AppContainer
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val activeSessionId = AtomicReference<String?>(null)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            container.logger.warn("AUDIO", "失去系统音频焦点，停止朗读")
            onStop()
        }
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackNotificationController.ACTION_STOP_TTS -> {
                    activeSessionId.getAndSet(null)?.let { container.orchestrator.cancelSession(it) }
                    PlaybackNotificationController.cancelImmediately(this@AiTextToSpeechService)
                    container.subtitleOverlay.hide()
                }
                PlaybackNotificationController.ACTION_TOGGLE_SUBTITLE -> {
                    val forceDisable = intent.getBooleanExtra(PlaybackNotificationController.EXTRA_FORCE_DISABLE, false)
                    val cur = container.preferences.settingsFlow.value
                    val next = if (forceDisable) false else !cur.isFloatingSubtitleEnabled
                    container.preferences.updateSettings(cur.copy(isFloatingSubtitleEnabled = next))
                    if (next) container.subtitleOverlay.show() else container.subtitleOverlay.hide()
                    PlaybackNotificationController.refresh(this@AiTextToSpeechService, container.subtitleOverlay.isShowing())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as AiTtsApplication).container
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val filter = IntentFilter().apply {
            addAction(PlaybackNotificationController.ACTION_STOP_TTS)
            addAction(PlaybackNotificationController.ACTION_TOGGLE_SUBTITLE)
        }
        ContextCompat.registerReceiver(this, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        container.logger.info("TTS_SVC", "AiTextToSpeechService v2 已就绪")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(actionReceiver) }
        activeSessionId.getAndSet(null)?.let { container.orchestrator.cancelSession(it) }
        abandonFocus()
        PlaybackNotificationController.cancelImmediately(this)
        container.subtitleOverlay.destroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val l = lang?.lowercase(Locale.getDefault()).orEmpty()
        val supported = listOf("zh", "zho", "cmn", "en", "ja", "ko", "yue").any { l.startsWith(it) }
        return if (supported) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        val active = container.preferences.getActiveProvider()
        return if (active.voiceId.startsWith("en-")) arrayOf("eng", "USA", "") else arrayOf("zho", "CHN", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val text = request.charSequenceText?.toString().orEmpty()
        val sessionId = UUID.randomUUID().toString().take(8)
        activeSessionId.set(sessionId)

        if (text.isBlank()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            activeSessionId.compareAndSet(sessionId, null)
            return
        }

        requestFocus()
        val settings = container.preferences.settingsFlow.value
        val activeProvider = resolveProviderForRequest(request.voiceName)
        val input = SynthesisInput(
            rawText = text,
            settings = settings,
            activeConfig = activeProvider,
            allProviders = container.preferences.providersFlow.value,
            rules = container.preferences.rulesFlow.value,
            credentialsLookup = { id -> container.preferences.getCredentials(id) },
            systemSpeed = mapReaderScale(request.speechRate, 0.25f, 3.0f),
            systemPitch = mapReaderScale(request.pitch, 0.5f, 2.0f),
            fadeGainProvider = { container.sleepTimer.getFadeVolumeFactor() }
        )

        val sink = object : SynthesisSink {
            override fun onStart(sampleRate: Int): Boolean =
                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS

            override fun onSegmentStart(segmentIndex: Int, totalSegments: Int, text: String) {
                val live = container.preferences.settingsFlow.value
                if (live.playbackNotificationEnabled) {
                    PlaybackNotificationController.show(
                        this@AiTextToSpeechService,
                        activeProvider.name,
                        activeProvider.voiceId.ifBlank { "默认" },
                        text,
                        container.subtitleOverlay.isShowing()
                    )
                }
                if (live.isFloatingSubtitleEnabled) {
                    container.subtitleOverlay.updateText(text)
                }
            }

            override fun onPcmChunk(pcm16Bytes: ByteArray) {
                callback.audioAvailable(pcm16Bytes, 0, pcm16Bytes.size)
            }

            override fun onDone() {
                callback.done()
            }

            override fun onError(error: Throwable) {
                runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
            }
        }

        try {
            runBlocking { container.orchestrator.execute(sessionId, input, sink) }
        } catch (_: CancellationException) {
            runCatching { callback.done() }
        } catch (_: Throwable) {
            runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        } finally {
            activeSessionId.compareAndSet(sessionId, null)
            abandonFocus()
            if (container.preferences.settingsFlow.value.playbackNotificationEnabled) {
                PlaybackNotificationController.scheduleDelayedDismiss(
                    this,
                    onDismissOverlay = { container.subtitleOverlay.hide() }
                )
            }
        }
    }

    override fun onStop() {
        val sid = activeSessionId.getAndSet(null)
        if (!sid.isNullOrBlank()) {
            container.orchestrator.cancelSession(sid)
        }
        PlaybackNotificationController.scheduleDelayedDismiss(
            this,
            onDismissOverlay = { container.subtitleOverlay.hide() }
        )
    }

    override fun onGetVoices(): MutableList<Voice> {
        val list = mutableListOf<Voice>()
        val active = container.preferences.getActiveProvider()
        for (p in container.preferences.providersFlow.value) {
            val v = Voice(p.name.ifBlank { p.id }, Locale.CHINESE, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, setOf(p.id))
            if (p.id == active.id) list.add(0, v) else list.add(v)
        }
        for (v in PresetVoices.edgeVoices) {
            val loc = runCatching { Locale.forLanguageTag(v.locale) }.getOrDefault(Locale.CHINESE)
            list.add(Voice(v.id, loc, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, setOf("edge")))
        }
        return list
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        container.preferences.getActiveProvider().let { it.name.ifBlank { it.voiceId.ifBlank { "zh-CN-XiaoxiaoNeural" } } }

    private fun resolveProviderForRequest(voiceName: String?): TtsProviderConfig {
        val target = voiceName?.trim().orEmpty()
        val providers = container.preferences.providersFlow.value
        val active = container.preferences.getActiveProvider()
        if (target.isBlank()) return active

        providers.firstOrNull { it.id == target || it.name == target }?.let { return it }

        val isKnownEdgeVoice = PresetVoices.edgeVoices.any { it.id == target } || target.endsWith("Neural")
        if (isKnownEdgeVoice) {
            val edgeCfg = if (active.type == ProviderType.EDGE_TTS) {
                active
            } else {
                providers.firstOrNull { it.type == ProviderType.EDGE_TTS && it.enabled }
                    ?: providers.firstOrNull { it.type == ProviderType.EDGE_TTS }
            }
            if (edgeCfg != null) return edgeCfg.copy(voiceId = target)
        }
        return active
    }

    private fun mapReaderScale(raw: Int, min: Float, max: Float): Float = when {
        raw <= 0 || raw == 10 || raw == 100 -> 1.0f
        raw in 1..30 -> (raw / 10.0f).coerceIn(min, max)
        else -> (raw / 100.0f).coerceIn(min, max)
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
