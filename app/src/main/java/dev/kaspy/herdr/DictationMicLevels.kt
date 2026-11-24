package dev.kaspy.herdr

import kotlin.math.log10
import kotlin.math.sqrt

internal const val DICTATION_LEVEL_WINDOW_SAMPLES = 640 // 40 ms at 16 kHz.

/** Non-overlapping RMS windows contain only a level, never raw samples. */
internal class DictationMicLevels(private val emit: (Float) -> Unit) {
    private var count = 0
    private var energy = 0L

    fun add(sample: Int) {
        energy += sample.toLong() * sample
        if (++count != DICTATION_LEVEL_WINDOW_SAMPLES) return
        val rms = sqrt(energy.toDouble() / count) / 32768.0
        val level = if (rms == 0.0) 0f else
            ((20.0 * log10(rms) + 54.0) / 44.0).coerceIn(0.0, 1.0).toFloat()
        energy = 0
        count = 0
        emit(level)
    }
}

/** A bounded history; the newest sample is on the right. */
internal fun appendDictationLevel(history: List<Float>, next: Float, capacity: Int = 192): List<Float> =
    if (history.size < capacity) history + next else history.takeLast(capacity - 1) + next
