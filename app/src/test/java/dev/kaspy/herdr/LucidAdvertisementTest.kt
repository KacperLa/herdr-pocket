package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LucidAdvertisementTest {
    @Test fun routeRequiresAnExactLivePane() {
        val ads=parseLucidAdvertisements("""{"version":1,"sessions":[{"paneId":"w3:pB","label":"OpenCode"},{"paneId":"w3:pC","label":"Codex"}]}""")
        assertEquals(setOf("w3:pB","w3:pC"),ads.keys)
        val session=CoreSession("terminal","Lucid","live","",null,tuiLocation=TuiLocation("terminal","w3:pB","w3:tN","w3"))
        assertTrue(lucidMatches(session,ads))
        assertTrue(lucidMatches(session.copy(tuiLocation=session.tuiLocation?.copy(paneId="w3:pC")),ads))
        assertFalse(lucidMatches(session.copy(tuiLocation=session.tuiLocation?.copy(paneId="w4:pB")),ads))
        assertFalse(lucidMatches(session.copy(state="closed"),ads))
        assertFalse(lucidMatches(session,emptyMap()))
    }

    @Test fun rejectsDuplicateAndUnsafePanesAndUnsupportedVersions() {
        val ads=parseLucidAdvertisements("""{"version":1,"sessions":[{"paneId":"w3:pB"},{"paneId":"w3:pB"},{"paneId":"w3:pB;echo bad"},{"paneId":"w3:pD"}]}""")
        assertEquals(setOf("w3:pD"),ads.keys)
        assertTrue(parseLucidAdvertisements("""{"version":2,"sessions":[{"paneId":"w3:pB"}]}""").isEmpty())
        assertTrue(parseLucidAdvertisements("""{"version":1,"paneId":"w3:pB"}""").isEmpty())
    }
}
