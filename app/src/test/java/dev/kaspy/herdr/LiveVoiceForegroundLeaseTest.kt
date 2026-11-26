package dev.kaspy.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveVoiceForegroundLeaseTest {
    @Test fun callRequiresForegroundPromotionAndSurvivesOwnerRebinding() = runBlocking {
        val gate = LiveVoiceForegroundLeaseGate()
        val ended = mutableListOf<LiveVoiceForegroundLeaseGate.End>()
        val beforeRotation = requireNotNull(gate.claim { ended.add(it) })
        assertFalse(beforeRotation.ready.isCompleted)
        assertFalse(gate.isPromoted(beforeRotation.token))
        assertTrue(gate.promote(beforeRotation.token))
        assertTrue(beforeRotation.ready.await())
        // A replaced Activity may only read/reuse the retained call.
        assertNull(gate.claim { error("Must not start a duplicate call") })
        assertTrue(gate.isPromoted(beforeRotation.token))
        assertTrue(ended.isEmpty())
        assertTrue(gate.clear(beforeRotation.token, LiveVoiceForegroundLeaseGate.End.USER))
        assertEquals(listOf(LiveVoiceForegroundLeaseGate.End.USER), ended)
        assertFalse(gate.isPromoted(beforeRotation.token))
    }

    @Test fun canceledStartAndStaleStopCannotTouchTheNewCall() = runBlocking {
        val gate = LiveVoiceForegroundLeaseGate()
        val old = requireNotNull(gate.claim { error("A failed promotion is handled by its start") })
        assertTrue(gate.clear(old.token, LiveVoiceForegroundLeaseGate.End.LOST))
        assertFalse(old.ready.await())
        val ended = mutableListOf<LiveVoiceForegroundLeaseGate.End>()
        val next = requireNotNull(gate.claim { ended.add(it) })
        assertFalse(gate.promote(old.token))
        assertTrue(gate.promote(next.token))
        assertFalse(gate.clear(old.token, LiveVoiceForegroundLeaseGate.End.USER))
        assertTrue(gate.isPromoted(next.token))
        assertTrue(gate.clear(next.token, LiveVoiceForegroundLeaseGate.End.LOST))
        assertFalse(gate.clear(next.token, LiveVoiceForegroundLeaseGate.End.LOST))
        assertEquals(listOf(LiveVoiceForegroundLeaseGate.End.LOST), ended)
    }
}
