package org.dekufoundation.satr

import kotlin.math.roundToInt

data class CaptureSize(val width: Int, val height: Int)

object CaptureSizePolicy {
    const val DEFAULT_MAX_EDGE = 640

    /** Down-scales a display without changing its aspect ratio or up-scaling it. */
    fun downsample(width: Int, height: Int, maxEdge: Int = DEFAULT_MAX_EDGE): CaptureSize {
        require(width > 0) { "Display width must be positive." }
        require(height > 0) { "Display height must be positive." }
        require(maxEdge > 0) { "Maximum edge must be positive." }
        val sourceEdge = maxOf(width, height)
        val scale = minOf(1.0, maxEdge.toDouble() / sourceEdge.toDouble())
        return CaptureSize(
            width = maxOf(1, (width * scale).roundToInt()),
            height = maxOf(1, (height * scale).roundToInt()),
        )
    }
}
