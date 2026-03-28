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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.l2m.autokey.service.AutoKeyAccessibilityService

/**
 * Mandatory setup screen — LAUNCHER activity.
 * If accessibility is already enabled, immediately goes to MainActivity.
 * Otherwise blocks user until they enable it.
 */
class SetupActivity : AppCompatActivity() {

    private var tvStep: TextView? = null
    private var tvStatus: TextView? = null
    private var btnAction: Button? = null
    private var progressBar: ProgressBar? = null
    private var waitLabel: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already enabled, skip setup — go straight to main
        if (isAccessibilityEnabled()) {
            goToMain()
            return
        }

        buildSetupUI()
    }

    private fun buildSetupUI() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 100, 64, 64)
        }

        layout.addView(TextView(this).apply {
            text = "L2M AutoKey"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        layout.addView(TextView(this).apply {
            text = "Initial Setup"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })

        tvStep = TextView(this).apply {
            text = "Step 1: Enable Accessibility Service"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvStep)

        tvStatus = TextView(this).apply {
            text = "Accessibility Service harus diaktifkan agar app dapat mengirim input ke game.\n\n" +
                    "1. Tekan tombol di bawah\n" +
                    "2. Cari \"L2M AutoKey\" di daftar\n" +
                    "3. Aktifkan toggle ON\n" +
                    "4. Kembali ke app ini"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(tvStatus)

        btnAction = Button(this).apply {
            text = "Buka Accessibility Settings"
            setOnClickListener { openAccessibilitySettings() }
        }
        layout.addView(btnAction)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 24, 0, 0)
        }
        layout.addView(progressBar)

        waitLabel = TextView(this).apply {
            text = "Menunggu aktivasi..."
            visibility = View.GONE
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        layout.addView(waitLabel)

        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityEnabled()) {
            onAccessibilityEnabled()
        } else {
            startCheckingAccessibility()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCheckingAccessibility()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            progressBar?.visibility = View.VISIBLE
            waitLabel?.visibility = View.VISIBLE
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Check via AccessibilityManager
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val myComponent = ComponentName(this, AutoKeyAccessibilityService::class.java)
            for (info in enabledServices) {
                val comp = ComponentName.unflattenFromString(info.id)
                if (comp == myComponent) return true
            }
        } catch (_: Exception) {}

        // Also check singleton
        return AutoKeyAccessibilityService.instance != null
    }

    private fun startCheckingAccessibility() {
        stopCheckingAccessibility()
        checkRunnable = object : Runnable {
            override fun run() {
                if (isAccessibilityEnabled()) {
                    onAccessibilityEnabled()
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(checkRunnable!!, 500)
    }

    private fun stopCheckingAccessibility() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
    }

    private fun onAccessibilityEnabled() {
        stopCheckingAccessibility()
        tvStep?.text = "✅ Accessibility Service Aktif!"
        tvStatus?.text = "Setup selesai. Memulai aplikasi..."
        btnAction?.visibility = View.GONE
        progressBar?.visibility = View.GONE
        waitLabel?.visibility = View.GONE

        handler.postDelayed({ goToMain() }, 1000)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
