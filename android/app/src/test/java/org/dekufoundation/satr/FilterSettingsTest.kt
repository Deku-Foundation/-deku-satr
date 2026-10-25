package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterSettingsTest {
    @Test
    fun blurStrengthMapsToBoundedRadius() {
        FilterSettings.blurStrengthPercent = 10
        assertEquals(FilterSettings.MIN_BLUR_RADIUS, FilterSettings.blurRadius)
        FilterSettings.blurStrengthPercent = 100
        assertEquals(FilterSettings.MAX_BLUR_RADIUS, FilterSettings.blurRadius)
    }

    @Test
    fun confidenceThresholdIsClampedAndFinite() {
        FilterSettings.confidenceThreshold = 0.1f
        assertEquals(FilterSettings.MIN_CONFIDENCE_THRESHOLD, FilterSettings.confidenceThreshold)
        FilterSettings.confidenceThreshold = 0.9f
        assertEquals(FilterSettings.MAX_CONFIDENCE_THRESHOLD, FilterSettings.confidenceThreshold)
        FilterSettings.confidenceThreshold = Float.NaN
        assertEquals(FilterSettings.DEFAULT_CONFIDENCE_THRESHOLD, FilterSettings.confidenceThreshold)
    }

    @Test
    fun maskExpansionIsBounded() {
        FilterSettings.maskExpansionRadius = -4
        assertEquals(0, FilterSettings.maskExpansionRadius)
        FilterSettings.maskExpansionRadius = 99
        assertEquals(6, FilterSettings.maskExpansionRadius)
        FilterSettings.resetToDefaults()
    }
}
