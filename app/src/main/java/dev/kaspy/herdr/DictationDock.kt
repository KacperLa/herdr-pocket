package dev.kaspy.herdr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.floor

@Composable
internal fun DictationDock(
    phase: DictationPhase,
    held: Boolean,
    pressEnter: Boolean,
    levels: () -> List<Float>,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    val processing = phase.showsTranscribing
    val releaseLabel = if (pressEnter) R.string.dictation_hold_release else R.string.dictation_hold_insert
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        color = PocketSelected,
        contentColor = PocketInk,
        shape = CircleShape,
        border = BorderStroke(1.dp, PocketOutline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketIconControl(stringResource(R.string.dictation_cancel), onCancel) { ink ->
                Canvas(Modifier.size(DockGlyphSize)) {
                    val inset = size.width * 0.25f
                    val far = size.width - inset
                    drawLine(ink, Offset(inset, inset), Offset(far, far), DockGlyphStroke.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(far, inset), Offset(inset, far), DockGlyphStroke.toPx(), StrokeCap.Round)
                }
            }
            if (processing || phase == DictationPhase.CAPTURED || phase == DictationPhase.STARTING) {
                val label = stringResource(when {
                    processing -> R.string.dictation_transcribing
                    phase == DictationPhase.CAPTURED -> if (held) releaseLabel else if (pressEnter) R.string.dictation_recording_ready else R.string.dictation_ready_to_insert
                    else -> R.string.dictation_starting
                })
                Text(
                    label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                // Tap mode gets a stable recording message from the stop button.
                val heldDescription = if (held) stringResource(releaseLabel) else null
                Canvas(Modifier.weight(1f).height(34.dp).then(
                    if (heldDescription == null) Modifier else Modifier.semantics {
                        contentDescription = heldDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                )) {
                    val history = levels()
                    val step = 5.dp.toPx()
                    val slots = floor(size.width / step).toInt()
                    val center = size.height / 2f
                    val stroke = 2.dp.toPx()
                    for (i in 0 until slots) {
                        val x = size.width - (i + 0.5f) * step
                        val level = history.getOrNull(history.lastIndex - i) ?: 0f
                        val half = 0.65.dp.toPx() + level * (center - 1.8.dp.toPx())
                        val ink = PocketInk.copy(alpha = if (i < history.size) 0.80f else 0.43f)
                        drawLine(ink, Offset(x, center - half), Offset(x, center + half), stroke, StrokeCap.Round)
                    }
                }
            }
            if (phase.acceptsStopAndSend && !held) {
                val action = stringResource(when {
                    phase.isRecording && pressEnter -> R.string.dictation_stop_and_send
                    phase.isRecording -> R.string.dictation_stop_and_insert
                    pressEnter -> R.string.dictation_send_recording
                    else -> R.string.dictation_insert_recording
                })
                PocketIconControl(action, onStop, emphasized = phase.isRecording) { ink ->
                    Icon(painterResource(R.drawable.ic_terminal_record_stop), null, tint = ink,
                        modifier = Modifier.size(DockGlyphSize))
                }
            } else {
                // Center the status within the pill even without a stop control.
                Spacer(Modifier.size(DockTargetSize))
            }
        }
    }
}
