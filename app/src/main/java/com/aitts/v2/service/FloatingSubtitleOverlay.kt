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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.SubtitleAlignment
import com.aitts.v2.model.SubtitleNovelPreset

/**
 * Lightweight foreground novel subtitle overlay.
 * Adapts to system dark/light mode or user-selected paper presets with contrast outline.
 */
class FloatingSubtitleOverlay(
    private val appContext: Context,
    private val settingsProvider: () -> GlobalSettings,
    private val onPresetSelected: (SubtitleNovelPreset) -> Unit
) {
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var containerView: LinearLayout? = null
    private var iconView: ImageView? = null
    private var paletteBtn: ImageView? = null
    private var paletteBar: LinearLayout? = null
    private var textView: TextView? = null
    private var closeBtn: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var attached = false
    private var expanded = false
    private var tempInverted = false
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
            val effectiveDark = when (settings.floatingSubtitleNovelPreset) {
                SubtitleNovelPreset.AUTO -> if (tempInverted) !isSysDark else isSysDark
                SubtitleNovelPreset.NIGHT -> !tempInverted
                else -> tempInverted
            }

            val (bgBase, fgColor) = when (settings.floatingSubtitleNovelPreset) {
                SubtitleNovelPreset.AUTO ->
                    if (effectiveDark) Color.parseColor("#181A20") to Color.parseColor("#F5F5F7")
                    else Color.WHITE to Color.parseColor("#0F172A")
                else -> {
                    val presetBg = settings.floatingSubtitleNovelPreset.bgHex.toInt()
                    val presetFg = settings.floatingSubtitleNovelPreset.textHex.toInt()
                    if (tempInverted) Color.parseColor("#181A20") to Color.parseColor("#F5F5F7") else presetBg to presetFg
                }
            }

            if (alpha <= 5) {
                container.background = null
                container.elevation = 0f
            } else {
                val bgColor = Color.argb(alpha, Color.red(bgBase), Color.green(bgBase), Color.blue(bgBase))
                val stroke = if (effectiveDark) Color.argb((alpha * 0.3f).toInt(), 255, 255, 255)
                else Color.argb((alpha * 0.25f).toInt(), 15, 23, 42)
                container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16f).toFloat()
                    setColor(bgColor)
                    setStroke(dp(1f), stroke)
                }
                container.elevation = dp(6f).toFloat()
            }

            textView?.apply {
                setTextColor(fgColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.floatingSubtitleFontSize.coerceIn(11, 28).toFloat())
                gravity = if (settings.floatingSubtitleAlignment == SubtitleAlignment.CENTER) Gravity.CENTER
                else Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = if (expanded) 14 else settings.floatingSubtitleMaxLines.coerceIn(1, 8)
                val lum = (0.299 * Color.red(fgColor) + 0.587 * Color.green(fgColor) + 0.114 * Color.blue(fgColor)) / 255.0
                if (alpha <= 5 || settings.floatingSubtitleAdaptiveContrast) {
                    val shadowColor = if (lum > 0.5) Color.parseColor("#CC000000") else Color.parseColor("#99FFFFFF")
                    setShadowLayer(dp(3f).toFloat(), 0f, dp(1f).toFloat(), shadowColor)
                } else {
                    setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }
            }

            iconView?.visibility = if (settings.floatingSubtitleShowIcon) View.VISIBLE else View.GONE
            iconView?.setColorFilter(fgColor)
            paletteBtn?.setColorFilter(fgColor)
            closeBtn?.setColorFilter(if (effectiveDark) Color.parseColor("#B0B5C5") else Color.parseColor("#475569"))
        }
    }

    fun destroy() {
        hide()
        containerView = null
        iconView = null
        paletteBtn = null
        paletteBar = null
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
        val width = minOf((metrics.widthPixels * 0.94f).toInt(), metrics.widthPixels - dp(20f))
        layoutParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = (metrics.heightPixels - dp(180f)).coerceAtLeast(dp(60f))
        }

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(8f), dp(8f))
        }
        containerView = container

        val (mainRow, tv) = buildMainRow(container)
        val pRow = buildPaletteRow()
        paletteBar = pRow
        paletteBtn?.setOnClickListener {
            pRow.visibility = if (pRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        container.addView(mainRow)
        container.addView(pRow)
        SubtitleTouchHandler.attach(
            appContext = appContext,
            windowManager = windowManager,
            container = container,
            textView = tv,
            interactiveViews = listOfNotNull(closeBtn, iconView, paletteBtn, pRow),
            layoutParamsProvider = { layoutParams },
            isAttachedProvider = { attached },
            settingsProvider = settingsProvider,
            onToggleExpand = { expanded = it },
            dpFn = ::dp
        )
        applySettings(settings)
    }

    private fun buildMainRow(container: LinearLayout): Pair<LinearLayout, TextView> {
        val mainRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(appContext).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(6f) }
            setOnClickListener {
                tempInverted = !tempInverted
                container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                applySettings(settingsProvider())
            }
        }
        iconView = icon

        val palette = ImageView(appContext).apply {
            setImageResource(android.R.drawable.ic_menu_set_as)
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(6f) }
        }
        paletteBtn = palette

        val tv = TextView(appContext).apply {
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2.5f).toFloat(), 1.2f)
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
                appContext.sendBroadcast(
                    Intent(PlaybackNotificationController.ACTION_TOGGLE_SUBTITLE)
                        .setPackage(appContext.packageName)
                        .putExtra(PlaybackNotificationController.EXTRA_FORCE_DISABLE, true)
                )
            }
        }
        closeBtn = close

        mainRow.addView(icon)
        mainRow.addView(palette)
        mainRow.addView(tv)
        mainRow.addView(close)
        return mainRow to tv
    }

    private fun buildPaletteRow(): LinearLayout = LinearLayout(appContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(0, dp(6f), 0, dp(2f))
        SubtitleNovelPreset.entries.forEach { preset ->
            val chip = TextView(appContext).apply {
                text = preset.displayName.take(3)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(preset.textHex.toInt())
                setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(preset.bgHex.toInt())
                    setStroke(dp(1f), Color.parseColor("#66808080"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    tempInverted = false
                    onPresetSelected(preset)
                    paletteBar?.visibility = View.GONE
                }
            }
            addView(chip)
        }
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
