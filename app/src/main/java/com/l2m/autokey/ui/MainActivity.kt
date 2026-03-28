package com.l2m.autokey.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.l2m.autokey.R
import com.l2m.autokey.service.AutoKeyAccessibilityService
import com.l2m.autokey.service.BotService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var cbRadar: CheckBox
    private lateinit var cbCombatEscape: CheckBox
    private lateinit var cbAutoTeleport: CheckBox
    private lateinit var etHpThreshold: EditText
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvCaptureStatus: TextView

    private var projectionResultCode = 0
    private var projectionData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force setup if accessibility not enabled
        if (AutoKeyAccessibilityService.instance == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        cbRadar = findViewById(R.id.cbRadar)
        cbCombatEscape = findViewById(R.id.cbCombatEscape)
        cbAutoTeleport = findViewById(R.id.cbAutoTeleport)
        etHpThreshold = findViewById(R.id.etHpThreshold)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus)

        // Load saved settings
        loadSettings()

        btnStart.setOnClickListener { onStartBot() }
        btnStop.setOnClickListener { onStopBot() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateBotStatus()
    }

    private fun onStartBot() {
        // renamed from onStart to avoid conflict with Activity.onStart()
        // Check accessibility
        if (AutoKeyAccessibilityService.instance == null) {
            Toast.makeText(this, "Enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // Request screen capture permission
        if (projectionData == null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            return
        }

        startBot()
    }

    private fun startBot() {
        saveSettings()

        val intent = Intent(this, BotService::class.java).apply {
            putExtra(BotService.EXTRA_RESULT_CODE, projectionResultCode)
            putExtra(BotService.EXTRA_DATA, projectionData)
        }
        startForegroundService(intent)

        // Apply settings to service
        // (In production, use a proper communication mechanism)
        tvStatus.text = "Status: Running"
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun onStopBot() {
        stopService(Intent(this, BotService::class.java))
        tvStatus.text = "Status: Stopped"
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK) {
            projectionResultCode = resultCode
            projectionData = data
            tvCaptureStatus.text = "✅ Screen Capture: ON"
            startBot()
        }
    }

    private fun updatePermissionStatus() {
        val a11yOn = AutoKeyAccessibilityService.instance != null
        tvAccessibilityStatus.text = if (a11yOn) "✅ Accessibility: ON" else "❌ Accessibility: OFF"
        tvCaptureStatus.text = if (projectionData != null) "✅ Screen Capture: ON" else "❌ Screen Capture: OFF"
    }

    private fun updateBotStatus() {
        if (BotService.isRunning) {
            tvStatus.text = "Status: Running"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.text = "Status: Stopped"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("l2m_autokey", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("radar_enabled", cbRadar.isChecked)
            putBoolean("combat_escape_enabled", cbCombatEscape.isChecked)
            putBoolean("auto_teleport_enabled", cbAutoTeleport.isChecked)
            putInt("hp_threshold", etHpThreshold.text.toString().toIntOrNull() ?: 50)
            apply()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("l2m_autokey", Context.MODE_PRIVATE)
        cbRadar.isChecked = prefs.getBoolean("radar_enabled", false)
        cbCombatEscape.isChecked = prefs.getBoolean("combat_escape_enabled", false)
        cbAutoTeleport.isChecked = prefs.getBoolean("auto_teleport_enabled", false)
        etHpThreshold.setText(prefs.getInt("hp_threshold", 50).toString())
    }
}
