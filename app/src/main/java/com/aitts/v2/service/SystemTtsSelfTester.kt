package com.aitts.v2.service

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class SelfTestResult(
    val success: Boolean,
    val message: String
)

/**
 * End-to-end verification binding Android's official [TextToSpeech] client
 * to confirm OS routing, service callback, and playback pipeline actually work.
 */
object SystemTtsSelfTester {

    suspend fun runSelfTest(context: Context): SelfTestResult {
        val defaultPkg = runCatching {
            Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        }.getOrNull().orEmpty()

        val result = CompletableDeferred<SelfTestResult>()
        var tts: TextToSpeech? = null
        val appCtx = context.applicationContext

        try {
            tts = TextToSpeech(appCtx, { status ->
                val active = tts
                if (status != TextToSpeech.SUCCESS || active == null) {
                    result.complete(SelfTestResult(false, "系统 TTS 初始化失败 (status=$status)"))
                    return@TextToSpeech
                }
                active.language = Locale.CHINESE
                active.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        val suffix = if (defaultPkg == appCtx.packageName) "（已设为系统默认引擎）" else "（当前绑定引擎）"
                        result.complete(SelfTestResult(true, "系统 TTS 链路实测通畅 $suffix"))
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        result.complete(SelfTestResult(false, "系统 TTS 合成回调报错"))
                    }
                })
                val params = Bundle()
                val code = active.speak("AI 语音引擎系统链路测试正常", TextToSpeech.QUEUE_FLUSH, params, "self_test")
                if (code != TextToSpeech.SUCCESS) {
                    result.complete(SelfTestResult(false, "TextToSpeech.speak 返回失败 ($code)"))
                }
            }, appCtx.packageName)

            return withTimeoutOrNull(18_000L) { result.await() }
                ?: SelfTestResult(false, "系统链路自检超时（请检查网络或当前启用引擎）")
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }
}
