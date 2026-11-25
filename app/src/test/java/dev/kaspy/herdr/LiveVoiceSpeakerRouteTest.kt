package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LiveVoiceSpeakerRouteTest {
    private class Output:LiveVoiceSpeakerRoute.Output {
        var allowsSelection=true
        var allowsRelease=true
        var requested=0
        var released=0
        override fun selectSpeaker():Boolean {
            requested++
            return allowsSelection
        }
        override fun releaseSpeaker() {
            released++
            check(allowsRelease)
        }
    }

    @Test fun defaultsToSystemRouteAndChangesOnlyWhenAsked() {
        val output=Output()
        val route=LiveVoiceSpeakerRoute(output)
        assertFalse(route.selected)
        assertTrue(route.setEnabled(false))
        assertEquals(0,output.released)
        assertTrue(route.setEnabled(true))
        assertTrue(route.selected)
        assertTrue(route.setEnabled(true))
        assertEquals(1,output.requested)
        assertTrue(route.setEnabled(false))
        assertFalse(route.selected)
        assertEquals(1,output.released)
    }

    @Test fun failedSelectionDoesNotActivateTheSwitchAndEndCallStillCleansUp() {
        val output=Output().apply {allowsSelection=false}
        val route=LiveVoiceSpeakerRoute(output)
        assertFalse(route.setEnabled(true))
        assertFalse(route.selected)
        route.endCall()
        assertEquals(1,output.released)
    }

    @Test fun endCallRestoresTheDefaultAndCanRetryACleanupFailure() {
        val output=Output()
        val route=LiveVoiceSpeakerRoute(output)
        assertTrue(route.setEnabled(true))
        output.allowsRelease=false
        assertFalse(route.setEnabled(false))
        assertTrue(route.selected)
        output.allowsRelease=true
        route.endCall()
        assertFalse(route.selected)
        assertEquals(2,output.released)
    }
}
