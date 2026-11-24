package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class ReconnectTargetTest {
    private val profile=ReconnectProfile(HostEndpoint.parse("example.invalid","22")!!,"test","pinned","content://fake/key")
    private val target=ReconnectTarget(profile,ReconnectMode.AgentSession(true,"known-agent"))

    @Test fun retryRequiresOriginalProfileAndIdleState() {
        assertTrue(target.isAvailable(profile,false,"custom"))
        assertFalse(target.isAvailable(profile,true,"custom"))
        assertFalse(target.isAvailable(null,false,"custom"))
        assertFalse(target.isAvailable(profile.copy(pin="changed"),false,"custom"))
        assertFalse(target.isAvailable(profile.copy(keyUri="content://different"),false,"custom"))
        assertFalse(target.isAvailable(profile.copy(user="other"),false,"custom"))
        assertFalse(target.isAvailable(profile.copy(endpoint=HostEndpoint.parse("other.invalid","22")!!),false,"custom"))
    }

    @Test fun customRetryNeverReusesEditedCommand() {
        val custom=ReconnectTarget(profile,ReconnectMode.Custom("herdr"))
        assertTrue(custom.isAvailable(profile,false,"herdr"))
        assertFalse(custom.isAvailable(profile,false,"another command"))
    }

    @Test fun overlayShowsReconnectOnlyForTheRetainedVisibleTerminalAndEligibleProfile() {
        val canRetry=target.isAvailable(profile,false,"custom")
        assertNull(terminalRecoveryUi(false,true,true,canRetry))
        assertNull(terminalRecoveryUi(true,false,true,canRetry))
        assertEquals(TerminalRecoveryUi.RECONNECTING,terminalRecoveryUi(true,true,true,false))
        assertEquals(TerminalRecoveryUi.RECONNECT,terminalRecoveryUi(true,true,false,canRetry))
        for(unavailable in listOf(null,profile.copy(pin="replaced"),profile.copy(keyUri="content://revoked"))) {
            val eligible=target.isAvailable(unavailable,false,"custom")
            assertEquals(TerminalRecoveryUi.SETTINGS,terminalRecoveryUi(true,true,false,eligible))
        }
        assertNull(terminalRecoveryUi(false,true,false,canRetry))
    }
}
