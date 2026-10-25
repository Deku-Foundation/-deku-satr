package org.dekufoundation.satr

import kotlin.math.roundToInt

object PersonMaskMath {
    const val MAX_MASK_PIXELS = 2_000_000

    fun thresholdAndDilate(
        confidence: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.48f,
        radiusX: Int = 2,
        radiusY: Int = 2,
    ): BooleanArray {
        val pixelCount = width.toLong() * height.toLong()
        require(width > 0 && height > 0) { "Mask dimensions must be positive." }
        require(
            pixelCount in 1..MAX_MASK_PIXELS.toLong() &&
                pixelCount == confidence.size.toLong(),
        ) { "Mask dimensions do not match its data or exceed the processing limit." }
        require(threshold.isFinite() && threshold in 0f..1f) { "Threshold must be finite and between zero and one." }
        require(confidence.all { it.isFinite() && it in 0f..1f }) {
            "Confidence values must be finite probabilities between zero and one."
        }

        val radiusHorizontal = radiusX.coerceIn(0, width - 1)
        val radiusVertical = radiusY.coerceIn(0, height - 1)
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
