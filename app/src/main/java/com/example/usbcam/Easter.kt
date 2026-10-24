package com.example.usbcam

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast

object Easter {
    private const val HOLD_ON_MS = 5000L
    private const val HOLD_OFF_MS = 3000L

    private val lines = listOf(
        "🔍 Tryb inspektora SKT: ON. ",
        "🕵️ Sprawdzam ASL'e, OLS'e i BSO pod turbiną",
        "🔦 Tryb endoskopu: ON. Zaglądamy w każdą dziurkę",
        "🐞 Wykryto śmierdziela robaka.",
        "🧰 Inspektor Filipek melduje się!"
    )
    private var tries = 0

    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity, root: View) {
        val gallery = root.findViewById<Button>(R.id.btnGallery)
        val parent = root.findViewById<View>(R.id.cameraContainer).parent as ViewGroup
        val overlay = CrosshairView(activity)
        overlay.visibility = View.GONE
        parent.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val h = Handler(Looper.getMainLooper())
        var fired = false

        val toggle = Runnable {
            fired = true
            val on = overlay.visibility != View.VISIBLE
            overlay.visibility = if (on) View.VISIBLE else View.GONE
            val msg = if (on) lines[tries++ % lines.size] else "Tryb inspektora: OFF"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            gallery.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        // krotkie dotkniecie nadal otwiera galerie; przytrzymanie 5 s wlacza, 3 s wylacza
        gallery.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    val need = if (overlay.visibility == View.VISIBLE) HOLD_OFF_MS else HOLD_ON_MS
                    h.postDelayed(toggle, need)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(toggle)
                    if (fired) {
                        v.isPressed = false
                        true // po easter eggu nie otwieraj galerii
                    } else false
                }
                else -> false
            }
        }
    }
}

private class CrosshairView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 255, 140)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r1 = 28f * d
        canvas.drawCircle(cx, cy, r1, p)
        canvas.drawCircle(cx, cy, 72f * d, p)
        canvas.drawLine(0f, cy, cx - r1, cy, p)
        canvas.drawLine(cx + r1, cy, w, cy, p)
        canvas.drawLine(cx, 0f, cx, cy - r1, p)
        canvas.drawLine(cx, cy + r1, cx, h, p)
        val step = 24f * d
        val tick = 6f * d
        var x = step
        while (x < w / 2f) {
            canvas.drawLine(cx + x, cy - tick, cx + x, cy + tick, p)
            canvas.drawLine(cx - x, cy - tick, cx - x, cy + tick, p)
            x += step
        }
        var y = step
        while (y < h / 2f) {
            canvas.drawLine(cx - tick, cy + y, cx + tick, cy + y, p)
            canvas.drawLine(cx - tick, cy - y, cx + tick, cy - y, p)
            y += step
        }
    }
}
