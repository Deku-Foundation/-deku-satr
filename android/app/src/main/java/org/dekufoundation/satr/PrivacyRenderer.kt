package org.dekufoundation.satr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

data class RenderedPrivacyMask(
    val overlay: Bitmap,
    val coveragePercent: Int,
)

object PrivacyRenderer {
    /**
     * Returns a transparent bitmap with only masked pixels made visible.
     * Source frames and intermediates stay in memory; callers must not persist them.
     */
    fun render(
        source: Bitmap,
        confidence: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        threshold: Float = 0.48f,
        blurRadius: Int = FilterSettings.blurRadius,
    ): RenderedPrivacyMask {
        require(maskWidth > 0 && maskHeight > 0)
        require(confidence.size == maskWidth * maskHeight)

        val width = source.width
        val height = source.height
        require(width > 0 && height > 0)

        val binary = PersonMaskMath.thresholdAndDilate(
            confidence = confidence,
            width = maskWidth,
            height = maskHeight,
            threshold = threshold,
            radiusX = 2,
            radiusY = 2,
        )
        val coverage = PersonMaskMath.coveragePercent(binary)

        val sourcePixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val boundedBlur = blurRadius.coerceIn(FilterSettings.MIN_BLUR_RADIUS, FilterSettings.MAX_BLUR_RADIUS)
        val blurredPixels = boxBlur(sourcePixels, width, height, max(boundedBlur, min(width, height) / 36))
        val outputPixels = IntArray(width * height)

        for (y in 0 until height) {
            val maskY = min(maskHeight - 1, (y.toLong() * maskHeight / height).toInt())
            for (x in 0 until width) {
                val maskX = min(maskWidth - 1, (x.toLong() * maskWidth / width).toInt())
                val index = y * width + x
                if (binary[maskY * maskWidth + maskX]) {
                    outputPixels[index] = blurredPixels[index] or (0xFF shl 24)
                }
            }
        }

        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlay.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return RenderedPrivacyMask(overlay, coverage)
    }

    /** Two-pass, edge-clamped box blur: linear-time and suitable for reduced screen frames. */
    private fun boxBlur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return src.copyOf()
        val horizontal = IntArray(src.size)
        val result = IntArray(src.size)
        val diameter = radius * 2 + 1

        for (y in 0 until height) {
            val row = y * width
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (offset in -radius..radius) {
                val pixel = src[row + offset.coerceIn(0, width - 1)]
                sumA += pixel ushr 24 and 0xFF
                sumR += pixel ushr 16 and 0xFF
                sumG += pixel ushr 8 and 0xFF
                sumB += pixel and 0xFF
            }

            for (x in 0 until width) {
                horizontal[row + x] =
                    ((sumA / diameter) shl 24) or
                    ((sumR / diameter) shl 16) or
                    ((sumG / diameter) shl 8) or
                    (sumB / diameter)

                val remove = src[row + (x - radius).coerceIn(0, width - 1)]
                val add = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                sumA += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                sumR += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                sumG += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }

        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (offset in -radius..radius) {
                val pixel = horizontal[offset.coerceIn(0, height - 1) * width + x]
                sumA += pixel ushr 24 and 0xFF
                sumR += pixel ushr 16 and 0xFF
                sumG += pixel ushr 8 and 0xFF
                sumB += pixel and 0xFF
            }

            for (y in 0 until height) {
                result[y * width + x] =
                    ((sumA / diameter) shl 24) or
                    ((sumR / diameter) shl 16) or
                    ((sumG / diameter) shl 8) or
                    (sumB / diameter)

                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumA += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                sumR += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                sumG += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
        return result
    }
}
