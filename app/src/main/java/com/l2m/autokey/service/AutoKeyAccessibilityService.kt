package com.l2m.autokey.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.l2m.autokey.core.InputInjector

/**
 * Accessibility Service — provides tap/swipe injection.
 * Must be enabled in Android Settings > Accessibility.
 */
class AutoKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        var instance: AutoKeyAccessibilityService? = null
            private set
    }

    lateinit var injector: InputInjector
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        injector = InputInjector(this)
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need gesture injection
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Accessibility Service destroyed")
    }
}
