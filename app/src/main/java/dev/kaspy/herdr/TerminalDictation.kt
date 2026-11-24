package dev.kaspy.herdr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
        when (phase) {
            DictationPhase.CHECKING -> {
                delay(1_500)
                // A support query is optional. A slow or older provider can still record locally.
                dictation.supportTimedOut(take)
            }
            DictationPhase.STARTING, DictationPhase.FINISHING, DictationPhase.SUBMITTING -> {
                delay(8_000)
                dictation.recognitionTimedOut(take, phase)
            }
            DictationPhase.LISTENING -> {
                delay(45_000)
                dictation.recognitionTimedOut(take, phase)
            }
            else -> Unit
        }
    }

    val busy = phase != null && phase != DictationPhase.PERMISSION
    val description = when {
        busy -> "Cancel terminal dictation"
        phase == DictationPhase.PERMISSION -> "Waiting for microphone permission"
        else -> "Dictate to the terminal. Sends the final result with Enter."
    }
    val message = when (phase) {
        DictationPhase.PERMISSION -> "Waiting for microphone permission."
        DictationPhase.WAITING_FOR_RESUME -> "Waiting for the terminal to return to the foreground."
        DictationPhase.CHECKING -> "Checking on-device speech support. Tap mic to cancel."
        DictationPhase.STARTING -> "Starting the microphone. Wait for Listening before speaking."
        DictationPhase.LISTENING -> "Listening on this device. Sends with Enter when you finish. Tap mic to cancel."
        DictationPhase.FINISHING -> "Finishing on-device dictation. Sends with Enter. Tap mic to cancel."
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
            Icon(painterResource(R.drawable.ic_terminal_mic),contentDescription=null,
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

    fun tap(requestPermission: () -> Unit) {
        if (permissionPending) return
        if (take != null) {
            cancel("Dictation cancelled. Nothing was sent.")
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

        try {
            val client = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = client
            val locale = Locale.getDefault()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            recognitionIntent = intent
            recognitionLocale = locale
            client.setRecognitionListener(listener(ticket))
            phase = DictationPhase.CHECKING
            try {
                client.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (take !== ticket || phase != DictationPhase.CHECKING) return
                        when (onDeviceSpeechModel(
                            locale.toLanguageTag(),
                            support.installedOnDeviceLanguages,
                            support.pendingOnDeviceLanguages,
                            support.supportedOnDeviceLanguages,
                        )) {
                            DictationModel.PENDING ->
                                cancel("The offline speech model for ${locale.displayName} is downloading. Try again when it finishes.")
                            DictationModel.NEEDS_DOWNLOAD ->
                                cancel("The offline speech model for ${locale.displayName} isn’t installed. Download it in Android’s speech settings.")
                            DictationModel.INSTALLED, DictationModel.UNKNOWN -> beginListening(ticket)
                        }
                    }
                    override fun onError(error: Int) {
                        // Not all offline providers implement this optional query.
                        beginListening(ticket)
                    }
                })
            } catch (_: RuntimeException) {
                beginListening(ticket)
            }
        } catch (_: RuntimeException) {
            cancel("Couldn’t open Android’s on-device speech service. No audio was sent to a cloud recognizer.")
        }
    }

    private var recognitionIntent: Intent? = null
    private var recognitionLocale: Locale? = null

    fun supportTimedOut(ticket: TerminalDictationTicket) = beginListening(ticket)

    private fun beginListening(ticket: TerminalDictationTicket) {
        if (take !== ticket || phase != DictationPhase.CHECKING) return
        if (!resumed() || terminalIdentity() !== ticket.terminal) {
            cancel("Dictation stopped because the active terminal changed or left the foreground. Nothing was sent.")
            return
        }
        val client = recognizer ?: return
        val intent = recognitionIntent ?: return
        phase = DictationPhase.STARTING
        try {
            client.startListening(intent)
        } catch (_: RuntimeException) {
            cancel("Could not start the microphone. Check microphone access and try again.")
        }
    }

    fun recognitionTimedOut(ticket: TerminalDictationTicket, timedPhase: DictationPhase) {
        if (take !== ticket || phase != timedPhase) return
        cancel(when (timedPhase) {
            DictationPhase.STARTING -> "The speech service didn’t start the microphone. Check microphone access and retry."
            DictationPhase.FINISHING -> "The speech service didn’t return text. Nothing was sent."
            DictationPhase.SUBMITTING -> "The terminal didn’t accept the dictation. Nothing was sent."
            else -> "Dictation reached its time limit. Nothing was sent."
        })
    }

    fun pause() {
        // A permission activity is allowed to pause or stop us. Never open the mic until we resume.
        when (phase) {
            DictationPhase.CHECKING, DictationPhase.STARTING, DictationPhase.LISTENING, DictationPhase.FINISHING, DictationPhase.SUBMITTING ->
                cancel("Dictation stopped when the terminal left the foreground. Nothing was sent.")
            else -> Unit
        }
    }

    fun cancelIfTargetChanged(terminal: Any?) {
        if (take?.terminal?.let { it !== terminal } == true) {
            cancel("The active terminal changed. Nothing was sent.")
        }
    }

    fun dispose() = cancel(null)

    private fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun cancel(message: String?) {
        take?.cancel()
        release(cancel = true)
        notice = message
    }

    private fun release(cancel: Boolean) {
        take = null
        phase = null
        closeRecognizer(cancel)
    }

    private fun closeRecognizer(cancel: Boolean) {
        recognitionIntent = null
        recognitionLocale = null
        val client = recognizer
        recognizer = null
        if (client != null) {
            if (cancel) runCatching { client.cancel() }
            runCatching { client.destroy() }
        }
    }

    private fun listener(ticket: TerminalDictationTicket) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (take === ticket && phase == DictationPhase.STARTING) {
                if (!resumed() || terminalIdentity() !== ticket.terminal) {
                    cancel("Dictation stopped. Nothing was sent.")
                    return
                }
                phase = phase?.speechReady()
            }
        }
        override fun onBeginningOfSpeech() = onReadyForSpeech(null)
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            if (take === ticket) phase = phase?.speechEnded()
        }

        override fun onError(error: Int) {
            if (take !== ticket || phase == DictationPhase.SUBMITTING) return
            val language = recognitionLocale?.displayName ?: "your language"
            val stillCurrent = terminalIdentity() === ticket.terminal && resumed()
            ticket.cancel()
            release(cancel = false)
            if (!stillCurrent) {
                notice = "Dictation stopped. Nothing was sent."
                return
            }
            notice = when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "The offline speech model for $language is unavailable. Check Android’s speech settings for a download."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "Android’s on-device speech service doesn’t support $language."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "No speech recognized. Nothing was sent."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone access is needed. Check Herdr’s microphone permission in Android Settings."
                SpeechRecognizer.ERROR_AUDIO ->
                    "Couldn’t capture audio. Turn on Android’s Microphone access and close other apps using the mic."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Android’s speech service is busy. Close other dictation and try again."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "The on-device speech service returned a network error. Herdr did not fall back to cloud dictation."
                else -> "On-device speech service stopped (Android error $error). Nothing was sent."
            }
        }

        override fun onResults(results: Bundle?) {
            if (take !== ticket ||
                (phase != DictationPhase.STARTING && phase != DictationPhase.LISTENING && phase != DictationPhase.FINISHING)
            ) return
            val terminal = if (resumed()) terminalIdentity() else null
            val text = ticket.finish(
                terminal, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
            )
            if (text == null) {
                val message = if (terminal !== ticket.terminal)
                    "The active terminal changed or left the foreground. Nothing was sent."
                else "No speech recognized. Nothing was sent."
                release(cancel = false)
                notice = message
                return
            }
            phase = DictationPhase.SUBMITTING
            closeRecognizer(cancel = false)
            fun stillAllowed() = take === ticket && phase == DictationPhase.SUBMITTING &&
                ticket.allowsDelivery(terminalIdentity(), resumed())
            fun complete(accepted: Boolean) {
                if (take !== ticket || phase != DictationPhase.SUBMITTING) return
                release(cancel = false)
                notice = if (accepted) "Dictation submitted with Enter."
                    else "Could not submit dictation to the active terminal."
            }
            val posted = runCatching { send(ticket.terminal, text, ::stillAllowed, ::complete) }.getOrDefault(false)
            if (!posted) complete(false)
        }
    }
}
