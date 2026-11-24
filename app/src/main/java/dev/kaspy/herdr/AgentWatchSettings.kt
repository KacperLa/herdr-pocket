package dev.kaspy.herdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Render the service's state separately from the terminal connection state. */
@Composable
internal fun AgentWatchSettings(
    enabled: Boolean, canNotify: Boolean, canStart: Boolean,
    onChange: (Boolean) -> Unit, onSystemSettings: () -> Unit,
) {
    val state by AgentWatchService.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = PocketControlSize)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.agent_watch_enable), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = null, colors = pocketSwitchColors())
        }
        val message = when {
            !enabled -> stringResource(R.string.agent_watch_opt_in)
            !canNotify -> stringResource(R.string.agent_watch_os_blocked)
            state is AgentWatchServiceState.Active -> watchStateLabel((state as AgentWatchServiceState.Active).state)
            !canStart -> stringResource(R.string.agent_watch_waiting)
            state is AgentWatchServiceState.Ended -> watchStateLabel((state as AgentWatchServiceState.Ended).state)
            state is AgentWatchServiceState.Failed -> stringResource(R.string.agent_watch_start_failed)
            else -> stringResource(R.string.agent_watch_waiting)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (enabled && !canNotify) {
            TextButton(onClick = onSystemSettings) { Text(stringResource(R.string.agent_watch_android_settings)) }
        }
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
