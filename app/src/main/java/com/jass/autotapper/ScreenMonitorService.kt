package com.jass.autotapper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMonitorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    @Volatile private var paused = false
    private var previousMatch = false
    private var lastTap = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            display?.release()
            display = null
            publishState("Screen capture stopped", false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (data == null) {
            publishState("Screen permission missing", false)
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            startCapture()
            START_NOT_STICKY
        } catch (error: Exception) {
            publishState("Capture error: ${error.message ?: "unknown"}", false)
            stopSelf()
            START_NOT_STICKY
        }
    }

    fun togglePaused(): Boolean {
        paused = !paused
        previousMatch = false
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putBoolean("control_running", !paused)
            .putBoolean("control_paused", paused)
            .apply()
        publishState(if (paused) "Smart detection paused" else "Smart detection running", !paused)
        return !paused
    }

    fun stopDetection() {
        paused = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val channel = "smart_monitor"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Smart number detection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channel)
            .setContentTitle("Jass Auto Tapper")
            .setContentText("Smart number detection is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startCapture() {
        // Use physical display metrics so OCR coordinates match overlay and Accessibility tap coordinates.
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        thread = HandlerThread("jass-screen-reader").also { it.start() }
        worker = Handler(thread!!.looper)
        val activeProjection = projection ?: error("MediaProjection unavailable")
        activeProjection.registerCallback(projectionCallback, worker!!)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        reader!!.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (paused || !busy.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }

            var crop: Bitmap? = null
            var enlarged: Bitmap? = null
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                image.close()

                val prefs = getSharedPreferences("targets", MODE_PRIVATE)
                val centerX = prefs.getInt("detect_x", width * 72 / 100)
                val centerY = prefs.getInt("detect_y", height * 55 / 100)
                val circleSize = prefs.getInt("detect_size", 76)
                // Read slightly beyond the guide ring, but never include its old "1" label.
                val roiSize = (circleSize * 1.65f).toInt().coerceIn(96, 240)
                val left = (centerX - roiSize / 2).coerceIn(0, full.width - 1)
                val top = (centerY - roiSize / 2).coerceIn(0, height - 1)
                val cropWidth = roiSize.coerceAtMost(full.width - left)
                val cropHeight = roiSize.coerceAtMost(height - top)
                crop = Bitmap.createBitmap(full, left, top, cropWidth, cropHeight)
                full.recycle()
                enlarged = Bitmap.createScaledBitmap(crop!!, cropWidth * 2, cropHeight * 2, true)

                recognizer.process(InputImage.fromBitmap(enlarged!!, 0))
                    .addOnSuccessListener { result ->
                        val found = result.textBlocks.any { block ->
                            block.lines.any { line -> line.text.trim().replace(" ", "") == "1" }
                        }
                        if (!found) {
                            previousMatch = false
                            publishState("Scanning target area…", !paused)
                            return@addOnSuccessListener
                        }

                        val now = System.currentTimeMillis()
                        if (!previousMatch && now - lastTap >= 400L) {
                            val tapper = AutoTapAccessibilityService.instance
                            if (tapper == null) {
                                publishState("Enable Accessibility service to tap", !paused)
                            } else {
                                val tapX = prefs.getInt("tap_x", width * 28 / 100).toFloat()
                                val tapY = prefs.getInt("tap_y", height * 55 / 100).toFloat()
                                val accepted = tapper.tap(tapX, tapY)
                                if (accepted) {
                                    previousMatch = true
                                    lastTap = now
                                    publishState("Number 1 detected • tap sent", true)
                                } else {
                                    publishState("Tap was rejected by Android", true)
                                }
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        publishState("OCR error: ${error.localizedMessage ?: "recognition failed"}", !paused)
                    }
                    .addOnCompleteListener {
                        crop?.recycle()
                        enlarged?.recycle()
                        busy.set(false)
                    }
            } catch (error: Exception) {
                runCatching { image.close() }
                crop?.let { runCatching { it.recycle() } }
                enlarged?.let { runCatching { it.recycle() } }
                busy.set(false)
                publishState("Scan error: ${error.localizedMessage ?: "unknown"}", !paused)
            }
        }, worker)

        display = activeProjection.createVirtualDisplay(
            "JassAutoTapper",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            worker
        )
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putString("control_mode", "smart")
            .putBoolean("control_running", true)
            .putBoolean("control_paused", false)
            .apply()
        publishState("Smart detection running • looking for exact 1", true)
    }

    private fun publishState(message: String, running: Boolean) {
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putString("detector_status", message)
            .putBoolean("control_running", running)
            .putBoolean("control_paused", !running)
            .apply()
        sendBroadcast(
            Intent(AutoTapAccessibilityService.ACTION_CONTROL_STATE)
                .setPackage(packageName)
                .putExtra("running", running)
                .putExtra("active", true)
                .putExtra("message", message)
        )
    }

    override fun onDestroy() {
        paused = true
        runCatching { projection?.unregisterCallback(projectionCallback) }
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        recognizer.close()
        if (instance === this) instance = null
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putBoolean("control_running", false)
            .putBoolean("control_paused", false)
            .apply()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        @Volatile var instance: ScreenMonitorService? = null
    }
}
