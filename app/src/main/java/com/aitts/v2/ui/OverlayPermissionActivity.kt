package com.aitts.v2.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.aitts.v2.AiTtsApplication
import com.aitts.v2.service.PlaybackNotificationController

/**
 * Translucent trampoline Activity allowing users to grant overlay permission from a notification action
 * without hitting Android 10+ background activity launch restrictions.
 */
class OverlayPermissionActivity : Activity() {

    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AiTtsApplication
        val prefs = app.container.preferences
        prefs.updateSettings(prefs.settingsFlow.value.copy(isFloatingSubtitleEnabled = true))

        if (Settings.canDrawOverlays(this)) {
            app.container.subtitleOverlay.show()
            PlaybackNotificationController.refresh(this, true)
            Toast.makeText(this, "前台小说字幕已开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        requested = true
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure {
            Toast.makeText(this, "请前往系统设置授予悬浮窗权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!requested) return
        val app = application as AiTtsApplication
        val prefs = app.container.preferences
        val cur = prefs.settingsFlow.value
        if (Settings.canDrawOverlays(this)) {
            prefs.updateSettings(cur.copy(isFloatingSubtitleEnabled = true))
            app.container.subtitleOverlay.show()
            PlaybackNotificationController.refresh(this, true)
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            prefs.updateSettings(cur.copy(isFloatingSubtitleEnabled = false))
            Toast.makeText(this, "未授予悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
