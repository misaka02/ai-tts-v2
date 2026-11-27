package com.aitts.v2.service

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.SubtitleAlignment

/**
 * Minimalist foreground novel subtitle overlay.
 * Automatically adapts to system dark/light theme with drag-position memory, tap-to-expand, and lock mode.
 */
class FloatingSubtitleOverlay(
    private val appContext: Context,
    private val settingsProvider: () -> GlobalSettings,
    private val onClosedByUser: () -> Unit = {},
    private val onPositionChanged: (Int) -> Unit = {}
) {
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var containerView: LinearLayout? = null
    private var textView: TextView? = null
    private var closeBtn: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var attached = false
    private var expanded = false
    private var lastText = ""

    fun isShowing(): Boolean = attached

    fun updateText(text: String) {
        lastText = text
        handler.post {
            val settings = settingsProvider()
            ensureView(settings)
            textView?.text = text.ifBlank { "准备播报..." }
            if (!attached && text.isNotBlank()) attach()
        }
    }

    fun show() {
        handler.post {
            ensureView(settingsProvider())
            if (lastText.isNotBlank()) textView?.text = lastText
            if (!attached) attach()
        }
    }

    fun hide() {
        handler.post { detach() }
    }

    fun applySettings(settings: GlobalSettings) {
        handler.post {
            val container = containerView ?: return@post
            val alpha = (settings.floatingSubtitleOpacity.coerceIn(0f, 1f) * 255).toInt()
            val isSysDark = (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val bgBase = if (isSysDark) Color.parseColor("#181614") else Color.parseColor("#FDFBF7")
            val fgColor = if (isSysDark) Color.parseColor("#EFEAE0") else Color.parseColor("#1B1917")

            if (alpha <= 5) {
                container.background = null
                container.elevation = 0f
            } else {
                val bgColor = Color.argb(alpha, Color.red(bgBase), Color.green(bgBase), Color.blue(bgBase))
                val stroke = if (isSysDark) {
                    Color.argb((alpha * 0.30f).toInt(), 239, 234, 224)
                } else {
                    Color.argb((alpha * 0.25f).toInt(), 27, 25, 23)
                }
                container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14f).toFloat()
                    setColor(bgColor)
                    setStroke(dp(1f), stroke)
                }
                container.elevation = dp(4f).toFloat()
            }

            textView?.apply {
                setTextColor(fgColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.floatingSubtitleFontSize.coerceIn(11, 26).toFloat())
                gravity = if (settings.floatingSubtitleAlignment == SubtitleAlignment.CENTER) {
                    Gravity.CENTER
                } else {
                    Gravity.START or Gravity.CENTER_VERTICAL
                }
                maxLines = if (expanded) 12 else 3
                val shadowColor = if (isSysDark) Color.parseColor("#CC000000") else Color.parseColor("#99FFFFFF")
                setShadowLayer(dp(2.5f).toFloat(), 0f, dp(1f).toFloat(), shadowColor)
            }
            closeBtn?.setColorFilter(if (isSysDark) Color.parseColor("#9C9387") else Color.parseColor("#675F55"))
        }
    }

    fun destroy() {
        hide()
        containerView = null
        textView = null
        closeBtn = null
        layoutParams = null
    }

    private fun ensureView(settings: GlobalSettings) {
        if (containerView != null) {
            applySettings(settings)
            return
        }
        val metrics = appContext.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.92f).toInt(), metrics.widthPixels - dp(24f))
        val defaultY = (metrics.heightPixels - dp(180f)).coerceAtLeast(dp(60f))
        val initialY = if (settings.floatingSubtitleY >= 0) {
            settings.floatingSubtitleY.coerceIn(dp(20f), (metrics.heightPixels - dp(60f)).coerceAtLeast(dp(60f)))
        } else {
            defaultY
        }
        layoutParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = initialY
        }

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(8f), dp(10f))
        }
        containerView = container

        val tv = TextView(appContext).apply {
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2f).toFloat(), 1.2f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = lastText.ifBlank { "AI 听书字幕就绪" }
        }
        textView = tv

        val close = ImageView(appContext).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(4f) }
            setOnClickListener {
                hide()
                onClosedByUser()
                appContext.sendBroadcast(
                    Intent(PlaybackNotificationController.ACTION_TOGGLE_SUBTITLE)
                        .setPackage(appContext.packageName)
                        .putExtra(PlaybackNotificationController.EXTRA_FORCE_DISABLE, true)
                )
            }
        }
        closeBtn = close

        container.addView(tv)
        container.addView(close)
        SubtitleTouchHandler.attach(
            appContext = appContext,
            windowManager = windowManager,
            container = container,
            textView = tv,
            interactiveViews = listOfNotNull(closeBtn),
            layoutParamsProvider = { layoutParams },
            isAttachedProvider = { attached },
            settingsProvider = settingsProvider,
            onToggleExpand = { expanded = it },
            onDragEnd = onPositionChanged,
            dpFn = ::dp
        )
        applySettings(settings)
    }

    private fun attach() {
        val wm = windowManager ?: return
        val view = containerView ?: return
        val lp = layoutParams ?: return
        if (!Settings.canDrawOverlays(appContext)) return
        runCatching {
            if (view.parent != null) wm.removeViewImmediate(view)
            wm.addView(view, lp)
            attached = true
        }.onFailure { attached = false }
    }

    private fun detach() {
        val wm = windowManager ?: return
        val view = containerView ?: return
        runCatching { if (attached || view.parent != null) wm.removeViewImmediate(view) }
        attached = false
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, appContext.resources.displayMetrics).toInt()
}
