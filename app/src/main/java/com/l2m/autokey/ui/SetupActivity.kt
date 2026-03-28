package com.l2m.autokey.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.l2m.autokey.service.AutoKeyAccessibilityService

/**
 * Mandatory setup screen shown on first launch (or when accessibility is not enabled).
 * Blocks the user from proceeding until accessibility service is activated.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var tvStep: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnAction: Button
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already enabled, skip setup
        if (isAccessibilityEnabled()) {
            markSetupComplete()
            goToMain()
            return
        }

        // Build setup UI programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 120, 64, 64)
        }

        TextView(this).apply {
            text = "L2M AutoKey"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            layout.addView(this)
        }

        TextView(this).apply {
            text = "Initial Setup"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
            layout.addView(this)
        }

        tvStep = TextView(this).apply {
            text = "Step 1: Enable Accessibility Service"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            layout.addView(this)
        }

        tvStatus = TextView(this).apply {
            text = "Accessibility Service harus diaktifkan agar app dapat mengirim input ke game.\n\n" +
                    "1. Tekan tombol di bawah\n" +
                    "2. Cari \"L2M AutoKey\" di daftar\n" +
                    "3. Aktifkan toggle ON\n" +
                    "4. Kembali ke app ini"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            layout.addView(this)
        }

        btnAction = Button(this).apply {
            text = "Buka Accessibility Settings"
            setOnClickListener { openAccessibilitySettings() }
            layout.addView(this)
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 24, 0, 0)
            layout.addView(this)
        }

        TextView(this).apply {
            text = "Menunggu aktivasi..."
            id = View.generateViewId()
            visibility = View.GONE
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
            tag = "waitLabel"
            layout.addView(this)
        }

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // Start polling for accessibility status
        startCheckingAccessibility()
    }

    override fun onPause() {
        super.onPause()
        stopCheckingAccessibility()
    }

    private fun openAccessibilitySettings() {
        try {
            // Try to open accessibility settings directly
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)

            // Show waiting indicator
            progressBar.visibility = View.VISIBLE
            val waitLabel = findViewWithTag<TextView>("waitLabel")
            waitLabel?.visibility = View.VISIBLE
        } catch (e: Exception) {
            // Fallback to general settings
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun <T : View> findViewWithTag(tag: String): T? {
        return window.decorView.findViewWithTag(tag)
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Check via AccessibilityManager
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val myComponent = ComponentName(this, AutoKeyAccessibilityService::class.java)
        for (info in enabledServices) {
            val comp = ComponentName.unflattenFromString(info.id)
            if (comp == myComponent) return true
        }

        // Also check singleton
        return AutoKeyAccessibilityService.instance != null
    }

    private fun startCheckingAccessibility() {
        checkRunnable = object : Runnable {
            override fun run() {
                if (isAccessibilityEnabled()) {
                    onAccessibilityEnabled()
                } else {
                    handler.postDelayed(this, 500) // Check every 500ms
                }
            }
        }
        handler.post(checkRunnable!!)
    }

    private fun stopCheckingAccessibility() {
        checkRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun onAccessibilityEnabled() {
        tvStep.text = "✅ Accessibility Service Aktif!"
        tvStatus.text = "Setup selesai. Memulai aplikasi..."
        btnAction.visibility = View.GONE
        progressBar.visibility = View.GONE

        markSetupComplete()

        // Delay then go to main
        handler.postDelayed({ goToMain() }, 1000)
    }

    private fun markSetupComplete() {
        getSharedPreferences("l2m_autokey", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("setup_complete", true)
            .apply()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
