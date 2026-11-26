package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LiveExtractionWindowTest {
    @Test fun ignoresExactlyTheRequestedCapturedRows() {
        val raw="Old turn\nCurrent result\n\n› Input placeholder\nFooter"
        assertEquals(raw,LiveExtractionWindow.crop(raw,0))
        assertEquals("Old turn\nCurrent result",LiveExtractionWindow.crop(raw,2))
        assertEquals("Old turn",LiveExtractionWindow.crop(raw,4))
        assertEquals("",LiveExtractionWindow.crop(raw,5))
        assertEquals(20,LiveExtractionWindow.normalize(Int.MAX_VALUE))
    }

    @Test fun preferenceIsStableForPaneButIsolatedAcrossPanesHostsAndSessions() {
        val config=SshConfig("Example.test",22,"me","SHA256:"+"A".repeat(43))
        fun key(cfg:SshConfig=config,session:String?=null,address:String="term_one")=
            LiveExtractionWindow.preferenceKey(checkNotNull(AgentPinScope.from(cfg,AgentBackend.TUI,session)),address)
        assertEquals(key(),key(config.copy(host="example.test"),"default"))
        assertNotEquals(key(),key(address="term_two"))
        assertNotEquals(key(),key(cfg=config.copy(user="someone-else")))
        assertNotEquals(key(),key(cfg=config.copy(host="other.test")))
        assertNotEquals(key(),key(cfg=config.copy(expectedPin=sshSha256Fingerprint(byteArrayOf(1)))))
        assertNotEquals(key(),key(session="office"))
        assertFalse(key().contains("term_one"))
    }
}
