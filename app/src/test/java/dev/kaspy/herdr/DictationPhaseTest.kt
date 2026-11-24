package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPhaseTest {
    @Test fun onlyListeningShowsActiveBackground() {
        assertEquals(listOf(DictationPhase.LISTENING), DictationPhase.entries.filter { it.isRecording })
    }

    @Test fun providerReadinessStartsAndSpeechEndStopsTheIndicator() {
        val starting=DictationPhase.STARTING
        val listening=starting.speechReady()
        assertFalse(starting.isRecording)
        assertTrue(listening.isRecording)
        assertEquals(DictationPhase.FINISHING,listening.speechEnded())
        assertFalse(listening.speechEnded().isRecording)
    }

    @Test fun lateReadinessDoesNotRelightAfterSpeechEnd() {
        assertFalse(DictationPhase.STARTING.speechEnded().speechReady().isRecording)
        assertEquals(DictationPhase.FINISHING,DictationPhase.FINISHING.speechReady())
        assertEquals(DictationPhase.CHECKING,DictationPhase.CHECKING.speechEnded())
    }
}
