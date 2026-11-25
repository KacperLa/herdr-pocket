package dev.kaspy.herdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
internal fun DictationProviderSettings(selected: DictationProvider, onSelect: (DictationProvider) -> Unit) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Recheck the packages when this screen opens or the user returns from an install/update.
    val issues = androidx.compose.runtime.remember(context, lifecycleState) {
        DictationProvider.entries.associateWith { it.issue(context) }
    }
    Column(Modifier.selectableGroup()) {
        for (provider in DictationProvider.entries) {
            val issue = issues[provider]
            val enabled = provider == DictationProvider.ANDROID || issue == null
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = selected == provider, enabled = enabled, role = Role.RadioButton,
                        onClick = { onSelect(provider) }),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == provider, enabled = enabled, onClick = null)
                Column {
                    Text(stringResource(provider.label), style = MaterialTheme.typography.bodyLarge)
                    if (issue != null || provider == DictationProvider.PARAKEETYPE) {
                        Text(stringResource(issue?.message ?: R.string.dictation_provider_parakeetype_detail),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
