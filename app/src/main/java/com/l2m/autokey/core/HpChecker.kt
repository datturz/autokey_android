package com.l2m.autokey.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HP bar detection from screenshot.
 * Same logic as Python HPChecker — reads HP bar color ratio.
 *
 * HP bar region (normalized): 3.6%-16.6% width, 2.2%-4.0% height
 */
class HpChecker {

    companion object {
        // HP bar region (normalized 0-1, from game_layout.py)
        private const val HP_X1 = 0.0359375f
        private const val HP_Y1 = 0.022222f
        private const val HP_X2 = 0.165625f
        private const val HP_Y2 = 0.040278f
    }

    private var lastHpPct = 1.0f
    private var hpColor: IntArray? = null // Calibrated HP bar color

    /**
     * Get HP percentage from screenshot (0.0 - 1.0).
     * Includes sudden-drop guard (>30% drop = capture error).
     */
    fun getHpPercentage(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height

        val x1 = (HP_X1 * w).toInt()
        val y1 = (HP_Y1 * h).toInt()
        val x2 = (HP_X2 * w).toInt()
        val y2 = (HP_Y2 * h).toInt()

        if (x2 <= x1 || y2 <= y1) return lastHpPct

        // Calibrate HP color from sample point (leftmost always-filled area)
        if (hpColor == null) {
            val sx = (0.034 * w).toInt()
            val sy = (0.030 * h).toInt()
            if (sx in 0 until w && sy in 0 until h) {
                val pixel = bitmap.getPixel(sx, sy)
                hpColor = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            }
        }

        val refColor = hpColor ?: return lastHpPct

        // Count HP bar pixels (match HP color)
        var hpPixels = 0
        var totalPixels = 0
        val midY = (y1 + y2) / 2

        for (x in x1 until x2) {
            val pixel = bitmap.getPixel(x, midY)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            val dr = abs(r - refColor[0])
            val dg = abs(g - refColor[1])
            val db = abs(b - refColor[2])

            // Similar color = HP bar present
            if (dr < 60 && dg < 60 && db < 60) {
                hpPixels++
            }
            totalPixels++
        }

        val rawPct = if (totalPixels > 0) hpPixels.toFloat() / totalPixels else lastHpPct

        // Sudden-drop guard: >30% drop in 1 frame = capture error
        val drop = lastHpPct - rawPct
        if (drop > 0.30f && lastHpPct > 0.20f) {
            return lastHpPct // Lock to last known value
        }

        lastHpPct = rawPct
        return rawPct
    }

    fun getLastHp() = lastHpPct
}
