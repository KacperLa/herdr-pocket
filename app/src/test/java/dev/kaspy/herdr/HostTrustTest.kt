package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class HostTrustTest {
    private val pins=mutableMapOf<HostEndpoint,String>()
    private val trust=HostTrust(pins::get){endpoint,pin -> pins[endpoint]=pin;true}
    private val a=HostEndpoint.parse(" HOST.Example ", "022")!!
    private val pinA="SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val pinB="SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"

    @Test fun firstUseDoesNotSaveUntilExplicitAcceptance() {
        val offer=trust.offer(a,pinA,4)!!
        assertNull(trust.pin(a))
        assertTrue(trust.accept(offer,a,4))
        assertEquals(pinA,trust.pin(a))
        assertNull(trust.offer(a,pinA,5))
    }
    @Test fun changedKeyCannotUseTheFirstUseFlow() {
        assertTrue(trust.accept(trust.offer(a,pinA,1)!!,a,1))
        assertNull(trust.offer(a,pinB,5))
        assertFalse(trust.accept(HostTrustOffer(a,pinB,5),a,5))
        assertEquals(pinA,trust.pin(a))
    }
    @Test fun changedPinStaysPinnedUntilExplicitReplacement() {
        pins[a]=pinA
        val other=HostEndpoint.parse(a.host,"2222")!!
        pins[other]=pinA
        assertNull(trust.replacementOffer(a,pinA,7))
        assertNull(trust.replacementOffer(a,"invalid",7))
        val offer=trust.replacementOffer(a,pinB,7)!!
        assertEquals(pinA,offer.previousPin)
        assertEquals(pinA,trust.pin(a))
        assertFalse(trust.accept(offer,a,8))
        assertFalse(trust.accept(offer,other,7))
        assertTrue(trust.accept(offer,a,7))
        assertFalse(trust.accept(offer,a,7))
        assertEquals(pinB,trust.pin(a))
        assertEquals(pinA,trust.pin(other))
    }
    @Test fun replacementCannotOverwriteAMoreRecentPin() {
        pins[a]=pinA
        val offer=trust.replacementOffer(a,pinB,9)!!
        val pinC="SHA256:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA"
        pins[a]=pinC
        assertFalse(trust.accept(offer,a,9))
        assertEquals(pinC,trust.pin(a))
    }
    @Test fun failedReplacementIsNotReportedAsTrusted() {
        pins[a]=pinA
        val noWrite=HostTrust(pins::get){_,_ -> false}
        assertFalse(noWrite.accept(noWrite.replacementOffer(a,pinB,1)!!,a,1))
        assertEquals(pinA,noWrite.pin(a))
    }
    @Test fun cancelOrEndpointChangeInvalidatesPendingAcceptance() {
        val offer=trust.offer(a,pinA,4)!!
        assertFalse(trust.accept(offer,a,5))
        assertFalse(trust.accept(offer,HostEndpoint.parse("host.example","2222"),4))
        assertFalse(trust.accept(offer,HostEndpoint.parse("other.example","22"),4))
        assertTrue(pins.isEmpty())
    }
    @Test fun legacyPinMigratesOnlyToSavedEndpointWithoutExistingPin() {
        assertEquals(pinA,trust.legacyPinToMigrate(a,"  $pinA "))
        assertNull(trust.legacyPinToMigrate(null,pinA))
        assertNull(trust.legacyPinToMigrate(a,"invalid"))
        assertTrue(trust.accept(trust.offer(a,pinB,1)!!,a,1))
        assertNull(trust.legacyPinToMigrate(a,pinA))
        assertEquals(pinB,trust.pin(a))
    }
    @Test fun pinsAreScopedToNormalizedHostAndNumericPort() {
        assertEquals(a,HostEndpoint.parse("host.example","22"))
        assertEquals(HostEndpoint.parse("[::1]","22"),HostEndpoint.parse("0:0:0:0:0:0:0:1","22"))
        assertEquals("host.example:22",a.display)
        assertTrue(trust.accept(trust.offer(a,pinA,1)!!,a,1))
        assertNull(trust.pin(HostEndpoint.parse("host.example","2222")!!))
        assertNull(trust.pin(HostEndpoint.parse("other.example","22")!!))
        assertNull(trust.offer(HostEndpoint.parse("other.example","22")!!,"invalid",4))
    }
}
