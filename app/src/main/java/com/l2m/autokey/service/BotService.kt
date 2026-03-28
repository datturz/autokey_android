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
 *
 * Mutual exclusion:
 * - Radar and combat escape share `escapedToTownAt` flag
 * - Once one triggers, the other is blocked until state clears
 * - Radar is suspended while in town + 3s grace after leaving
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

    // ── State tracking (matching Python adjustments) ──
    private var isInTown = false
    private var escapedToTownAt = 0L         // Timestamp when escaped to town
    private var lastInTownTime = 0L          // Last time detected in town
    private var radarLastTriggerAt = 0L      // Radar cooldown timestamp
    private var combatEscapeTriggered = false // One-shot per HP cycle
    private var isProcessingEscape = false   // Mutex: one escape at a time

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

            // Load settings
            loadSettings()

            shouldStop = false
            isRunning = true
            botThread = thread(name = "BotLoop") { botLoop() }
        }

        return START_NOT_STICKY
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("l2m_autokey", Context.MODE_PRIVATE)
        radarEnabled = prefs.getBoolean("radar_enabled", false)
        combatEscapeEnabled = prefs.getBoolean("combat_escape_enabled", false)
        autoTeleportEnabled = prefs.getBoolean("auto_teleport_enabled", false)
        combatEscapeThreshold = prefs.getInt("hp_threshold", 50)
    }

    /**
     * Main bot loop — same flow as Python _screen_capture_loop.
     * Runs every 300ms.
     *
     * Priority: Radar > Combat Escape > HP Actions
     * Mutual exclusion via isProcessingEscape + escapedToTownAt
     */
    private fun botLoop() {
        Log.i(TAG, "Bot loop started")
        var lastHp = 100

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
                    bitmap.recycle()
                    continue
                }

                val now = System.currentTimeMillis()

                // ── Auto-reset escapedToTownAt after 60s (prevent stuck) ──
                if (escapedToTownAt > 0 && (now - escapedToTownAt) > 60_000) {
                    Log.i(TAG, "Reset escaped state (timeout 60s)")
                    escapedToTownAt = 0
                    isInTown = false
                }

                // ── 1. RADAR CHECK (highest priority) ──
                // Guards: enabled, not in town, not already escaped,
                //         cooldown 60s, grace period 3s after leaving town
                val radarCooldownOk = (now - radarLastTriggerAt) > 60_000
                val graceOk = (now - lastInTownTime) > 3_000
                if (radarEnabled
                    && !isInTown
                    && !isProcessingEscape
                    && escapedToTownAt == 0L
                    && radarCooldownOk
                    && graceOk
                ) {
                    if (templateMatcher.checkRadarWarning(bitmap)) {
                        Log.i(TAG, "RADAR ❗ Detected! Escape!")
                        isProcessingEscape = true
                        try {
                            injector.pressKey(escapeKeyName)
                            Thread.sleep(500)
                            injector.pressKey(escapeKeyName) // Press 2x
                        } finally {
                            isProcessingEscape = false
                        }

                        // Set town state
                        escapedToTownAt = now
                        radarLastTriggerAt = now
                        isInTown = true
                        lastInTownTime = now
                        updateNotification("RADAR escape! In town.")

                        bitmap.recycle()
                        Thread.sleep(3000) // Wait for teleport
                        continue
                    }
                }

                // ── 2. HP CHECK + COMBAT ESCAPE ──
                val hpPct = (hpChecker.getHpPercentage(bitmap) * 100).toInt()

                // Reset combat escape when HP recovers
                if (hpPct >= combatEscapeThreshold) {
                    combatEscapeTriggered = false
                }

                // Guards: enabled, HP low, not already triggered, not in town,
                //         not processing another escape, not already escaped
                if (combatEscapeEnabled
                    && hpPct < combatEscapeThreshold
                    && !combatEscapeTriggered
                    && !isInTown
                    && !isProcessingEscape
                    && escapedToTownAt == 0L
                ) {
                    Log.i(TAG, "Combat Escape! HP=$hpPct% < $combatEscapeThreshold%")
                    combatEscapeTriggered = true
                    isProcessingEscape = true
                    updateNotification("Combat Escape! HP=$hpPct%")

                    try {
                        // Weapon swap
                        if (ceWeaponKey.isNotEmpty()) {
                            injector.pressKey(ceWeaponKey)
                            Thread.sleep(500)
                        }
                        // Skill (press 2x)
                        if (ceSkillKey.isNotEmpty()) {
                            injector.pressKey(ceSkillKey)
                            Thread.sleep(500)
                            injector.pressKey(ceSkillKey)
                            Thread.sleep(500)
                        }
                        // Teleport (press 2x)
                        if (ceTeleportKey.isNotEmpty()) {
                            injector.pressKey(ceTeleportKey)
                            Thread.sleep(1000)
                            injector.pressKey(ceTeleportKey)
                        }
                    } finally {
                        isProcessingEscape = false
                    }

                    // Set town state
                    escapedToTownAt = now
                    isInTown = true
                    lastInTownTime = now

                    bitmap.recycle()
                    Thread.sleep(3000) // Wait for teleport
                    continue
                }

                // ── 3. Status update ──
                lastHp = hpPct
                val townStr = if (isInTown) " | In Town" else ""
                val escStr = if (escapedToTownAt > 0) " | Escaped" else ""
                updateNotification("HP: $hpPct% | Radar: ${if (radarEnabled) "ON" else "OFF"}$townStr$escStr")

                bitmap.recycle()
                Thread.sleep(300) // 0.3s tick

            } catch (e: Exception) {
                Log.e(TAG, "Bot loop error: ${e.message}")
                isProcessingEscape = false // Safety reset
                Thread.sleep(1000)
            }
        }

        Log.i(TAG, "Bot loop stopped")
        isRunning = false
    }

    /**
     * Called when bot returns to farm (e.g., after auto-teleport back).
     * Resets town/escape state so radar + combat escape become active again.
     */
    fun onReturnedToFarm() {
        isInTown = false
        escapedToTownAt = 0
        lastInTownTime = System.currentTimeMillis() // 3s grace before radar active
        radarLastTriggerAt = 0
        combatEscapeTriggered = false
        Log.i(TAG, "Returned to farm — radar + combat escape active")
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
