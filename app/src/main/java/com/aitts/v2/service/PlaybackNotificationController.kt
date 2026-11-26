package com.aitts.v2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.aitts.v2.ui.MainActivity
import com.aitts.v2.ui.OverlayPermissionActivity

/**
 * Single debounced playback notification controller avoiding per-sentence notification flicker.
 */
object PlaybackNotificationController {

    const val CHANNEL_ID = "aitts_v2_playback"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_TTS = "com.aitts.v2.ACTION_STOP_TTS"
    const val ACTION_TOGGLE_SUBTITLE = "com.aitts.v2.ACTION_TOGGLE_SUBTITLE"
    const val EXTRA_FORCE_DISABLE = "extra_force_disable"

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    private var lastProvider = "AI 语音引擎"
    private var lastVoice = "默认"
    private var lastSentence = ""

    fun show(
        context: Context,
        providerName: String,
        voiceId: String,
        sentence: String,
        isSubtitleVisible: Boolean
    ) {
        cancelDelayedDismiss()
        lastProvider = providerName
        lastVoice = voiceId
        lastSentence = sentence

        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(ACTION_STOP_TTS).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hasOverlayPerm = Settings.canDrawOverlays(context)
        val subtitlePending = if (!hasOverlayPerm) {
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, OverlayPermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(ACTION_TOGGLE_SUBTITLE).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val subtitleTitle = when {
            !hasOverlayPerm -> "开启字幕 (需授权)"
            isSubtitleVisible -> "隐藏字幕"
            else -> "开启字幕"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$providerName ($voiceId)")
            .setContentText(sentence.ifBlank { "正在合成..." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(sentence.ifBlank { "正在合成..." }))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_view, subtitleTitle, subtitlePending)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()

        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, notification)
        }
    }

    fun refresh(context: Context, isSubtitleVisible: Boolean) {
        if (lastSentence.isNotBlank()) {
            show(context, lastProvider, lastVoice, lastSentence, isSubtitleVisible)
        }
    }

    fun scheduleDelayedDismiss(context: Context, onDismissOverlay: () -> Unit, delayMs: Long = 5000L) {
        cancelDelayedDismiss()
        val appCtx = context.applicationContext
        val r = Runnable {
            cancelImmediately(appCtx)
            onDismissOverlay()
        }
        dismissRunnable = r
        handler.postDelayed(r, delayMs)
    }

    fun cancelDelayedDismiss() {
        dismissRunnable?.let {
            handler.removeCallbacks(it)
            dismissRunnable = null
        }
    }

    fun cancelImmediately(context: Context) {
        cancelDelayedDismiss()
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "AI TTS 后台朗读", NotificationManager.IMPORTANCE_LOW).apply {
                description = "展示当前朗读状态与控制"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }
}
