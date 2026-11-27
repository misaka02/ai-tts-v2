package com.aitts.v2.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class AppPermissionState(
    val ignoringBatteryOptimizations: Boolean,
    val notificationGranted: Boolean,
    val overlayGranted: Boolean,
    val isSystemDefaultTts: Boolean
)

object PermissionHelper {

    fun check(context: Context): AppPermissionState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val defaultTts = runCatching {
            Settings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
        }.getOrDefault(false)
        return AppPermissionState(
            ignoringBatteryOptimizations = batteryOk,
            notificationGranted = notifOk,
            overlayGranted = Settings.canDrawOverlays(context),
            isSystemDefaultTts = defaultTts
        )
    }

    fun requestNotificationIfNeeded(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
            }
        }
    }

    fun openIgnoreBatteryOptimizations(context: Context) {
        val intent = runCatching {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrElse {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openSystemTtsSettings(context: Context) {
        val actions = listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in actions) {
            val started = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    fun openOverlayPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
