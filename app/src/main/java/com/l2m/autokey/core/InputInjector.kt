package com.l2m.autokey.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * Input injection via Accessibility Service.
 * Equivalent to Python MouseClicker + KeySender combined.
 *
 * On Android, game keys don't exist — all input is touch-based.
 * "Key press" = tap at specific screen coordinates.
 */
class InputInjector(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "InputInjector"
        // Base resolution for coordinate scaling (same as Windows version)
        const val BASE_W = 1280
        const val BASE_H = 720
    }

    private var screenWidth = 1280
    private var screenHeight = 720

    fun setScreenSize(w: Int, h: Int) {
        screenWidth = w
        screenHeight = h
    }

    /**
     * Scale coordinates from 1280x720 base to actual screen size.
     */
    fun scaleX(x: Int): Int = (x * screenWidth / BASE_W)
    fun scaleY(y: Int): Int = (y * screenHeight / BASE_H)

    /**
     * Tap at position (x, y) in actual screen coordinates.
     */
    fun tap(x: Int, y: Int, duration: Long = 50) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap OK at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Tap at 1280x720 base coordinates (auto-scaled).
     * Equivalent to Python click_scaled().
     */
    fun tapScaled(x1280: Int, y720: Int, duration: Long = 50) {
        tap(scaleX(x1280), scaleY(y720), duration)
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) in actual coords.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300) {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        service.dispatchGesture(gesture, null, null)
    }

    /**
     * Simulate "key press" by tapping the corresponding UI button position.
     * On mobile L2M, skill buttons are at fixed positions.
     *
     * Key mapping (approximate positions @1280x720):
     * These need calibration per device/resolution.
     */
    fun pressKey(key: String) {
        val pos = KEY_POSITIONS[key]
        if (pos != null) {
            tapScaled(pos.first, pos.second)
        } else {
            Log.w(TAG, "Unknown key: $key")
        }
    }

    // Skill/button positions @1280x720 — needs calibration from actual game
    // These are approximate and should be configurable
    private val KEY_POSITIONS = mapOf(
        "F" to Pair(1160, 580),      // Auto hunt button
        "R" to Pair(1080, 580),      // Radar scan
        "Q" to Pair(1000, 650),      // Teleport scroll
        "F5" to Pair(920, 580),      // Skill slot
        "F6" to Pair(840, 580),      // Skill slot
        "1" to Pair(760, 650),       // Quick slot 1
        "2" to Pair(840, 650),       // Quick slot 2
        "3" to Pair(920, 650),       // Quick slot 3
        "4" to Pair(1000, 580),      // Quick slot 4
        "5" to Pair(1080, 650),      // Quick slot 5
        "M" to Pair(1240, 80),       // Map button
        "O" to Pair(50, 400),        // Saved locations
        "Escape" to Pair(1240, 80),  // Close (same as map toggle)
    )
}
