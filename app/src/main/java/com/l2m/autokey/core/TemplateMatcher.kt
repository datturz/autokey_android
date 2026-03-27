package com.l2m.autokey.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Template matching and color detection.
 * Equivalent to Python image_utils + _fast_radar_warning_check + _is_boss_target_bar_visible.
 *
 * Uses simple pixel comparison instead of OpenCV for smaller APK size.
 * OpenCV can be added later if needed.
 */
class TemplateMatcher(private val context: Context) {

    companion object {
        private const val TAG = "TemplateMatcher"
    }

    private val templateCache = mutableMapOf<String, Bitmap>()

    /**
     * Load template from assets folder (cached).
     */
    fun loadTemplate(name: String): Bitmap? {
        templateCache[name]?.let { return it }
        return try {
            val bitmap = context.assets.open(name).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            templateCache[name] = bitmap
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load template: $name")
            null
        }
    }

    /**
     * Check if region has enough red pixels (for radar ❗ detection).
     * Same logic as Python _fast_radar_warning_check.
     *
     * @param bitmap Source image
     * @param x1,y1,x2,y2 Region to check (actual pixels)
     * @param threshold Minimum ratio of red pixels (0.0-1.0)
     */
    fun hasRedInRegion(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int, threshold: Float = 0.25f): Boolean {
        val cx1 = max(0, min(x1, bitmap.width - 1))
        val cy1 = max(0, min(y1, bitmap.height - 1))
        val cx2 = max(cx1 + 1, min(x2, bitmap.width))
        val cy2 = max(cy1 + 1, min(y2, bitmap.height))

        var redCount = 0
        var total = 0

        for (y in cy1 until cy2) {
            for (x in cx1 until cx2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Bright red: R > 150, G < 80, B < 80
                if (r > 150 && g < 80 && b < 80) {
                    redCount++
                }
                total++
            }
        }

        return total > 0 && (redCount.toFloat() / total) > threshold
    }

    /**
     * Simple template matching using normalized cross-correlation.
     * Searches for template in a region of the source bitmap.
     *
     * @return Match score (0.0-1.0), or -1 if error
     */
    fun matchTemplate(source: Bitmap, template: Bitmap,
                      regionX: Int = 0, regionY: Int = 0,
                      regionW: Int = source.width, regionH: Int = source.height): Float {
        val tw = template.width
        val th = template.height
        val rw = min(regionW, source.width - regionX)
        val rh = min(regionH, source.height - regionY)

        if (tw > rw || th > rh) return -1f

        // Scale template if resolution differs from 720p
        val scale = source.height / 720f
        val scaledTpl = if (abs(scale - 1f) > 0.05f) {
            val nw = max(1, (tw * scale).toInt())
            val nh = max(1, (th * scale).toInt())
            if (nw < rw && nh < rh) {
                Bitmap.createScaledBitmap(template, nw, nh, true)
            } else template
        } else template

        val stw = scaledTpl.width
        val sth = scaledTpl.height
        if (stw > rw || sth > rh) return -1f

        // Sample-based matching (check every 3rd pixel for speed)
        val step = 3
        var bestScore = 0f

        // Slide template across region (step by 2 pixels for speed)
        for (sy in 0..(rh - sth) step 2) {
            for (sx in 0..(rw - stw) step 2) {
                var matchSum = 0L
                var totalPixels = 0

                for (ty in 0 until sth step step) {
                    for (tx in 0 until stw step step) {
                        val srcPixel = source.getPixel(regionX + sx + tx, regionY + sy + ty)
                        val tplPixel = scaledTpl.getPixel(tx, ty)

                        val dr = Color.red(srcPixel) - Color.red(tplPixel)
                        val dg = Color.green(srcPixel) - Color.green(tplPixel)
                        val db = Color.blue(srcPixel) - Color.blue(tplPixel)

                        // Similarity: 1 - normalized distance
                        val dist = (dr * dr + dg * dg + db * db)
                        val maxDist = 3 * 255 * 255
                        matchSum += (maxDist - dist)
                        totalPixels++
                    }
                }

                val score = if (totalPixels > 0) matchSum.toFloat() / (totalPixels * 3 * 255 * 255) else 0f
                if (score > bestScore) {
                    bestScore = score
                }
            }
        }

        return bestScore
    }

    /**
     * Check radar warning positions for red ❗ icons.
     * Same as Python _fast_radar_warning_check.
     * Requires 2+ positions with red to trigger.
     */
    fun checkRadarWarning(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val scaleX = w / 1280f
        val scaleY = h / 720f

        // WARNING_POSITIONS from game_layout.py (1280x720)
        val positions = listOf(
            intArrayOf(1166, 214, 1182, 248),
            intArrayOf(974, 214, 990, 248),
            intArrayOf(974, 165, 990, 204),
            intArrayOf(1166, 165, 1182, 204),
            intArrayOf(974, 133, 990, 152),
            intArrayOf(1168, 133, 1182, 152),
        )

        var redCount = 0
        for (pos in positions) {
            val x1 = (pos[0] * scaleX).toInt()
            val y1 = (pos[1] * scaleY).toInt()
            val x2 = (pos[2] * scaleX).toInt()
            val y2 = (pos[3] * scaleY).toInt()

            if (hasRedInRegion(bitmap, x1, y1, x2, y2, 0.25f)) {
                redCount++
            }
        }

        return redCount >= 2
    }

    /**
     * Check if boss target bar is visible in top strip.
     * Region: 40-100% width, 0-6% height (same as Python).
     */
    fun isBossBarVisible(bitmap: Bitmap): Boolean {
        val templates = listOf("boss_target_bar.png", "boss_target_bar_2.png")
        val w = bitmap.width
        val h = bitmap.height

        for (name in templates) {
            val tpl = loadTemplate(name) ?: continue
            val score = matchTemplate(
                bitmap, tpl,
                regionX = (w * 0.4).toInt(), regionY = 0,
                regionW = (w * 0.6).toInt(), regionH = (h * 0.06).toInt()
            )
            if (score >= 0.65f) {
                Log.d(TAG, "$name: score=${"%.3f".format(score)}")
                return true
            }
        }
        return false
    }

    fun release() {
        templateCache.values.forEach { it.recycle() }
        templateCache.clear()
    }
}
