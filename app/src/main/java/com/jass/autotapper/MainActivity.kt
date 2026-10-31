package com.jass.autotapper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var captureLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
    private var smartMode = false
    private var clickJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val i = Intent(this, ScreenMonitorService::class.java).putExtra("resultCode", result.resultCode).putExtra("data", result.data)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                startOverlay()
            }
        }
        setContent { AutoTapperScreen() }
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)
    private fun requestOverlay() {
        if (!hasOverlay()) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    private fun startOverlay() {
        if (!hasOverlay()) { requestOverlay(); return }
        startService(Intent(this, TargetOverlayService::class.java))
    }
    private fun startSmart() {
        if (!hasOverlay()) { requestOverlay(); return }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher?.launch(mgr.createScreenCaptureIntent())
    }
    private fun startNormal(intervalMs: Long, durationMs: Long) {
        if (AutoTapAccessibilityService.instance == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        startOverlay()
        clickJob?.cancel()
        clickJob = lifecycleScope.launch {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < durationMs) {
                val p = getSharedPreferences("targets", MODE_PRIVATE)
                val x = p.getInt("tap_x", resources.displayMetrics.widthPixels * 28 / 100).toFloat()
                val y = p.getInt("tap_y", resources.displayMetrics.heightPixels * 55 / 100).toFloat()
                AutoTapAccessibilityService.instance?.tap(x, y)
                delay(intervalMs.coerceAtLeast(50))
            }
        }
    }

    @Composable
    private fun AutoTapperScreen() {
        var mode by remember { mutableStateOf(0) }
        var speed by remember { mutableStateOf("500") }
        var speedUnit by remember { mutableStateOf("ms") }
        var duration by remember { mutableStateOf("10") }
        var durationUnit by remember { mutableStateOf("min") }
        var running by remember { mutableStateOf(false) }
        val bg = Color(0xFF101512)
        val panel = Color(0xFF1B251F)
        val green = Color(0xFF35D39A)
        MaterialTheme(colorScheme = darkColorScheme(primary = green, background = bg, surface = panel)) {
            Surface(Modifier.fillMaxSize(), color = bg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("JASS AUTO TAPPER", color = green, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Automate screen taps", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(14.dp)).padding(5.dp)) {
                        listOf("Normal", "Smart 1 Detect").forEachIndexed { index, label ->
                            Box(Modifier.weight(1f).background(if (mode == index) green else Color.Transparent, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { mode = index }) { Text(label, color = if (mode == index) Color.Black else Color.LightGray, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    if (mode == 0) {
                        Section("CLICK SPEED")
                        OutlinedTextField(speed, { speed = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Interval") }, suffix = { Text(speedUnit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ms", "sec", "min").forEach { unit -> FilterChip(selected = speedUnit == unit, onClick = { speedUnit = unit }, label = { Text(unit) }) }
                        }
                        Section("RUN DURATION")
                        OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("How long to run") }, suffix = { Text(durationUnit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("sec", "min", "hr").forEach { unit -> FilterChip(selected = durationUnit == unit, onClick = { durationUnit = unit }, label = { Text(unit) }) }
                        }
                        InfoCard("Tap position", "Enable accessibility, then drag the orange TAP circle to the desired screen location.")
                    } else {
                        Section("SMART NUMBER DETECTOR")
                        InfoCard("Two floating circles", "Orange TAP circle = where to tap. Green 1 circle = area to read. Drag both circles over the target app.")
                        InfoCard("Detection rule", "When OCR reads the exact number 1 inside the green circle, one tap is sent to the orange circle. It waits for 1 to disappear before triggering again.")
                        Text("Scan interval: continuous • Tap cooldown: 350 ms", color = Color.LightGray, fontSize = 13.sp)
                    }
                    Button(onClick = {
                        if (mode == 0) {
                            val s = speed.toLongOrNull() ?: 500L
                            val interval = when (speedUnit) { "sec" -> s * 1000; "min" -> s * 60000; else -> s }
                            val d = duration.toLongOrNull() ?: 10L
                            val total = when (durationUnit) { "sec" -> d * 1000; "hr" -> d * 3600000; else -> d * 60000 }
                            startNormal(interval, total); running = true
                        } else startSmart()
                    }, Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = green), shape = RoundedCornerShape(14.dp)) {
                        Text(if (mode == 0) "START AUTO CLICKER" else "START SMART DETECTION", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { clickJob?.cancel(); stopService(Intent(this@MainActivity, ScreenMonitorService::class.java)); stopService(Intent(this@MainActivity, TargetOverlayService::class.java)); running = false }, Modifier.fillMaxWidth().height(50.dp)) {
                        Text("STOP / HIDE FLOATING CIRCLES", color = Color.White)
                    }
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Enable Auto Tap Accessibility Service") }
                    Text("Permissions are controlled by Android. Screen capture starts only after your confirmation.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
    @Composable private fun Section(s: String) { Text(s, color = Color(0xFF35D39A), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    @Composable private fun InfoCard(title: String, body: String) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF1B251F), RoundedCornerShape(14.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(body, color = Color.LightGray, fontSize = 13.sp)
        }
    }
}
