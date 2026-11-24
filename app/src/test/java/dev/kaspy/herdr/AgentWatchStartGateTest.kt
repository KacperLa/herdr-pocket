package dev.kaspy.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWatchStartGateTest {
    @Test fun sameLeaseDoesNotRestartOnRecompositionOrResume() {
        val gate = AgentWatchStartGate()
        val lease = Any()
        assertTrue(gate.claim(lease))
        assertFalse(gate.claim(lease))
    }

    @Test fun newLeaseAndExplicitResetAllowAStart() {
        val gate = AgentWatchStartGate()
        data class Lease(val route: String)
        val first = Lease("default")
        val second = first.copy()
        assertTrue(gate.claim(first))
        assertTrue(gate.claim(second))
        assertFalse(gate.claim(second))
        gate.reset()
        assertTrue(gate.claim(second))
    }
}
