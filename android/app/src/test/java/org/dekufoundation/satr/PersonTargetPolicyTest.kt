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
    @Test
    fun genderModeLeavesOnlyHighConfidenceMatchingPresentationUnblurred() {
        val male = PersonInstanceMask(
            trackId = 30L,
            width = 6,
            height = 1,
            pixels = booleanArrayOf(true, true, false, false, false, false),
            confidence = 0.95f,
            presentation = PersonPresentation.MALE_PRESENTING,
            presentationConfidence = 0.93f,
        )
        val female = PersonInstanceMask(
            trackId = 40L,
            width = 6,
            height = 1,
            pixels = booleanArrayOf(false, false, true, true, false, false),
            confidence = 0.95f,
            presentation = PersonPresentation.FEMALE_PRESENTING,
            presentationConfidence = 0.95f,
        )
        val uncertain = PersonInstanceMask(
            trackId = 50L,
            width = 6,
            height = 1,
            pixels = booleanArrayOf(false, false, false, false, true, true),
            confidence = 0.90f,
            presentation = PersonPresentation.MALE_PRESENTING,
            presentationConfidence = 0.70f,
        )
        val menMode = PersonTargetPolicy.composeMask(
            width = 6,
            height = 1,
            instances = listOf(male, female, uncertain),
            mode = PersonTargetMode.SHOW_MALE_PRESENTING_ONLY,
            selectedTrackIds = emptySet(),
        )
        assertEquals(listOf(false, false, true, true, true, true), menMode.toList())

        val womenMode = PersonTargetPolicy.composeMask(
            width = 6,
            height = 1,
            instances = listOf(male, female, uncertain),
            mode = PersonTargetMode.SHOW_FEMALE_PRESENTING_ONLY,
            selectedTrackIds = emptySet(),
        )
        assertEquals(listOf(true, true, false, false, true, true), womenMode.toList())
    }

    @Test
    fun manualCorrectionOverridesModelPresentationAndUnknownRemainsBlurred() {
        val modelSaysFemale = first.copy(
            presentation = PersonPresentation.FEMALE_PRESENTING,
            presentationConfidence = 0.99f,
            manualPresentation = PersonPresentation.MALE_PRESENTING,
        )
        val corrected = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(modelSaysFemale),
            mode = PersonTargetMode.SHOW_MALE_PRESENTING_ONLY,
            selectedTrackIds = emptySet(),
        )
        assertEquals(List(8) { false }, corrected.toList())

        val forcedUnknown = first.copy(
            presentation = PersonPresentation.MALE_PRESENTING,
            presentationConfidence = 0.99f,
            manualPresentation = PersonPresentation.UNKNOWN,
        )
        val unknown = PersonTargetPolicy.composeMask(
            width = 4,
            height = 2,
            instances = listOf(forcedUnknown),
            mode = PersonTargetMode.SHOW_MALE_PRESENTING_ONLY,
            selectedTrackIds = emptySet(),
        )
        assertEquals(first.pixels.toList(), unknown.toList())
    }

}
