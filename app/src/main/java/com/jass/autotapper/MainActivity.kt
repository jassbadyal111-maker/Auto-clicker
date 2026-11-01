package com.jass.autotapper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.media.projection.MediaProjectionManager

private data class CircleColor(val name: String, val value: Int)

private val circlePalette = listOf(
    CircleColor("Amber", 0xFFFFB300.toInt()),
    CircleColor("Green", 0xFF00C982.toInt()),
    CircleColor("Blue", 0xFF38A7FF.toInt()),
    CircleColor("Pink", 0xFFFF5C93.toInt()),
    CircleColor("Red", 0xFFFF5A52.toInt()),
    CircleColor("Purple", 0xFFB58CFF.toInt()),
    CircleColor("White", 0xFFFFFFFF.toInt())
)

private val appBg = Color(0xFF0D1411)
private val cardBg = Color(0xFF18231D)
private val accent = Color(0xFF42D6A0)
private val softText = Color(0xFFB8C7BF)

class MainActivity : ComponentActivity() {
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenMonitorService::class.java)
                    .putExtra("resultCode", result.resultCode)
                    .putExtra("data", result.data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                else startService(serviceIntent)
                Toast.makeText(this, "Smart Detection started. Use the floating pause button.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show()
            }
        }

        setContent { AutoTapperScreen() }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (!hasOverlayPermission()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun ensureAccessibility(): Boolean {
        if (AutoTapAccessibilityService.instance != null) return true
        Toast.makeText(this, "Please enable Auto Tap Accessibility first", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return false
    }

    private fun showOverlays() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        getSharedPreferences("targets", MODE_PRIVATE).edit().putBoolean("overlay_visible", true).apply()
        startService(Intent(this, TargetOverlayService::class.java))
    }

    private fun refreshOverlays() {
        if (!hasOverlayPermission()) return
        if (!getSharedPreferences("targets", MODE_PRIVATE).getBoolean("overlay_visible", false)) return
        startService(Intent(this, TargetOverlayService::class.java).setAction(TargetOverlayService.ACTION_REFRESH))
    }

    private fun startNormal(intervalMs: Long, durationMs: Long) {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        if (!ensureAccessibility()) return

        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putString("control_mode", "normal")
            .putLong("normal_interval", intervalMs)
            .putLong("normal_duration_ms", durationMs)
            .putBoolean("control_running", true)
            .putBoolean("control_paused", false)
            .apply()
        showOverlays()
        AutoTapAccessibilityService.instance?.startAutoClick(intervalMs, durationMs)
    }

    private fun startSmart() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        if (!ensureAccessibility()) return
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putString("control_mode", "smart")
            .putBoolean("control_running", true)
            .putBoolean("control_paused", false)
            .apply()
        showOverlays()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopAll() {
        AutoTapAccessibilityService.instance?.stopAutoClick()
        ScreenMonitorService.instance?.stopDetection()
        stopService(Intent(this, ScreenMonitorService::class.java))
        stopService(Intent(this, TargetOverlayService::class.java))
        getSharedPreferences("targets", MODE_PRIVATE).edit()
            .putBoolean("overlay_visible", false)
            .putBoolean("control_running", false)
            .putBoolean("control_paused", false)
            .putString("detector_status", "Stopped")
            .apply()
    }

    @Composable
    private fun AutoTapperScreen() {
        val prefs = getSharedPreferences("targets", MODE_PRIVATE)
        var mode by remember { mutableIntStateOf(0) }
        var intervalText by remember { mutableStateOf("500") }
        var intervalUnit by remember { mutableStateOf("ms") }
        var durationText by remember { mutableStateOf("10") }
        var durationUnit by remember { mutableStateOf("min") }

        var tapColor by remember { mutableIntStateOf(prefs.getInt("tap_color", 0xFFFFB300.toInt())) }
        var detectColor by remember { mutableIntStateOf(prefs.getInt("detect_color", 0xFF00C982.toInt())) }
        var tapShape by remember { mutableStateOf(prefs.getString("tap_shape", "circle") ?: "circle") }
        var detectShape by remember { mutableStateOf(prefs.getString("detect_shape", "circle") ?: "circle") }
        var tapSize by remember { mutableFloatStateOf(prefs.getInt("tap_size", 76).toFloat()) }
        var detectSize by remember { mutableFloatStateOf(prefs.getInt("detect_size", 76).toFloat()) }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = accent,
            secondary = Color(0xFF85E6C0),
            background = appBg,
            surface = cardBg,
            onPrimary = Color(0xFF07120D),
            onSurface = Color.White
        )) {
            Surface(modifier = Modifier.fillMaxSize(), color = appBg) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(16.dp), color = accent) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Text("J", color = Color(0xFF07120D), fontSize = 25.sp, fontWeight = FontWeight.Black)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("JASS AUTO TAPPER", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text("Precision touch control", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(16.dp)).padding(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        ModeButton("Normal", mode == 0, Modifier.weight(1f)) { mode = 0 }
                        ModeButton("Smart Detect", mode == 1, Modifier.weight(1f)) { mode = 1 }
                    }

                    if (mode == 0) {
                        SectionTitle("NORMAL AUTO CLICK")
                        Card(colors = CardDefaults.cardColors(containerColor = cardBg), shape = RoundedCornerShape(18.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Click interval", color = Color.White, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = intervalText,
                                    onValueChange = { intervalText = it.filter(Char::isDigit).take(8) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Time between taps") },
                                    suffix = { Text(intervalUnit) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                ChoiceRow(listOf("ms", "sec", "min"), intervalUnit) { intervalUnit = it }
                                Spacer(Modifier.height(2.dp))
                                Text("Auto-stop after", color = Color.White, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = durationText,
                                    onValueChange = { durationText = it.filter(Char::isDigit).take(8) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Run duration") },
                                    suffix = { Text(durationUnit) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                ChoiceRow(listOf("sec", "min", "hr"), durationUnit) { durationUnit = it }
                            }
                        }
                        Text("Move the orange TAP target to the point you want to press.", color = softText, fontSize = 13.sp)
                    } else {
                        SectionTitle("SMART NUMBER DETECTOR")
                        Card(colors = CardDefaults.cardColors(containerColor = cardBg), shape = RoundedCornerShape(18.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("How it works", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("1. Move the green reticle over the number you want to detect.", color = softText, fontSize = 14.sp)
                                Text("2. Move the orange TAP target to the action point.", color = softText, fontSize = 14.sp)
                                Text("3. The app reads only the green target area. It taps once when the exact digit 1 appears, then waits for it to disappear.", color = softText, fontSize = 14.sp)
                                Surface(color = Color(0xFF102D22), shape = RoundedCornerShape(12.dp)) {
                                    Text(
                                        prefs.getString("detector_status", "Ready to scan") ?: "Ready to scan",
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        color = accent,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    SectionTitle("FLOATING TARGET APPEARANCE")
                    CircleStylePanel(
                        title = "Tap target  •  Orange",
                        colorValue = tapColor,
                        shapeValue = tapShape,
                        sizeValue = tapSize,
                        onColor = { tapColor = it; prefs.edit().putInt("tap_color", it).apply(); refreshOverlays() },
                        onShape = { tapShape = it; prefs.edit().putString("tap_shape", it).apply(); refreshOverlays() },
                        onSize = { tapSize = it },
                        onSizeFinished = { prefs.edit().putInt("tap_size", tapSize.toInt()).apply(); refreshOverlays() }
                    )
                    CircleStylePanel(
                        title = "Detection target  •  Green",
                        colorValue = detectColor,
                        shapeValue = detectShape,
                        sizeValue = detectSize,
                        onColor = { detectColor = it; prefs.edit().putInt("detect_color", it).apply(); refreshOverlays() },
                        onShape = { detectShape = it; prefs.edit().putString("detect_shape", it).apply(); refreshOverlays() },
                        onSize = { detectSize = it },
                        onSizeFinished = { prefs.edit().putInt("detect_size", detectSize.toInt()).apply(); refreshOverlays() }
                    )

                    Button(
                        onClick = {
                            if (mode == 0) {
                                val amount = (intervalText.toLongOrNull() ?: 500L).coerceAtLeast(1L)
                                val intervalMs = when (intervalUnit) {
                                    "sec" -> (amount * 1000L).coerceAtMost(86_400_000L)
                                    "min" -> (amount * 60_000L).coerceAtMost(86_400_000L)
                                    else -> amount.coerceAtMost(86_400_000L)
                                }
                                val durationAmount = (durationText.toLongOrNull() ?: 10L).coerceAtLeast(1L)
                                val durationMs = when (durationUnit) {
                                    "sec" -> (durationAmount * 1000L).coerceAtMost(86_400_000L)
                                    "hr" -> (durationAmount * 3_600_000L).coerceAtMost(86_400_000L)
                                    else -> (durationAmount * 60_000L).coerceAtMost(86_400_000L)
                                }
                                startNormal(intervalMs, durationMs)
                            } else {
                                startSmart()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text(if (mode == 0) "START AUTO CLICKER" else "START SMART DETECTION", color = Color(0xFF07120D), fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(onClick = { stopAll() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("STOP ALL  •  HIDE FLOATING CONTROLS", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Accessibility settings", color = accent)
                    }
                    Text(
                        "Tip: Use the floating round ▶ / Ⅱ button to start or pause while another app is open.",
                        color = Color(0xFF81948A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) accent else Color.Transparent,
                contentColor = if (selected) Color(0xFF07120D) else softText
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }

    @Composable
    private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) }
                )
            }
        }
    }

    @Composable
    private fun CircleStylePanel(
        title: String,
        colorValue: Int,
        shapeValue: String,
        sizeValue: Float,
        onColor: (Int) -> Unit,
        onShape: (String) -> Unit,
        onSize: (Float) -> Unit,
        onSizeFinished: () -> Unit
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = cardBg), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Color", color = softText, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    circlePalette.forEach { choice ->
                        FilterChip(
                            selected = colorValue == choice.value,
                            onClick = { onColor(choice.value) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(12.dp).background(Color(choice.value), CircleShape))
                                    Text(choice.name)
                                }
                            }
                        )
                    }
                }
                Text("Shape", color = softText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Circle" to "circle", "Rounded" to "rounded", "Square" to "square").forEach { option ->
                        FilterChip(
                            selected = shapeValue == option.second,
                            onClick = { onShape(option.second) },
                            label = { Text(option.first) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", color = softText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${sizeValue.toInt()} dp", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Slider(value = sizeValue, onValueChange = onSize, onValueChangeFinished = onSizeFinished, valueRange = 56f..120f, steps = 7)
            }
        }
    }
}
