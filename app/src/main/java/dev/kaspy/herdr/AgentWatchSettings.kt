package dev.kaspy.herdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp

/** Render the service's state separately from the terminal connection state. */
@Composable
internal fun AgentWatchSettings(canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val state by AgentWatchService.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state is AgentWatchServiceState.Active) {
            OutlinedButton(onClick = onStop) { Text("Stop agent alerts") }
        } else {
            OutlinedButton(onClick = onStart, enabled = canStart) { Text("Enable agent alerts") }
        }
        val message = when (val current = state) {
            AgentWatchServiceState.Stopped -> if (canStart)
                "Get Done and Blocked alerts while Pocket is in the background."
                else "Connect to a Herdr TUI terminal to enable alerts."
            is AgentWatchServiceState.Active -> watchStateLabel(current.state)
            is AgentWatchServiceState.Ended -> watchStateLabel(current.state)
            is AgentWatchServiceState.Failed -> "Could not start agent alerts. Check Pocket’s notification settings."
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun watchStateLabel(state: AgentWatchState): String = when (state) {
    AgentWatchState.CONNECTING -> "Connecting to Herdr for agent alerts…"
    AgentWatchState.WATCHING -> "Watching for Done and Blocked."
    AgentWatchState.RETRYING -> "Agent alerts paused. Reconnecting…"
    AgentWatchState.UNSUPPORTED -> "Update Herdr on this host to enable agent alerts."
    AgentWatchState.FORWARDING_UNAVAILABLE -> "Could not forward the Herdr socket over SSH. Check the host’s SSH forwarding settings."
    AgentWatchState.AUTH_FAILED -> "SSH authentication failed. Reconnect to Herdr and try again."
    AgentWatchState.PIN_FAILED -> "Herdr’s SSH host key could not be verified. Agent alerts stopped."
}

/** Untyped and explicitly named default refer to the same Herdr TUI session. */
internal fun normalizedWatchSession(name: String?): String? =
    name?.trim()?.takeUnless { it.isEmpty() || it == "default" }
