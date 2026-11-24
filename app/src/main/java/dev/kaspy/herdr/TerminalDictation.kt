package dev.kaspy.herdr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.AudioFormat
import android.widget.Toast
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun TerminalDictationControls(
    enabled: Boolean,
    terminalIdentity: () -> Any?,
    send: (Any, String, () -> Boolean, (Boolean) -> Unit) -> Boolean,
    shortcuts: @Composable (@Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTerminal by rememberUpdatedState({ if (enabled) terminalIdentity() else null })
    val currentSend by rememberUpdatedState(send)
    val dictation = remember(context, lifecycle) {
        TerminalDictation(context, { currentTerminal() }, {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }) { terminal, text, allowed, completed -> currentSend(terminal, text, allowed, completed) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        dictation.permissionResult(it)
    }
    DisposableEffect(dictation, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> dictation.startWhenResumed()
                Lifecycle.Event.ON_PAUSE -> dictation.pause()
                Lifecycle.Event.ON_DESTROY -> dictation.dispose()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            dictation.dispose()
        }
    }

    // Observe Compose-backed identity changes immediately and also poll the non-Compose SSH channel.
    val visibleTerminal = if (enabled) terminalIdentity() else null
    SideEffect { dictation.cancelIfTargetChanged(visibleTerminal) }
    val take = dictation.take
    LaunchedEffect(dictation, take) {
        if (take == null) return@LaunchedEffect
        while (dictation.take === take) {
            dictation.cancelIfTargetChanged(currentTerminal())
            delay(100)
        }
    }
    val phase = dictation.phase
    LaunchedEffect(dictation, take, phase) {
        if (take == null) return@LaunchedEffect
        val timeout = when (phase) {
            DictationPhase.STARTING, DictationPhase.STOPPING, DictationPhase.SUBMITTING -> 8_000L
            DictationPhase.TRANSCRIBING -> 120_000L
            else -> return@LaunchedEffect
        }
        delay(timeout)
        dictation.timedOut(take, phase)
    }

    val description = when (phase) {
        DictationPhase.RECORDING, DictationPhase.CAPTURED -> "Stop recording, transcribe, and send with Enter"
        DictationPhase.PERMISSION -> "Waiting for microphone permission"
        null -> "Start dictation recording"
        else -> "Cancel terminal dictation"
    }
    val message = when (phase) {
        DictationPhase.PERMISSION -> "Waiting for microphone permission."
        DictationPhase.WAITING_FOR_RESUME -> "Waiting for the terminal to return to the foreground."
        DictationPhase.STARTING -> "Starting the microphone."
        DictationPhase.RECORDING -> "Recording. Pauses are OK. Tap again to transcribe and send."
        DictationPhase.STOPPING -> "Stopping the microphone."
        DictationPhase.CAPTURED -> "Recording reached its five-minute limit. Tap to transcribe and send."
        DictationPhase.TRANSCRIBING -> "Transcribing the recording on this device."
        DictationPhase.SUBMITTING -> "Submitting recognized text to the active terminal."
        null -> dictation.notice ?: "Ready for on-device dictation."
    }
    shortcuts {
        IconButton(
            onClick = { dictation.tap { permission.launch(Manifest.permission.RECORD_AUDIO) } },
            enabled = !dictation.permissionPending,
            modifier = Modifier.size(DockTargetSize).semantics {
                contentDescription = description
                stateDescription = message
                liveRegion = LiveRegionMode.Polite
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (phase?.isRecording == true) Color(0xFFB8202D) else Color.Transparent,
                contentColor = Color.White,
            ),
        ) {
            val color = if (dictation.permissionPending) Color.White.copy(alpha = .38f) else Color.White
            Icon(painterResource(if (phase?.acceptsStopAndSend == true) R.drawable.ic_terminal_record_stop
                else R.drawable.ic_terminal_mic),contentDescription=null,
                tint=color,modifier=Modifier.size(DockGlyphSize))
        }
    }
}

/** Main-thread only, as required by SpeechRecognizer and its listener. */
private class TerminalDictation(
    private val context: Context,
    private val terminalIdentity: () -> Any?,
    private val resumed: () -> Boolean,
    private val send: (Any, String, () -> Boolean, (Boolean) -> Unit) -> Boolean,
) {
    var take by mutableStateOf<TerminalDictationTicket?>(null)
        private set
    var phase by mutableStateOf<DictationPhase?>(null)
        private set
    var permissionPending by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var capture: DictationCapture? = null
    private var captured: DictationPcm? = null
    private var audioPipe: DictationAudioPipe? = null

    fun tap(requestPermission: () -> Unit) {
        if (permissionPending) return
        take?.let { ticket ->
            when (phase) {
                DictationPhase.RECORDING -> {
                    phase = DictationPhase.STOPPING
                    capture?.finish()
                }
                DictationPhase.CAPTURED -> {
                    val pcm = captured
                    captured = null
                    if (pcm != null) beginTranscription(ticket, pcm)
                    else cancel("No recording was available. Nothing was sent.", showToast = true)
                }
                else -> cancel("Dictation cancelled. Nothing was sent.")
            }
            return
        }
        val terminal = terminalIdentity()
        if (terminal == null) {
            notice = "Connect to a live SSH terminal to use dictation."
            return
        }
        notice = null
        if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) {
            notice = "This device has no on-device speech recognition service. Herdr won’t use cloud dictation."
            return
        }

        val granted = hasMicPermission()
        take = TerminalDictationTicket(terminal, granted)
        if (granted) {
            phase = DictationPhase.WAITING_FOR_RESUME
            startWhenResumed()
        } else {
            phase = DictationPhase.PERMISSION
            permissionPending = true
            try {
                requestPermission()
            } catch (_: RuntimeException) {
                permissionPending = false
                cancel("Could not request microphone access. Check Herdr’s permissions in Android Settings.")
            }
        }
    }

    fun permissionResult(granted: Boolean) {
        permissionPending = false
        val ticket = take ?: return // A recreated activity must not resume a previous recording.
        if (phase != DictationPhase.PERMISSION) return
        when (ticket.permissionResult(granted, terminalIdentity())) {
            DictationPermissionResult.DENIED ->
                cancel("Microphone access denied. Allow it in Android Settings → Apps → Herdr Pocket → Permissions.")
            DictationPermissionResult.STALE ->
                cancel("The terminal changed while waiting for microphone access. Tap the mic to start again.")
            DictationPermissionResult.READY -> {
                phase = DictationPhase.WAITING_FOR_RESUME
                startWhenResumed()
            }
        }
    }

    fun startWhenResumed() {
        val ticket = take ?: return
        if (phase != DictationPhase.WAITING_FOR_RESUME ||
            !ticket.claimStart(terminalIdentity(), resumed())) return
        if (!hasMicPermission()) {
            cancel("Microphone access is needed. Check Herdr’s microphone permission in Android Settings.")
            return
        }

        phase = DictationPhase.STARTING
        val worker = DictationCapture(context.mainExecutor, onStarted = {
            if (take === ticket && phase == DictationPhase.STARTING && stillHere(ticket)) {
                phase = DictationPhase.RECORDING
            }
        }, onComplete = { pcm, capped ->
            capture = null
            if (take !== ticket || !stillHere(ticket)) {
                pcm?.close()
            } else if (pcm == null) {
                cancel("Couldn’t record audio. Check Android microphone access and try again.", showToast = true)
            } else if (phase == DictationPhase.STOPPING) {
                beginTranscription(ticket, pcm)
            } else if (capped && phase == DictationPhase.RECORDING) {
                captured = pcm
                phase = DictationPhase.CAPTURED
                toast("Five-minute recording limit reached. Tap the stop button to transcribe and send.")
            } else {
                pcm.close()
                cancel("Recording stopped unexpectedly. Nothing was sent.", showToast = true)
            }
        })
        capture = worker
        try {
            worker.start()
        } catch (_: RuntimeException) {
            cancel("Couldn’t start the recorder. Nothing was sent.", showToast = true)
        }
    }

    private fun stillHere(ticket: TerminalDictationTicket): Boolean =
        resumed() && terminalIdentity() === ticket.terminal

    private fun beginTranscription(ticket: TerminalDictationTicket, pcm: DictationPcm) {
        if (!stillHere(ticket)) {
            pcm.close()
            cancel("The active terminal changed. Nothing was sent.")
            return
        }
        phase = DictationPhase.TRANSCRIBING
        try {
            val pipe = DictationAudioPipe.open(pcm)
            audioPipe = pipe
            val client = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = client
            client.setRecognitionListener(listener(ticket))
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe.source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, DICTATION_HZ)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            client.startListening(intent)
            pipe.start(context.mainExecutor) {
                if (take === ticket && phase == DictationPhase.TRANSCRIBING)
                    cancel("Android’s on-device speech service couldn’t read the recording. Nothing was sent.", showToast = true)
            }
        } catch (_: Exception) {
            // Once a pipe exists it owns the PCM and wipes it on close.
            if (audioPipe == null) pcm.close()
            cancel("Couldn’t open Android’s recorded-audio speech service. Nothing was sent.", showToast = true)
        }
    }

    fun timedOut(ticket: TerminalDictationTicket, timedPhase: DictationPhase?) {
        if (take !== ticket || phase != timedPhase) return
        cancel(when (timedPhase) {
            DictationPhase.STARTING -> "The microphone didn’t start. Nothing was sent."
            DictationPhase.STOPPING -> "The microphone didn’t finish. Nothing was sent."
            DictationPhase.TRANSCRIBING -> "Android didn’t finish transcribing the recording. Nothing was sent."
            DictationPhase.SUBMITTING -> "The terminal didn’t accept the dictation."
            else -> "Dictation stopped. Nothing was sent."
        }, showToast = true)
    }

    fun pause() {
        // The microphone permission dialog may obscure the Activity; no capture starts until resumed.
        if (phase != DictationPhase.PERMISSION && phase != DictationPhase.WAITING_FOR_RESUME && take != null)
            cancel("Dictation stopped when the terminal left the foreground.")
    }

    fun cancelIfTargetChanged(terminal: Any?) {
        if (take?.terminal?.let { it !== terminal } == true) cancel("The active terminal changed. Nothing was sent.")
    }

    fun dispose() = cancel(null)

    private fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        if (resumed()) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun cancel(message: String?, showToast: Boolean = false) {
        take?.cancel()
        take = null
        phase = null
        capture?.cancel()
        capture = null
        captured?.close()
        captured = null
        closeRecognizer(cancel = true)
        notice = message
        if (showToast && message != null) toast(message)
    }

    private fun closeRecognizer(cancel: Boolean) {
        audioPipe?.close()
        audioPipe = null
        val client = recognizer
        recognizer = null
        if (client != null) {
            if (cancel) runCatching { client.cancel() }
            runCatching { client.destroy() }
        }
    }

    private fun submit(ticket: TerminalDictationTicket, results: List<String>?) {
        if (take !== ticket || phase != DictationPhase.TRANSCRIBING) return
        if (!stillHere(ticket)) {
            cancel("The active terminal changed. Nothing was sent.")
            return
        }
        if (audioPipe?.consumedAllAudio() != true) {
            cancel("Android’s on-device speech service didn’t read the full recording. Nothing was sent.", showToast = true)
            return
        }
        val text = ticket.finish(ticket.terminal, results)
        if (text == null) {
            cancel("No usable speech was recognized. Nothing was sent.", showToast = true)
            return
        }
        phase = DictationPhase.SUBMITTING
        closeRecognizer(cancel = false)
        fun stillAllowed() = take === ticket && phase == DictationPhase.SUBMITTING &&
            ticket.allowsDelivery(terminalIdentity(), resumed())
        fun complete(accepted: Boolean) {
            if (take !== ticket || phase != DictationPhase.SUBMITTING) return
            take = null
            phase = null
            notice = if (accepted) "Dictation submitted with Enter."
                else "Could not submit dictation to the active terminal."
            if (!accepted) toast(checkNotNull(notice))
        }
        val posted = runCatching { send(ticket.terminal, text, ::stillAllowed, ::complete) }.getOrDefault(false)
        if (!posted) complete(false)
    }

    private fun listener(ticket: TerminalDictationTicket) = object : RecognitionListener {
        private val transcript = RecordedDictationSegments()
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onSegmentResults(segmentResults: Bundle) {
            if (take === ticket && phase == DictationPhase.TRANSCRIBING)
                transcript.add(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }
        override fun onEndOfSegmentedSession() = submit(ticket, transcript.result()?.let(::listOf))

        override fun onResults(results: Bundle?) {
            // Some providers use a regular final callback even with audio-source mode.
            // If there were segmented results, do not append a duplicate full hypothesis.
            submit(ticket, if (transcript.hasSegments) transcript.result()?.let(::listOf)
                else results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        }

        override fun onError(error: Int) {
            if (take !== ticket || phase != DictationPhase.TRANSCRIBING) return
            val language = Locale.getDefault().displayName
            val message = when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "The offline speech model for $language is unavailable. Check Android’s speech settings."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "Android’s on-device speech service doesn’t support $language."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech recognized. Nothing was sent."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android’s speech service is busy. Try again."
                SpeechRecognizer.ERROR_AUDIO -> "Android’s speech service couldn’t read the recording. Nothing was sent."
                else -> "On-device transcription stopped (Android error $error). Nothing was sent."
            }
            cancel(message, showToast = true)
        }
    }
}
