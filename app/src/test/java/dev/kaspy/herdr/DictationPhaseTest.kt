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

    @Test fun replayPacesPcmAndAllowsEnoughTimeForLongRecordings() {
        assertEquals(16_000_000L, dictationReplayDelayNanos(1024, realTime = false))
        assertEquals(32_000_000L, dictationReplayDelayNanos(1024, realTime = true))
        assertEquals(180_000L, dictationReplayTimeoutMillis(9_600_000, realTime = false))
        assertEquals(330_000L, dictationReplayTimeoutMillis(9_600_000, realTime = true))
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
