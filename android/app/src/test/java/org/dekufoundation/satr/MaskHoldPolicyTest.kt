package org.dekufoundation.satr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskHoldPolicyTest {
    @Test
    fun reusesRecentMaskForTransientEmptyResult() {
        assertTrue(
            MaskHoldPolicy.shouldReusePreviousMask(
                freshCoveragePercent = 0,
                minimumCoveragePercent = 1,
                previousMaskAvailable = true,
                dimensionsMatch = true,
                ageMs = 250L,
                maximumAgeMs = 250L,
            ),
        )
    }

    @Test
    fun doesNotReuseMaskAfterHoldWindowExpires() {
        assertFalse(
            MaskHoldPolicy.shouldReusePreviousMask(
                freshCoveragePercent = 0,
                minimumCoveragePercent = 1,
                previousMaskAvailable = true,
                dimensionsMatch = true,
                ageMs = 251L,
                maximumAgeMs = 250L,
            ),
        )
    }

    @Test
    fun doesNotReuseMaskWhenFrameDimensionsChange() {
        assertFalse(
            MaskHoldPolicy.shouldReusePreviousMask(
                freshCoveragePercent = 0,
                minimumCoveragePercent = 1,
                previousMaskAvailable = true,
                dimensionsMatch = false,
                ageMs = 100L,
                maximumAgeMs = 250L,
            ),
        )
    }

    @Test
    fun doesNotReuseMaskWhenFreshSegmentationFindsPerson() {
        assertFalse(
            MaskHoldPolicy.shouldReusePreviousMask(
                freshCoveragePercent = 2,
                minimumCoveragePercent = 1,
                previousMaskAvailable = true,
                dimensionsMatch = true,
                ageMs = 100L,
                maximumAgeMs = 250L,
            ),
        )
    }

    @Test
    fun doesNotReuseMaskWithoutPreviousMask() {
        assertFalse(
            MaskHoldPolicy.shouldReusePreviousMask(
                freshCoveragePercent = 0,
                minimumCoveragePercent = 1,
                previousMaskAvailable = false,
                dimensionsMatch = true,
                ageMs = 100L,
                maximumAgeMs = 160L,
            ),
        )
    }
}
