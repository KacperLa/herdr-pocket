package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionBackDestinationTest {
    private val pty = Any()
    @Test fun liveCoreConnectionReturnsToAgents() {
        assertEquals(ConnectionBackDestination.AGENTS, connectionBackDestination(true, false, false, pty, pty, "agent"))
    }
    @Test fun liveCustomConnectionReturnsToTerminal() {
        assertEquals(ConnectionBackDestination.TERMINAL, connectionBackDestination(true, false, false, pty, pty, null))
    }
    @Test fun disconnectedOrPendingConnectionHasNoBackAction() {
        assertNull(connectionBackDestination(false, false, false, pty, pty, "agent"))
        assertNull(connectionBackDestination(false, false, false, null, null, null))
        assertNull(connectionBackDestination(true, false, false, null, null, null))
    }
    @Test fun aClosedOrReplacedPtyDoesNotRouteBack() {
        assertNull(connectionBackDestination(true, false, false, pty, null, "agent"))
        assertNull(connectionBackDestination(true, false, false, pty, Any(), "agent"))
    }
    @Test fun backActionOnlyAppearsInSettings() {
        assertNull(connectionBackDestination(true, true, false, pty, pty, "agent"))
        assertNull(connectionBackDestination(true, true, true, pty, pty, "agent"))
    }
}
