package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupReconnectTest {
    private val profile = StartupReconnectProfile("example.test", 2202, "agent")
    private val pin = "SHA256:" + "A".repeat(43)

    @Test fun claimsValidProfileOnce() {
        val startup = StartupReconnect()
        assertEquals(profile, startup.claim(profile, rememberedKeyReadable = true, pinnedHostKey = pin))
        assertNull(startup.claim(profile, rememberedKeyReadable = true, pinnedHostKey = pin))
        assertEquals(profile, StartupReconnect().claim(profile, rememberedKeyReadable = true, pinnedHostKey = pin))
    }

    @Test fun discoveryWithoutAllPrerequisitesStillConsumesOpportunity() {
        for ((candidate, readable, fingerprint) in listOf(
            Triple(null, true, pin),
            Triple(profile, false, pin),
            Triple(profile, true, null),
            Triple(profile, true, "not-a-fingerprint"),
            Triple(profile.copy(host = ""), true, pin),
            Triple(profile.copy(port = 0), true, pin),
            Triple(profile.copy(user = " "), true, pin),
            Triple(profile.copy(user = "bad\u0000user"), true, pin),
        )) {
            val startup = StartupReconnect()
            assertNull(startup.claim(candidate, readable, fingerprint))
            assertNull(startup.claim(profile, rememberedKeyReadable = true, pinnedHostKey = pin))
        }
    }

    @Test fun manualActionBeforeDiscoveryPreventsAutomaticClaim() {
        val startup = StartupReconnect()
        startup.cancelAutomatic()
        startup.cancelAutomatic()
        assertNull(startup.claim(profile, rememberedKeyReadable = true, pinnedHostKey = pin))
    }

    @Test fun canonicalizesEndpointAndTrimsUser() {
        val startup = StartupReconnect()
        assertEquals(profile, startup.claim(profile.copy(host = " EXAMPLE.TEST ", user = " agent "), true, pin))
    }

    @Test fun reconnectsOnlyToExactRememberedLiveAddress() {
        val last = CoreSession("box/t4", "last", "live", "", null)
        val main = CoreSession("box/t3", "main", "live", "", null)
        assertEquals(last, startupSession(listOf(main, last), last.address))
        assertNull(startupSession(listOf(main), last.address))
        assertNull(startupSession(listOf(main, last.copy(state = "finished")), last.address))
        assertNull(startupSession(listOf(main, last, last.copy(name = "duplicate")), last.address))
    }

    @Test fun noRememberedAddressUsesExistingCoreSelectionRules() {
        val other = CoreSession("box/t4", "other", "live", "", null)
        val main = CoreSession("box/t3", "main", "live", "", null)
        assertEquals(main, startupSession(listOf(other, main), null))
        assertEquals(other, startupSession(listOf(other), null))
        assertNull(startupSession(listOf(other, main.copy(name = "second")), null))
    }
}
