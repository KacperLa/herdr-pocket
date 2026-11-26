package dev.kaspy.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceDockTest {
    @Test fun terminalTypingControlsYieldToEveryCallPhaseAndReturnAtIdle() {
        assertTrue(LiveVoiceUiPhase.IDLE.showTerminalTypingControls)
        assertFalse(LiveVoiceUiPhase.CONNECTING.showTerminalTypingControls)
        assertFalse(LiveVoiceUiPhase.LISTENING.showTerminalTypingControls)
        assertFalse(LiveVoiceUiPhase.SPEAKING.showTerminalTypingControls)
        assertTrue(LiveVoiceUiPhase.IDLE.showTerminalTypingControls)
    }
}
