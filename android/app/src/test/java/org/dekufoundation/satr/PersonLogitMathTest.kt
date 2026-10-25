package org.dekufoundation.satr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonLogitMathTest {
    @Test fun retainsPixelsThatCouldPassMinimumThreshold() {
        assertTrue(PersonLogitMath.canReachMinimumThreshold(0f, 0f))
        assertTrue(PersonLogitMath.canReachMinimumThreshold(-1f, 0f))
    }

    @Test fun prunesBackgroundPixelsBelowMinimumThreshold() {
        assertFalse(PersonLogitMath.canReachMinimumThreshold(-2f, 0f))
    }

    @Test fun rejectsNonFiniteLogits() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonLogitMath.canReachMinimumThreshold(Float.NaN, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonLogitMath.canReachMinimumThreshold(0f, Float.POSITIVE_INFINITY)
        }
    }

    @Test fun rejectsMaximumBelowPersonLogit() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonLogitMath.canReachMinimumThreshold(1f, 0f)
        }
    }
}
