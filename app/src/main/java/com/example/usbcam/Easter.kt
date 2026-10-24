package com.example.usbcam

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast

object Easter {
    private val lines = listOf(
        "🔍 Tryb inspektora SKT: ON. Żaden wyciek",
        "🕵️ Sprawdzam ASL'e, OLS'e i BSO pod turbiną",
        "🔦 Tryb endoskopu: ON. Zaglądamy w każdą dziurkę",
        "🐞 Wykryto śmierdziela robaka.",
        "🧰 Inspektor Filipek melduje się!"
    )
    private var tries = 0

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
        gallery.setOnLongClickListener {
            val on = overlay.visibility != View.VISIBLE
            overlay.visibility = if (on) View.VISIBLE else View.GONE
            val msg = if (on) lines[tries++ % lines.size] else "Tryb inspektora: OFF"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            true
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
