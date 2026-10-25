package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class TemporalMaskSmootherTest {
    @Test
    fun blendsNearThresholdEdgesWithThePreviousFrame() {
        val smoother = TemporalMaskSmoother(currentWeight = 0.72f, maximumGapMs = 450L)
        smoother.smooth(floatArrayOf(0.8f), width = 1, height = 1, timestampMs = 100L)

        val next = floatArrayOf(0.4f)
        val result = smoother.smooth(next, width = 1, height = 1, timestampMs = 240L)

        assertEquals(0.512f, result[0], 0.001f)
        assertEquals(0.512f, next[0], 0.001f)
    }

    @Test
    fun currentEmptyFrameDoesNotRetainAnOpaqueGhostMask() {
        val smoother = TemporalMaskSmoother(currentWeight = 0.72f, maximumGapMs = 450L)
        smoother.smooth(floatArrayOf(1f), width = 1, height = 1, timestampMs = 100L)

        val result = smoother.smooth(floatArrayOf(0f), width = 1, height = 1, timestampMs = 240L)

        assertEquals(0.28f, result[0], 0.001f)
        assertFalse(result[0] >= FilterSettings.DEFAULT_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun stopsBlendingWhenTheFrameGapIsTooLarge() {
        val smoother = TemporalMaskSmoother(currentWeight = 0.72f, maximumGapMs = 450L)
        smoother.smooth(floatArrayOf(1f), width = 1, height = 1, timestampMs = 100L)

        val result = smoother.smooth(floatArrayOf(0f), width = 1, height = 1, timestampMs = 551L)

        assertEquals(0f, result[0], 0f)
    }

    @Test
    fun stopsBlendingWhenTheFrameGeometryChanges() {
        val smoother = TemporalMaskSmoother()
        smoother.smooth(floatArrayOf(1f, 1f), width = 2, height = 1, timestampMs = 100L)

        val result = smoother.smooth(floatArrayOf(0f, 0f, 0f), width = 3, height = 1, timestampMs = 120L)

        assertEquals(listOf(0f, 0f, 0f), result.toList())
    }

    @Test
    fun resetDropsPreviousConfidenceMap() {
        val smoother = TemporalMaskSmoother()
        smoother.smooth(floatArrayOf(1f), width = 1, height = 1, timestampMs = 100L)
        smoother.reset()

        val result = smoother.smooth(floatArrayOf(0f), width = 1, height = 1, timestampMs = 120L)

        assertEquals(0f, result[0], 0f)
    }

    @Test
    fun outputReusesTheCurrentInferenceBuffer() {
        val smoother = TemporalMaskSmoother()
        val current = floatArrayOf(0.5f)
        val result = smoother.smooth(current, width = 1, height = 1, timestampMs = 100L)
        assertSame(current, result)
    }
}
