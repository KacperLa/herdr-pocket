package dev.kaspy.herdr

import android.animation.ValueAnimator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal enum class LiveVoiceUiPhase { IDLE, CONNECTING, LISTENING, SPEAKING }

@Composable internal fun LiveVoiceToolbarButton(
    enabled:Boolean, phase:LiveVoiceUiPhase, audioLevel:Float, onToggle:()->Unit,
) {
    val active=phase==LiveVoiceUiPhase.LISTENING || phase==LiveVoiceUiPhase.SPEAKING
    val label=stringResource(when(phase) {
        LiveVoiceUiPhase.IDLE -> R.string.live_voice_start
        LiveVoiceUiPhase.CONNECTING -> R.string.live_voice_cancel
        else -> R.string.live_voice_stop
    })
    val status=stringResource(when(phase) {
        LiveVoiceUiPhase.IDLE -> R.string.live_voice_idle
        LiveVoiceUiPhase.CONNECTING -> R.string.live_voice_connecting
        LiveVoiceUiPhase.LISTENING -> R.string.live_voice_listening
        LiveVoiceUiPhase.SPEAKING -> R.string.live_voice_speaking
    })
    val animate=ValueAnimator.areAnimatorsEnabled()
    val pulse=if(phase==LiveVoiceUiPhase.CONNECTING && animate)
        rememberInfiniteTransition(label="Live voice connecting").animateFloat(
            initialValue=.62f,targetValue=1f,
            animationSpec=infiniteRepeatable(tween(900),RepeatMode.Reverse),
            label="Live voice pulse",
        ).value else 1f
    Surface(
        onClick=onToggle,enabled=enabled,
        shape=CircleShape,color=if(active) Color.White else Color.Transparent,
        modifier=Modifier.height(48.dp).width(if(active) 58.dp else 48.dp).semantics {
            contentDescription=label
            stateDescription=status
            role=Role.Button
        },
    ) {
        Box(contentAlignment=Alignment.Center) {
            if(active) {
                val target=audioLevel.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
                val voiceLevel=if(animate) animateFloatAsState(target,tween(110),label="Voice level").value else target
                Canvas(Modifier.size(24.dp)) {
                    val levels=floatArrayOf(.21f,.62f,.91f,.39f,.74f,.21f)
                    for(i in levels.indices) {
                        val x=size.width*(2+4*i)/24f
                        val half=size.height*levels[i]*(.30f + .67f*voiceLevel)/2f
                        drawLine(Color.Black,Offset(x,size.height/2-half),Offset(x,size.height/2+half),
                            strokeWidth=1.9.dp.toPx(),cap=StrokeCap.Round)
                    }
                }
            } else {
                Icon(painterResource(R.drawable.ic_terminal_live_voice),contentDescription=null,
                    tint=Color.White.copy(alpha=if(enabled) pulse else .38f),modifier=Modifier.size(24.dp))
            }
        }
    }
}

/** The button owns only the current visible route and never defers a request to a successor. */
@Composable internal fun LiveVoiceDockControl(voice:PocketLiveVoice, inputEnabled:Boolean) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val visible=if(inputEnabled) voice.routeIdentity() else null
    val current by androidx.compose.runtime.rememberUpdatedState(visible)
    val gate=androidx.compose.runtime.remember {LiveVoicePermissionGate()}
    fun resumed()=lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    fun startGranted() {
        gate.claim(current,resumed())?.let(voice::requestTicket)
    }
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        if(gate.result(allowed,current) && !allowed) voice.reportPermissionDenied()
        if(allowed) startGranted()
    }
    androidx.compose.runtime.DisposableEffect(lifecycle,voice) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _, event ->
            when(event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> startGranted()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> voice.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            gate.clear()
            voice.stop()
        }
    }
    androidx.compose.runtime.SideEffect {
        gate.removeIfChanged(visible)
        if(visible==null) voice.stop()
    }
    androidx.compose.runtime.LaunchedEffect(voice.phase,visible) {
        if(voice.phase!=LiveVoiceUiPhase.IDLE && visible!=null) {
            while(voice.routeIdentity()===visible) kotlinx.coroutines.delay(100)
            voice.stop()
        }
    }
    LiveVoiceToolbarButton(visible!=null,voice.phase,voice.level) {
        if(voice.phase!=LiveVoiceUiPhase.IDLE) {
            gate.clear()
            voice.stop(finish=true)
        } else if(visible!=null && resumed() && voice.routeIdentity()===visible) {
            if(voice.endpoint==null ||
                androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                voice.requestTicket(visible)
            } else if(gate.request(visible)) {
                runCatching {permission.launch(android.Manifest.permission.RECORD_AUDIO)}
                    .onFailure {gate.clear();voice.reportPermissionDenied()}
            }
        }
    }
}
