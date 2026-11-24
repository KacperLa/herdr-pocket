package dev.kaspy.herdr

internal enum class DictationPhase {
    PERMISSION, WAITING_FOR_RESUME, STARTING, RECORDING, STOPPING, CAPTURED, TRANSCRIBING, SUBMITTING;

    val acceptsStopAndSend get() = this == RECORDING || this == CAPTURED
    val isRecording get() = this == RECORDING
}

/** Do not submit partial segments, or a mic-based result from a provider ignoring the audio source. */
internal fun recordedDictationCanSubmit(wroteAllAudio: Boolean, unreadBytes: Int?): Boolean =
    wroteAllAudio && unreadBytes == 0
