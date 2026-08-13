package com.timelimittracker.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentOverlay: FrameLayout? = null

    val isShowing: Boolean get() = currentOverlay != null

    fun show(message: String, onDismiss: () -> Unit) {
        if (isShowing) hide()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            // Allow button to receive touch but block everything else
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }

        val overlay = buildOverlayView(message) {
            hide()
            onDismiss()
        }

        try {
            windowManager.addView(overlay, params)
            currentOverlay = overlay
        } catch (e: WindowManager.BadTokenException) {
            Log.e("OverlayManager", "Cannot show overlay — SYSTEM_ALERT_WINDOW revoked", e)
        }
    }

    fun hide() {
        val overlay = currentOverlay ?: return
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            Log.e("OverlayManager", "Error removing overlay", e)
        }
        currentOverlay = null
    }

    private fun buildOverlayView(message: String, onDismiss: () -> Unit): FrameLayout {
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(204, 0, 0, 0)) // 80% black
            isClickable = true
            isFocusable = true
        }

        val cardPaddingPx = dpToPx(24)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dpToPx(12).toFloat()
            }
        }

        val messageView = TextView(context).apply {
            text = message
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(24))
        }

        val dismissButton = Button(context).apply {
            text = "Dismiss"
            textSize = 16f
            setOnClickListener { onDismiss() }
        }

        card.addView(messageView)
        card.addView(dismissButton)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            val margin = dpToPx(32)
            setMargins(margin, margin, margin, margin)
        }

        scrim.addView(card, cardParams)
        return scrim
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
