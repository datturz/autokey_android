package com.l2m.autokey.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.l2m.autokey.core.HpChecker
import com.l2m.autokey.core.InputInjector
import com.l2m.autokey.core.ScreenCapture
import com.l2m.autokey.core.TemplateMatcher
import kotlin.concurrent.thread

/**
 * Main bot service — runs screen capture loop + bot logic.
 * Equivalent to Python _screen_capture_loop + _check_radar_actions + _check_combat_escape.
 *
 * Features:
 * 1. Radar detection (red ❗ at WARNING_POSITIONS) → tap escape key position
 * 2. Combat escape (HP < threshold) → weapon + skill + teleport
 * 3. Auto teleport (via saved spots dialog)
 */
class BotService : Service() {

    companion object {
        private const val TAG = "BotService"
        private const val CHANNEL_ID = "l2m_autokey"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var isRunning = false
            private set
    }

    private lateinit var screenCapture: ScreenCapture
    private lateinit var templateMatcher: TemplateMatcher
    private lateinit var hpChecker: HpChecker

    private var botThread: Thread? = null
    @Volatile private var shouldStop = false

    // Settings (configurable from UI)
    var radarEnabled = false
    var combatEscapeEnabled = false
    var combatEscapeThreshold = 50 // HP percentage
    var autoTeleportEnabled = false

    // Escape key position @1280x720
    var escapeKeyName = "Q"

    // Combat escape keys
    var ceWeaponKey = ""
    var ceSkillKey = ""
    var ceTeleportKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        screenCapture = ScreenCapture(this)
        templateMatcher = TemplateMatcher(this)
        hpChecker = HpChecker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("L2M AutoKey running...")
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (data != null) {
            screenCapture.init(resultCode, data)

            // Set screen size for injector
            AutoKeyAccessibilityService.instance?.injector?.setScreenSize(
                screenCapture.getWidth(), screenCapture.getHeight()
            )

            shouldStop = false
            isRunning = true
            botThread = thread(name = "BotLoop") { botLoop() }
        }

        return START_NOT_STICKY
    }

    /**
     * Main bot loop — same flow as Python _screen_capture_loop.
     * Runs every 300ms.
     */
    private fun botLoop() {
        Log.i(TAG, "Bot loop started")
        var radarCooldownUntil = 0L
        var lastHp = 100
        var combatEscapeTriggered = false

        while (!shouldStop) {
            try {
                val bitmap = screenCapture.capture()
                if (bitmap == null) {
                    Thread.sleep(500)
                    continue
                }

                val injector = AutoKeyAccessibilityService.instance?.injector
                if (injector == null) {
                    Thread.sleep(500)
                    continue
                }

                val now = System.currentTimeMillis()

                // ── 1. RADAR CHECK (highest priority) ──
                if (radarEnabled && now > radarCooldownUntil) {
                    if (templateMatcher.checkRadarWarning(bitmap)) {
                        Log.i(TAG, "RADAR ❗ Detected! Escape!")
                        injector.pressKey(escapeKeyName)
                        radarCooldownUntil = now + 60_000 // 60s cooldown
                        updateNotification("RADAR escape! Cooldown 60s")
                        bitmap.recycle()
                        Thread.sleep(3000)
                        continue
                    }
                }

                // ── 2. HP CHECK + COMBAT ESCAPE ──
                val hpPct = (hpChecker.getHpPercentage(bitmap) * 100).toInt()

                if (combatEscapeEnabled && hpPct < combatEscapeThreshold && !combatEscapeTriggered) {
                    Log.i(TAG, "Combat Escape! HP=$hpPct% < $combatEscapeThreshold%")
                    combatEscapeTriggered = true
                    updateNotification("Combat Escape! HP=$hpPct%")

                    // Weapon swap
                    if (ceWeaponKey.isNotEmpty()) {
                        injector.pressKey(ceWeaponKey)
                        Thread.sleep(500)
                    }
                    // Skill
                    if (ceSkillKey.isNotEmpty()) {
                        injector.pressKey(ceSkillKey)
                        Thread.sleep(500)
                        injector.pressKey(ceSkillKey)
                        Thread.sleep(500)
                    }
                    // Teleport
                    if (ceTeleportKey.isNotEmpty()) {
                        injector.pressKey(ceTeleportKey)
                        Thread.sleep(1000)
                        injector.pressKey(ceTeleportKey)
                    }

                    bitmap.recycle()
                    Thread.sleep(3000)
                    continue
                }

                // Reset combat escape when HP recovers
                if (hpPct >= combatEscapeThreshold) {
                    combatEscapeTriggered = false
                }

                lastHp = hpPct
                updateNotification("HP: $hpPct% | Radar: ${if (radarEnabled) "ON" else "OFF"}")

                bitmap.recycle()
                Thread.sleep(300) // 0.3s tick

            } catch (e: Exception) {
                Log.e(TAG, "Bot loop error: ${e.message}")
                Thread.sleep(1000)
            }
        }

        Log.i(TAG, "Bot loop stopped")
        isRunning = false
    }

    override fun onDestroy() {
        shouldStop = true
        botThread?.interrupt()
        screenCapture.release()
        templateMatcher.release()
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "L2M AutoKey", NotificationManager.IMPORTANCE_LOW
        )
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("L2M AutoKey")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
