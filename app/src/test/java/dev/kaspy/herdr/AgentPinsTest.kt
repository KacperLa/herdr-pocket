package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentPinsTest {
    private val pin="SHA256:"+"A".repeat(43)
    private val config=SshConfig("Example.TEST",22,"agent",pin)
    private fun scope(cfg:SshConfig=config,backend:AgentBackend=AgentBackend.TUI,session:String?=null) =
        checkNotNull(AgentPinScope.from(cfg,backend,session))

    @Test fun scopedPinsRestoreAndCannotCrossBackendsHostsLoginsOrSessions() {
        val state=mutableMapOf<String,Set<String>>()
        fun reopen()=AgentPins({state[it].orEmpty()},{key,entries -> state[key]=entries.toSet()})
        val initial=scope()
        assertTrue(reopen().toggle(initial,"term_a"))
        assertEquals(setOf("term_a"),reopen().values(scope(config.copy(host="example.test"),session="default")))
        for(other in listOf(scope(config.copy(host="other.test")),scope(config.copy(user="other")),
            scope(config.copy(port=2222)),scope(config.copy(expectedPin=sshSha256Fingerprint(byteArrayOf(1)))),
            scope(backend=AgentBackend.CORE),scope(session="office"),scope(session="Default"))) {
            assertTrue(reopen().values(other).isEmpty())
        }
        assertNull(AgentPinScope.from(config.copy(expectedPin=null),AgentBackend.TUI,null))
        assertTrue(reopen().toggle(initial,"term_a"))
        assertTrue(reopen().values(initial).isEmpty())
        assertFalse(reopen().toggle(initial,"bad\naddress"))
    }

    @Test fun pinsFollowOpaqueAddressesAndDoNotBypassFiltering() {
        val done=CoreSession("t1","Same","live","Codex","done")
        val blocked=CoreSession("t2","Same","live","Codex","blocked")
        val pinned=done.copy(name="Renamed")
        val old=CoreSession("t3","Other","live","Codex","working")
        val roster=listOf(old,blocked,pinned)
        val pins=setOf("t1")
        assertEquals(listOf(pinned,blocked,old),filteredAgentRoster(roster,"",pinned=pins))
        assertEquals(listOf(blocked,pinned,old),filteredAgentRoster(roster,""))
        assertEquals(listOf(blocked),filteredAgentRoster(roster,"",SpaceStatusFilter.BLOCKED,pins))
        assertEquals(listOf(blocked),filteredAgentRoster(roster,"Same",pinned=pins))
        assertEquals(listOf(pinned),filteredAgentRoster(roster,"Renamed",pinned=pins))
        assertEquals(roster,spacesRoster(roster).single().rows)
    }
}
