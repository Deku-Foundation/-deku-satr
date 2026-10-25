package org.dekufoundation.satr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonTrackAssociatorTest {
    private fun detection(left: Float, top: Float, right: Float, bottom: Float, score: Float = 0.9f) =
        PersonBoxDetection(NormalizedPersonBox(left, top, right, bottom), score)

    @Test
    fun preservesTrackIdWhenBoxMovesSlightly() {
        val tracker = PersonTrackAssociator()
        val first = tracker.update(listOf(detection(0.10f, 0.10f, 0.30f, 0.60f)), 100L).single()
        val next = tracker.update(listOf(detection(0.12f, 0.11f, 0.32f, 0.61f)), 200L).single()
        assertEquals(first.trackId, next.trackId)
    }

    @Test
    fun matchesTwoBoxesOneToOneWhenDetectionOrderChanges() {
        val tracker = PersonTrackAssociator()
        val first = tracker.update(
            listOf(detection(0.05f, 0.10f, 0.20f, 0.60f), detection(0.70f, 0.12f, 0.90f, 0.62f)),
            100L,
        )
        val next = tracker.update(
            listOf(detection(0.69f, 0.12f, 0.89f, 0.62f), detection(0.06f, 0.10f, 0.21f, 0.60f)),
            200L,
        )
        assertEquals(first[1].trackId, next[0].trackId)
        assertEquals(first[0].trackId, next[1].trackId)
        assertNotEquals(next[0].trackId, next[1].trackId)
    }

    @Test
    fun assignsNewTrackAfterGraceWindowExpires() {
        val tracker = PersonTrackAssociator(maxGapMs = 200L)
        val first = tracker.update(listOf(detection(0.1f, 0.1f, 0.3f, 0.6f)), 100L).single()
        val next = tracker.update(listOf(detection(0.1f, 0.1f, 0.3f, 0.6f)), 301L).single()
        assertNotEquals(first.trackId, next.trackId)
    }

    @Test
    fun rejectsNonMonotonicTimestamp() {
        val tracker = PersonTrackAssociator()
        tracker.update(listOf(detection(0.1f, 0.1f, 0.3f, 0.6f)), 200L)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.update(emptyList(), 199L)
        }
    }

    @Test
    fun rejectsInvalidBoxesAndScores() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedPersonBox(0.2f, 0.1f, 0.1f, 0.6f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedPersonBox(Float.NaN, 0f, 0.5f, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            detection(0f, 0f, 0.5f, 0.5f, Float.POSITIVE_INFINITY)
        }
    }
}
