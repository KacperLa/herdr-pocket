package dev.kaspy.herdr

internal enum class DictationPhase {
    PERMISSION, WAITING_FOR_RESUME, STARTING, RECORDING, STOPPING, CAPTURED, TRANSCRIBING, RETRYING, SUBMITTING;

    val acceptsStopAndSend get() = this == RECORDING || this == CAPTURED
    val isRecording get() = this == RECORDING
}

/** Do not submit partial segments, or a mic-based result from a provider ignoring the audio source. */
internal fun recordedDictationCanSubmit(wroteAllAudio: Boolean, unreadBytes: Int?): Boolean =
    wroteAllAudio && unreadBytes == 0

private const val PCM_BYTES_PER_SECOND = 32_000

/** Pace full chunks without letting delays accumulate into a catch-up burst. */
internal fun dictationReplayDelayNanos(bytes: Int, realTime: Boolean): Long =
    bytes.toLong() * 1_000_000_000L / PCM_BYTES_PER_SECOND / if (realTime) 1L else 2L

internal fun dictationReplayTimeoutMillis(bytes: Int, realTime: Boolean): Long =
    dictationReplayDelayNanos(bytes, realTime) / 1_000_000L + 30_000L
