package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMaskMathTest {
    @Test
    fun thresholdProducesForegroundMask() {
        val result = PersonMaskMath.thresholdAndDilate(
            confidence = floatArrayOf(0.1f, 0.47f, 0.48f, 0.9f),
            width = 4,
            height = 1,
            threshold = 0.48f,
            radiusX = 0,
            radiusY = 0,
        )
        assertEquals(listOf(false, false, true, true), result.toList())
    }

    @Test
    fun dilationExpandsSinglePixel() {
        val values = FloatArray(25)
        values[12] = 1f
        val result = PersonMaskMath.thresholdAndDilate(
            values, width = 5, height = 5, threshold = 0.5f, radiusX = 1, radiusY = 1
        )
        assertEquals(9, result.count { it })
        assertTrue(result[6])
        assertTrue(result[12])
        assertTrue(!result[0])
    }

    @Test
    fun coveragePercentIsStable() {
        assertEquals(40, PersonMaskMath.coveragePercent(booleanArrayOf(true, false, true, false, false)))
        assertEquals(0, PersonMaskMath.coveragePercent(booleanArrayOf()))
    }


    @Test
    fun clampsLargeDilationRadiiToMaskBounds() {
        val result = PersonMaskMath.thresholdAndDilate(
            confidence = floatArrayOf(0f, 1f, 0f),
            width = 3,
            height = 1,
            threshold = 0.5f,
            radiusX = Int.MAX_VALUE,
            radiusY = Int.MAX_VALUE,
        )
        assertEquals(listOf(true, true, true), result.toList())
    }

    @Test
    fun rejectsOversizedDimensionsBeforeAllocatingOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonMaskMath.thresholdAndDilate(floatArrayOf(0f), 50_000, 50_000)
        }
    }

    @Test
    fun rejectsMismatchedMaskDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonMaskMath.thresholdAndDilate(floatArrayOf(0f, 1f), 2, 2)
        }
    }
}
