package org.dekufoundation.satr

import kotlin.math.roundToInt

object PersonMaskMath {
    fun thresholdAndDilate(
        confidence: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.48f,
        radiusX: Int = 2,
        radiusY: Int = 2,
    ): BooleanArray {
        require(width > 0 && height > 0) { "Mask dimensions must be positive." }
        require(confidence.size == width * height) { "Mask dimensions do not match its data." }
        require(threshold in 0f..1f) { "Threshold must be between zero and one." }

        val radiusHorizontal = radiusX.coerceAtLeast(0)
        val radiusVertical = radiusY.coerceAtLeast(0)
        val binary = BooleanArray(confidence.size) { confidence[it] >= threshold }
        if (radiusHorizontal == 0 && radiusVertical == 0) return binary

        val horizontal = BooleanArray(binary.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var hit = false
                val left = (x - radiusHorizontal).coerceAtLeast(0)
                val right = (x + radiusHorizontal).coerceAtMost(width - 1)
                for (xx in left..right) {
                    if (binary[row + xx]) {
                        hit = true
                        break
                    }
                }
                horizontal[row + x] = hit
            }
        }

        val output = BooleanArray(binary.size)
        for (y in 0 until height) {
            val top = (y - radiusVertical).coerceAtLeast(0)
            val bottom = (y + radiusVertical).coerceAtMost(height - 1)
            for (x in 0 until width) {
                var hit = false
                for (yy in top..bottom) {
                    if (horizontal[yy * width + x]) {
                        hit = true
                        break
                    }
                }
                output[y * width + x] = hit
            }
        }
        return output
    }

    fun coveragePercent(mask: BooleanArray): Int {
        if (mask.isEmpty()) return 0
        val foreground = mask.count { it }
        return ((foreground * 100.0) / mask.size).roundToInt().coerceIn(0, 100)
    }
}
