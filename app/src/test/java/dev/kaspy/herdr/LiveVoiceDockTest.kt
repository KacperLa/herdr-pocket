package dev.kaspy.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceDockTest {
    @Test fun theAudioSlotSwapsWithoutAddingAControl() {
        assertFalse(LiveVoiceUiPhase.IDLE.showsSpeakerSlot)
        assertTrue(LiveVoiceUiPhase.CONNECTING.showsSpeakerSlot)
        assertTrue(LiveVoiceUiPhase.LISTENING.showsSpeakerSlot)
        assertTrue(LiveVoiceUiPhase.SPEAKING.showsSpeakerSlot)
    }
    @Test fun speakerActivationWaitsForTheCallToConnect() {
        assertFalse(LiveVoiceUiPhase.IDLE.canSwitchSpeaker)
        assertFalse(LiveVoiceUiPhase.CONNECTING.canSwitchSpeaker)
        assertTrue(LiveVoiceUiPhase.LISTENING.canSwitchSpeaker)
        assertTrue(LiveVoiceUiPhase.SPEAKING.canSwitchSpeaker)
    }
}
