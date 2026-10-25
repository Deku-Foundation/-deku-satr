package org.dekufoundation.satr

/**
 * Smooths adjacent confidence maps for the same screen geometry.
 *
 * The current frame dominates the result, so a disappeared person is not kept as a
 * visible ghost. A previous map is only blended for a short gap and identical dimensions.
 * This is boundary stabilization, not object tracking.
 */
internal class TemporalMaskSmoother(
    private val currentWeight: Float = DEFAULT_CURRENT_WEIGHT,
    private val maximumGapMs: Long = DEFAULT_MAXIMUM_GAP_MS,
) {
    private var previous: FloatArray? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var previousAtMs = 0L

    init {
        require(currentWeight.isFinite() && currentWeight in 0.5f..1f) {
            "Current-frame weight must be between 0.5 and 1."
        }
        require(maximumGapMs >= 0L) { "Maximum smoothing gap cannot be negative." }
    }

    @Synchronized
    fun smooth(
        current: FloatArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ): FloatArray {
        val count = width.toLong() * height.toLong()
        require(width > 0 && height > 0 && count == current.size.toLong()) {
            "Confidence dimensions must match the supplied values."
        }
        require(timestampMs >= 0L) { "Frame timestamp cannot be negative." }

        val old = previous
        val elapsed = timestampMs - previousAtMs
        val mayBlend = old != null &&
            previousWidth == width &&
            previousHeight == height &&
            elapsed in 0L..maximumGapMs &&
            old.size == current.size
        val oldWeight = 1f - currentWeight
        for (index in current.indices) {
            val fresh = current[index]
            require(fresh.isFinite() && fresh in 0f..1f) {
                "Confidence values must be finite probabilities."
            }
            current[index] = if (mayBlend) {
                (fresh * currentWeight + old!![index] * oldWeight).coerceIn(0f, 1f)
            } else {
                fresh
            }
        }

        // Keep exactly one prior map; mutate the new inference buffer in place to avoid
        // allocating another full-sized output array for every frame.
        previous = current.copyOf()
        previousWidth = width
        previousHeight = height
        previousAtMs = timestampMs
        return current
    }

    @Synchronized
    fun reset() {
        previous = null
        previousWidth = 0
        previousHeight = 0
        previousAtMs = 0L
    }

    private companion object {
        const val DEFAULT_CURRENT_WEIGHT = 0.72f
        const val DEFAULT_MAXIMUM_GAP_MS = 450L
    }
}
