package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test
import org.connectbot.terminal.MAX_ENCODED_USER_LINE_CHARS

class DictationPhaseTest {
    @Test fun onlyRecordingShowsActiveBackground() {
        assertEquals(listOf(DictationPhase.RECORDING), DictationPhase.entries.filter { it.isRecording })
    }

    @Test fun secondTapSubmitsOnlyAStartedOrCappedRecording() {
        assertEquals(listOf(DictationPhase.RECORDING, DictationPhase.CAPTURED),
            DictationPhase.entries.filter { it.acceptsStopAndSend })
    }

    @Test fun ignoredOrIncompleteAudioNeverSubmits() {
        assertFalse(recordedDictationCanSubmit(false, 0))
        assertFalse(recordedDictationCanSubmit(true, 23))
        assertFalse(recordedDictationCanSubmit(true, null))
        assertTrue(recordedDictationCanSubmit(true, 0))
    }

    @Test fun segmentsJoinInOrderIntoOneSanitizedLine() {
        val segments = RecordedDictationSegments()
        segments.add(listOf("First part", "alternate hypothesis"))
        segments.add(listOf("second\npart"))
        assertEquals("First part second part", segments.result())
    }

    @Test fun oversizedSegmentsNeverSendATruncatedCommand() {
        val segments = RecordedDictationSegments()
        segments.add(listOf("x".repeat(MAX_ENCODED_USER_LINE_CHARS)))
        segments.add(listOf("overflow"))
        segments.add(listOf("late segment"))
        assertNull(segments.result())
    }
}
