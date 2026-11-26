package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LiveVoicePermissionGateTest {
    @Test fun permissionCannotRetargetOrStartInBackground() {
        val gate=LiveVoicePermissionGate()
        val first=Any()
        val replacement=Any()
        assertTrue(gate.request(first))
        assertFalse(gate.request(replacement))
        assertTrue(gate.result(true,first))
        assertNull(gate.claim(first,resumed=false))
        assertNull(gate.claim(replacement,resumed=true))
        assertNull(gate.claim(first,resumed=true))
    }

    @Test fun waitsForResumeAndConsumesOneGrant() {
        val gate=LiveVoicePermissionGate()
        val first=Any()
        assertTrue(gate.request(first))
        assertTrue(gate.result(true,first))
        assertNull(gate.claim(first,resumed=false))
        assertSame(first,gate.claim(first,resumed=true))
        assertNull(gate.claim(first,resumed=true))
    }
}
