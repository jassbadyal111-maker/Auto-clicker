package com.jass.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var loopRunning = false
    @Volatile private var loopPaused = false
    private var intervalMs = 500L
    private var stopAtMs = Long.MAX_VALUE

    private val clickLoop = object : Runnable {
        override fun run() {
            if (!loopRunning || loopPaused) return
            if (System.currentTimeMillis() >= stopAtMs) {
                stopAutoClick()
                return
            }
            val prefs = getSharedPreferences("targets", MODE_PRIVATE)
            val x = prefs.getInt("tap_x", resources.displayMetrics.widthPixels * 28 / 100).toFloat()
            val y = prefs.getInt("tap_y", resources.displayMetrics.heightPixels * 55 / 100).toFloat()
            tap(x, y)
            handler.postDelayed(this, intervalMs.coerceAtLeast(60L))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { pauseAutoClick() }

    fun tap(x: Float, y: Float, callback: (() -> Unit)? = null): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke() }
        }, handler)
    }

    fun startAutoClick(interval: Long, durationMs: Long) {
        handler.removeCallbacks(clickLoop)
        intervalMs = interval.coerceAtLeast(60L)
        stopAtMs = if (durationMs > 0L) System.currentTimeMillis() + durationMs else Long.MAX_VALUE
        loopRunning = true
        loopPaused = false
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putBoolean("control_running", true)
            .putBoolean("control_paused", false)
            .putString("control_mode", "normal")
            .apply()
        publishState()
        handler.post(clickLoop)
    }

    /** Starts the saved loop when stopped; otherwise toggles pause/resume. */
    fun toggleAutoClick(): Boolean {
        if (!loopRunning) {
            val prefs = getSharedPreferences("targets", MODE_PRIVATE)
            val interval = prefs.getLong("normal_interval", 500L)
            val duration = prefs.getLong("normal_duration_ms", 600_000L)
            startAutoClick(interval, duration)
            return true
        }
        if (loopPaused) {
            loopPaused = false
            getSharedPreferences("targets", MODE_PRIVATE).edit().putBoolean("control_paused", false).apply()
            handler.post(clickLoop)
        } else {
            loopPaused = true
            handler.removeCallbacks(clickLoop)
            getSharedPreferences("targets", MODE_PRIVATE).edit().putBoolean("control_paused", true).apply()
        }
        publishState()
        return !loopPaused
    }

    fun pauseAutoClick() {
        if (!loopRunning || loopPaused) return
        loopPaused = true
        handler.removeCallbacks(clickLoop)
        getSharedPreferences("targets", MODE_PRIVATE).edit().putBoolean("control_paused", true).apply()
        publishState()
    }

    fun stopAutoClick() {
        handler.removeCallbacks(clickLoop)
        loopRunning = false
        loopPaused = false
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putBoolean("control_running", false)
            .putBoolean("control_paused", false)
            .apply()
        publishState()
    }

    private fun publishState() {
        sendBroadcast(Intent(ACTION_CONTROL_STATE).setPackage(packageName)
            .putExtra("running", loopRunning && !loopPaused)
            .putExtra("active", loopRunning))
    }

    companion object {
        const val ACTION_CONTROL_STATE = "com.jass.autotapper.CONTROL_STATE"
        @Volatile var instance: AutoTapAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        stopAutoClick()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
