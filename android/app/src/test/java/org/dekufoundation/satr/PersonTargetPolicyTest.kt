package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonTargetPolicyTest {
    private val first = PersonInstanceMask(
        trackId = 10L,
        width = 4,
        height = 2,
        pixels = booleanArrayOf(true, true, false, false, true, true, false, false),
        confidence = 0.92f,
    )
    private val second = PersonInstanceMask(
        trackId = 20L,
        width = 4,
        height = 2,
        pixels = booleanArrayOf(false, false, true, true, false, false, true, true),
        confidence = 0.88f,
    )

    @Test
    fun conservativeDefaultMasksEveryDetectedInstance() {
        val result = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(first, second),
            mode = PersonTargetMode.MASK_ALL,
            selectedTrackIds = emptySet(),
        )
        assertEquals(List(8) { true }, result.toList())
    }

    @Test
    fun selectedOnlyMasksOnlyTheExplicitlySelectedTrack() {
        val result = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(first, second),
            mode = PersonTargetMode.MASK_SELECTED_ONLY,
            selectedTrackIds = setOf(20L),
        )
        assertEquals(second.pixels.toList(), result.toList())
    }

    @Test
    fun allExceptSelectedKeepsTheSelectedTrackOutOfTheMask() {
        val result = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(first, second),
            mode = PersonTargetMode.MASK_ALL_EXCEPT_SELECTED,
            selectedTrackIds = setOf(10L),
        )
        assertEquals(second.pixels.toList(), result.toList())
        assertFalse(result[0])
        assertTrue(result[2])
    }

    @Test
    fun selectedOnlyRejectsAnEmptySelection() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonTargetPolicy.composeMask(
                width = 4,
                height = 2,
                instances = listOf(first, second),
                mode = PersonTargetMode.MASK_SELECTED_ONLY,
                selectedTrackIds = emptySet(),
            )
        }
    }

    @Test
    fun selectedOnlyFallsBackToAllMasksIfSelectedTracksAreMissingFromFrame() {
        val result = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(first, second),
            mode = PersonTargetMode.MASK_SELECTED_ONLY,
            selectedTrackIds = setOf(999L),
        )
        assertEquals(List(8) { true }, result.toList())
    }

    @Test
    fun rejectsDuplicateTrackIdsAndInvalidMaskShape() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonTargetPolicy.composeMask(
                4, 2, listOf(first, first), PersonTargetMode.MASK_ALL, emptySet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonTargetPolicy.composeMask(
                4, 2, listOf(first.copy(width = 3)), PersonTargetMode.MASK_ALL, emptySet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonTargetPolicy.composeMask(
                4, 2, listOf(first.copy(confidence = Float.NaN)), PersonTargetMode.MASK_ALL, emptySet(),
            )
        }
    }
}
