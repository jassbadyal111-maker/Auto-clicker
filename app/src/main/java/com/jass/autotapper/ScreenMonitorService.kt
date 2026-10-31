package com.jass.autotapper

import android.app.*
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenMonitorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var previousMatch = false
    private var lastTap = 0L

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(42, notification())
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        startCapture()
        return START_STICKY
    }
    private fun notification(): Notification {
        val channel = "smart_monitor"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Smart number detection", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, channel).setContentTitle("Jass Auto Tapper").setContentText("Watching selected screen area for number 1").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
    }
    private fun startCapture() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels; val dpi = dm.densityDpi
        thread = HandlerThread("screen-reader").also { it.start() }; worker = Handler(thread!!.looper)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("JassAutoTapper", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, worker)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (busy) { image.close(); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]; val buffer = plane.buffer
                val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buffer)
                val prefs = getSharedPreferences("targets", MODE_PRIVATE)
                val cx = prefs.getInt("detect_x", w * 72 / 100)
                val cy = prefs.getInt("detect_y", h * 55 / 100)
                val radius = 54
                val left = (cx - radius).coerceIn(0, full.width - 1)
                val top = (cy - radius).coerceIn(0, h - 1)
                val crop = Bitmap.createBitmap(full, left, top, (radius * 2).coerceAtMost(full.width-left), (radius * 2).coerceAtMost(h-top))
                full.recycle(); image.close(); busy = true
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { result ->
                        val found = result.textBlocks.any { block -> block.lines.any { line -> line.text.trim() == "1" } }
                        val now = System.currentTimeMillis()
                        if (found && !previousMatch && now - lastTap > 350) {
                            val tx = prefs.getInt("tap_x", w * 28 / 100).toFloat()
                            val ty = prefs.getInt("tap_y", h * 55 / 100).toFloat()
                            AutoTapAccessibilityService.instance?.tap(tx, ty)
                            lastTap = now
                        }
                        previousMatch = found
                    }
                    .addOnCompleteListener { crop.recycle(); busy = false }
            } catch (_: Exception) { runCatching { image.close() }; busy = false }
        }, worker)
    }
    override fun onDestroy() {
        display?.release(); reader?.close(); projection?.stop(); thread?.quitSafely(); recognizer.close()
        super.onDestroy()
    }
}
