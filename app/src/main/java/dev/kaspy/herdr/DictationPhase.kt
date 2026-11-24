package dev.kaspy.herdr

internal enum class DictationPhase {
    PERMISSION, WAITING_FOR_RESUME, CHECKING, STARTING, LISTENING, FINISHING, SUBMITTING;

    val isRecording get() = this == LISTENING

    // RecognitionListener readiness (or beginning of speech) starts the recording indicator.
    fun speechReady() = if (this == STARTING) LISTENING else this

    // Do not light up again if the provider reports duplicate or out-of-order callbacks.
    fun speechEnded() = if (this == STARTING || this == LISTENING) FINISHING else this
}
