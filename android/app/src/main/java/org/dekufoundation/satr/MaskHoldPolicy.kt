package org.dekufoundation.satr

/**
 * Small, deterministic policy for reusing a recent person mask when a streaming
 * segmentation result is temporarily empty. Kept pure so the timing boundaries
 * can be unit-tested without Android or ML Kit.
 */
internal object MaskHoldPolicy {
    fun shouldReusePreviousMask(
        freshCoveragePercent: Int,
        minimumCoveragePercent: Int,
        previousMaskAvailable: Boolean,
        dimensionsMatch: Boolean,
        ageMs: Long,
        maximumAgeMs: Long,
    ): Boolean {
        require(freshCoveragePercent in 0..100) { "Fresh coverage must be a percentage." }
        require(minimumCoveragePercent in 0..100) { "Minimum coverage must be a percentage." }
        require(maximumAgeMs >= 0L) { "Maximum mask age cannot be negative." }

        return freshCoveragePercent <= minimumCoveragePercent &&
            previousMaskAvailable &&
            dimensionsMatch &&
            ageMs in 0L..maximumAgeMs
    }
}
