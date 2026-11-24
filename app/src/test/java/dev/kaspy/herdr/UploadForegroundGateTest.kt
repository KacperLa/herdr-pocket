package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class UploadForegroundGateTest {
    @Test fun waitsForExactlyOnePromotionAndDoesNotOverlapUploads() {
        val gate = UploadForegroundGate()
        val token = checkNotNull(gate.claim())
        assertNull(gate.claim())
        assertFalse(gate.isPromoted(token))
        assertTrue(gate.promote(token))
        assertTrue(gate.isPromoted(token))
        assertFalse(gate.promote(token))
        assertTrue(gate.release(token))
        assertFalse(gate.promote(token))
    }

    @Test fun oldPromotionOrShutdownCannotAffectTheReplacement() {
        val gate = UploadForegroundGate()
        val stopped = checkNotNull(gate.claim())
        assertFalse(gate.isPromoted(stopped))
        assertTrue(gate.release(stopped))
        val newer = checkNotNull(gate.claim())
        assertFalse(gate.promote(stopped))
        assertFalse(gate.release(stopped))
        assertTrue(gate.owns(newer))
        assertTrue(gate.promote(newer))
        assertTrue(gate.release(newer))
    }
}
