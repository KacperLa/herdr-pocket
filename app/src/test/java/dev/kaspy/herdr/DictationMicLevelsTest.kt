package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class DictationMicLevelsTest {
    @Test fun emitsOneLevelPerWindowAndSilenceIsFlat() {
        val levels = mutableListOf<Float>()
        val meter = DictationMicLevels { levels.add(it) }
        repeat(DICTATION_LEVEL_WINDOW_SAMPLES - 1) { meter.add(0) }
        assertTrue(levels.isEmpty())
        meter.add(0)
        assertEquals(listOf(0f), levels)
        repeat(DICTATION_LEVEL_WINDOW_SAMPLES) { meter.add(2000) }
        repeat(DICTATION_LEVEL_WINDOW_SAMPLES) { meter.add(-32768) }
        assertTrue(levels[1] in 0.1f..0.9f)
        assertEquals(1f, levels[2])
        repeat(DICTATION_LEVEL_WINDOW_SAMPLES) { meter.add(0) }
        assertEquals(0f, levels.last())
    }

    @Test fun historyDropsTheOldestBarAndRetainsQuietFrames() {
        var history = emptyList<Float>()
        for (level in listOf(0.1f, 0.2f, 0.3f, 0f)) history = appendDictationLevel(history, level, 3)
        assertEquals(listOf(0.2f, 0.3f, 0f), history)
    }

    @Test fun processingReplacesWaveformAndOrdinaryControls() {
        val processing = listOf(DictationPhase.STOPPING, DictationPhase.TRANSCRIBING,
            DictationPhase.RETRYING, DictationPhase.SUBMITTING)
        assertEquals(processing, DictationPhase.entries.filter { it.showsTranscribing })
        assertTrue(processing.all { it.replacesQuickActions && !it.acceptsStopAndSend && !it.isRecording })
        assertTrue(DictationPhase.RECORDING.replacesQuickActions)
        assertFalse(DictationPhase.PERMISSION.replacesQuickActions)
    }
}
