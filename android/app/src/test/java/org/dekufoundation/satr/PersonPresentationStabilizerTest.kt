package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonPresentationStabilizerTest {
    @Test
    fun requiresTwoHighConfidenceMatchingPredictions() {
        val stabilizer = PersonPresentationStabilizer()
        assertEquals(
            PersonPresentation.UNKNOWN,
            stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.95f, 100L).presentation,
        )
        assertEquals(
            PersonPresentation.MALE_PRESENTING,
            stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.94f, 800L).presentation,
        )
    }

    @Test
    fun lowConfidenceImmediatelyFallsBackToUnknown() {
        val stabilizer = PersonPresentationStabilizer()
        stabilizer.update(12L, PersonPresentation.FEMALE_PRESENTING, 0.94f, 100L)
        stabilizer.update(12L, PersonPresentation.FEMALE_PRESENTING, 0.95f, 800L)
        assertEquals(
            PersonPresentation.UNKNOWN,
            stabilizer.update(12L, PersonPresentation.FEMALE_PRESENTING, 0.72f, 1_500L).presentation,
        )
    }

    @Test
    fun changedPredictionBecomesUnknownUntilConfirmed() {
        val stabilizer = PersonPresentationStabilizer()
        stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.94f, 100L)
        stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.94f, 800L)
        assertEquals(
            PersonPresentation.UNKNOWN,
            stabilizer.update(12L, PersonPresentation.FEMALE_PRESENTING, 0.96f, 1_500L).presentation,
        )
        assertEquals(
            PersonPresentation.FEMALE_PRESENTING,
            stabilizer.update(12L, PersonPresentation.FEMALE_PRESENTING, 0.96f, 2_200L).presentation,
        )
    }

    @Test
    fun unknownPredictionDoesNotRetainPreviousGender() {
        val stabilizer = PersonPresentationStabilizer()
        stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.94f, 100L)
        stabilizer.update(12L, PersonPresentation.MALE_PRESENTING, 0.94f, 800L)
        assertEquals(
            PersonPresentation.UNKNOWN,
            stabilizer.update(12L, PersonPresentation.UNKNOWN, 0f, 1_400L).presentation,
        )
    }
}
