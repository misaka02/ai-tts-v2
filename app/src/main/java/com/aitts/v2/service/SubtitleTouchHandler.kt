package com.aitts.v2.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aitts.v2.model.GlobalSettings
import kotlin.math.abs

internal object SubtitleTouchHandler {

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        appContext: Context,
        windowManager: WindowManager?,
        container: LinearLayout,
        textView: TextView,
        interactiveViews: List<View>,
        layoutParamsProvider: () -> WindowManager.LayoutParams?,
        isAttachedProvider: () -> Boolean,
        settingsProvider: () -> GlobalSettings,
        onToggleExpand: (Boolean) -> Unit,
        onDragEnd: (Int) -> Unit = {},
        dpFn: (Float) -> Int
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var expanded = false
        val rect = Rect()

        container.setOnTouchListener { _, event ->
            val lp = layoutParamsProvider() ?: return@setOnTouchListener false
            for (child in interactiveViews) {
                if (child.visibility == View.VISIBLE) {
                    child.getHitRect(rect)
                    rect.inset(-dpFn(6f), -dpFn(6f))
                    if (rect.contains(event.x.toInt(), event.y.toInt())) return@setOnTouchListener false
                }
            }
            val current = settingsProvider()
            if (current.floatingSubtitleLockPosition) {
                if (event.action == MotionEvent.ACTION_UP) {
                    expanded = !expanded
                    textView.maxLines = if (expanded) 14 else current.floatingSubtitleMaxLines.coerceIn(1, 8)
                    onToggleExpand(expanded)
                }
                return@setOnTouchListener true
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        val dm = appContext.resources.displayMetrics
                        val w = container.width.takeIf { it > 0 } ?: lp.width
                        val h = container.height.takeIf { it > 0 } ?: dpFn(60f)
                        lp.x = (startX + dx).coerceIn(0, maxOf(0, dm.widthPixels - w))
                        lp.y = (startY + dy).coerceIn(dpFn(20f), maxOf(dpFn(20f), dm.heightPixels - h))
                        if (isAttachedProvider()) runCatching { windowManager?.updateViewLayout(container, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        textView.maxLines = if (expanded) 14 else current.floatingSubtitleMaxLines.coerceIn(1, 8)
                        onToggleExpand(expanded)
                    } else {
                        onDragEnd(lp.y)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
