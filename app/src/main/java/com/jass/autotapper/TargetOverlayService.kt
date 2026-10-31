package com.jass.autotapper

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class TargetOverlayService : Service() {
    private lateinit var wm: WindowManager
    private val views = mutableListOf<View>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (views.isEmpty()) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            addTarget("TAP", 0.28f, 0.55f, 0xFFFFB300.toInt())
            addTarget("1", 0.72f, 0.55f, 0xFF00C982.toInt())
        }
        return START_STICKY
    }
    private fun addTarget(label: String, fx: Float, fy: Float, color: Int) {
        val v = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55303030); setStroke(4, color) }
            elevation = 12f
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val p = WindowManager.LayoutParams(76, 76, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (resources.displayMetrics.widthPixels * fx).toInt()
        p.y = (resources.displayMetrics.heightPixels * fy).toInt()
        var sx = 0; var sy = 0; var ox = 0; var oy = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX.toInt(); sy = e.rawY.toInt(); ox = p.x; oy = p.y; true }
                MotionEvent.ACTION_MOVE -> { p.x = ox + e.rawX.toInt() - sx; p.y = oy + e.rawY.toInt() - sy; wm.updateViewLayout(v, p); savePosition(label, p); true }
                else -> true
            }
        }
        wm.addView(v, p); views.add(v); savePosition(label, p)
    }
    private fun savePosition(label: String, p: WindowManager.LayoutParams) {\n        val key = if (label == "TAP") "tap" else "detect"\n        getSharedPreferences("targets", MODE_PRIVATE).edit().putInt(key + "_x", p.x + 38).putInt(key + "_y", p.y + 38).apply()\n    }\n    override fun onDestroy() { views.forEach { runCatching { wm.removeView(it) } }; views.clear(); super.onDestroy() }
}
