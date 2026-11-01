package com.jass.autotapper

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

class TargetOverlayService : Service() {
    private lateinit var wm: WindowManager
    private val views = mutableListOf<View>()
    private var controlView: TextView? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) ?: false
            setControlRunning(running)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(AutoTapAccessibilityService.ACTION_CONTROL_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            rebuildOverlays()
        } else if (views.isEmpty()) {
            rebuildOverlays()
        }
        return START_STICKY
    }

    private fun rebuildOverlays() {
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
        controlView = null

        val prefs = getSharedPreferences("targets", MODE_PRIVATE)
        addTarget("TAP", "tap", 0.28f, 0.55f)
        addTarget("", "detect", 0.72f, 0.55f)
        addFloatingControl(prefs.getBoolean("control_running", false))
    }

    private fun addTarget(label: String, key: String, fx: Float, fy: Float) {
        val prefs = getSharedPreferences("targets", MODE_PRIVATE)
        val sizeDp = prefs.getInt(key + "_size", 76).coerceIn(44, 140)
        val sizePx = dp(sizeDp)
        val color = prefs.getInt(key + "_color", if (key == "tap") DEFAULT_TAP_COLOR else DEFAULT_DETECT_COLOR)
        val shape = prefs.getString(key + "_shape", "circle") ?: "circle"

        val view = TextView(this).apply {
            text = label
            textSize = if (key == "tap") 12f else 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeOutline(color, shape, sizePx)
            elevation = dp(8).toFloat()
            alpha = 0.94f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(key + "_x", (resources.displayMetrics.widthPixels * fx).toInt())
                .let { (it - sizePx / 2).coerceAtLeast(0) }
            y = prefs.getInt(key + "_y", (resources.displayMetrics.heightPixels * fy).toInt())
                .let { (it - sizePx / 2).coerceAtLeast(0) }
        }

        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (originX + event.rawX - downX).toInt().coerceAtLeast(0)
                    params.y = (originY + event.rawY - downY).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(view, params) }
                    saveCenter(key, params, sizePx)
                    true
                }
                else -> true
            }
        }

        wm.addView(view, params)
        views.add(view)
        saveCenter(key, params, sizePx)
    }

    private fun addFloatingControl(initiallyRunning: Boolean) {
        val prefs = getSharedPreferences("targets", MODE_PRIVATE)
        val sizePx = dp(58)
        val button = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1BBE83.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("control_x", resources.displayMetrics.widthPixels - sizePx - dp(20)).coerceAtLeast(0)
            y = prefs.getInt("control_y", dp(150)).coerceAtLeast(0)
        }

        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragged = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(7) || abs(dy) > dp(7)) dragged = true
                    if (dragged) {
                        params.x = (originX + dx).toInt().coerceAtLeast(0)
                        params.y = (originY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs.edit().putInt("control_x", params.x).putInt("control_y", params.y).apply()
                    } else {
                        toggleCurrentMode()
                    }
                    true
                }
                else -> true
            }
        }

        wm.addView(button, params)
        views.add(button)
        controlView = button
        setControlRunning(initiallyRunning)
    }

    private fun toggleCurrentMode() {
        val mode = getSharedPreferences("targets", MODE_PRIVATE).getString("control_mode", "normal")
        when (mode) {
            "smart" -> {
                val monitor = ScreenMonitorService.instance
                if (monitor == null) {
                    Toast.makeText(this, "Start Smart Detection from the app first", Toast.LENGTH_SHORT).show()
                    return
                }
                setControlRunning(monitor.togglePaused())
            }
            else -> {
                val tapper = AutoTapAccessibilityService.instance
                if (tapper == null) {
                    Toast.makeText(this, "Enable Auto Tap Accessibility first", Toast.LENGTH_SHORT).show()
                    return
                }
                setControlRunning(tapper.toggleAutoClick())
            }
        }
    }

    private fun setControlRunning(running: Boolean) {
        controlView?.text = if (running) "Ⅱ" else "▶"
        controlView?.alpha = if (running) 1f else 0.82f
    }

    private fun makeOutline(color: Int, style: String, sizePx: Int) =
        GradientDrawable().apply {
            this.shape = if (style == "circle") GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = if (style == "rounded") sizePx * 0.22f else 0f
            setColor(0x44303030)
            setStroke(dp(3), color)
        }

    private fun saveCenter(key: String, params: WindowManager.LayoutParams, sizePx: Int) {
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putInt(key + "_x", params.x + sizePx / 2)
            .putInt(key + "_y", params.y + sizePx / 2)
            .apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
        controlView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_REFRESH = "com.jass.autotapper.REFRESH_OVERLAYS"
        private const val DEFAULT_TAP_COLOR = 0xFFFFB300.toInt()
        private const val DEFAULT_DETECT_COLOR = 0xFF00C982.toInt()
    }
}
