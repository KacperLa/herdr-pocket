package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentWatchTapTokensTest {
    @Test fun onlyKnownUnexpiredTokenOpensOnce() {
        val store=AgentWatchTapTokens<String>(capacity=2,lifetimeMs=20)
        store.put("first","original host and session",10)
        assertNull(store.take("forged",11))
        assertEquals("original host and session",store.take("first",11))
        assertNull(store.take("first",12))
        store.put("expired","old pane",10)
        assertNull(store.take("expired",30))
    }

    @Test fun newerAlertsKeepStoreBoundedAndReplacedTokensCanBeRevoked() {
        val store=AgentWatchTapTokens<Int>(capacity=2,lifetimeMs=20)
        store.put("first",1,10)
        store.put("second",2,11)
        store.put("third",3,12)
        assertNull(store.take("first",12))
        store.remove("second")
        assertNull(store.take("second",12))
        assertEquals(3,store.take("third",12))
    }
}
