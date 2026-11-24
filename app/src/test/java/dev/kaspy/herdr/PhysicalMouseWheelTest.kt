package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalMouseWheelTest {
    @Test fun accumulatesFractionsInEitherDirection() {
        val wheel = PhysicalMouseWheel()
        val terminal = Any()
        assertEquals(0, wheel.update(terminal, .75f))
        assertEquals(1, wheel.update(terminal, .5f))
        assertEquals(0, wheel.update(terminal, -.75f))
        assertEquals(-1, wheel.update(terminal, -.75f))
        assertEquals(0, wheel.update(terminal, -.5f))
        assertEquals(-1, wheel.update(terminal, -.25f))
    }

    @Test fun equalButDistinctTargetsAndExplicitResetDiscardRemainder() {
        val wheel = PhysicalMouseWheel()
        data class Route(val id: Int)
        val old = Route(1)
        val replacement = Route(1)
        assertEquals(0, wheel.update(old, .75f))
        assertEquals(0, wheel.update(replacement, .75f))
        assertEquals(1, wheel.update(replacement, .25f))
        assertEquals(0, wheel.update(replacement, -.75f))
        wheel.reset()
        assertEquals(0, wheel.update(replacement, -.75f))
    }

    @Test fun badInputDoesNotPoisonRemainderAndHugeDeltasAreBounded() {
        val wheel = PhysicalMouseWheel()
        val terminal = Any()
        assertEquals(0, wheel.update(terminal, .75f))
        for (bad in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, wheel.update(terminal, bad))
        }
        assertEquals(1, wheel.update(terminal, .25f))
        assertEquals(12, wheel.update(terminal, Float.MAX_VALUE))
        assertEquals(-12, wheel.update(terminal, -Float.MAX_VALUE))
        assertEquals(0, wheel.update(terminal, .25f))
    }
}
