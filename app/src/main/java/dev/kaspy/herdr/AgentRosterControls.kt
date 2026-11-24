package dev.kaspy.herdr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class AgentRosterView(val label:Int) {
    AGENTS(R.string.agent_roster_agents),
    SPACES(R.string.agent_roster_spaces),
}

/** Center segment of the roster toolbar. Text wraps rather than clipping on narrow screens. */
@Composable internal fun AgentRosterViewSwitch(
    view:AgentRosterView,
    onViewChange:(AgentRosterView)->Unit,
    modifier:Modifier=Modifier,
) {
    Surface(
        modifier=modifier.widthIn(max=280.dp).fillMaxWidth(),
        shape=CircleShape,
        border=BorderStroke(1.dp,PocketOutline),
        color=PocketBlack,
        contentColor=PocketInk,
        shadowElevation=0.dp,
    ) {
        Row(
            Modifier.selectableGroup().heightIn(min=48.dp),
            horizontalArrangement=Arrangement.spacedBy(2.dp),
            verticalAlignment=Alignment.CenterVertically,
        ) {
            for(option in AgentRosterView.entries) {
                val selected=option==view
                Box(
                    // Inset only the highlight, not the 48dp tab hit area.
                    modifier=Modifier.weight(1f).heightIn(min=48.dp)
                        .clip(CircleShape)
                        .selectable(selected=selected,role=Role.Tab,onClick={onViewChange(option)})
                        .padding(3.dp),
                    contentAlignment=Alignment.Center,
                ) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min=42.dp)
                            .background(if(selected) PocketSelected else PocketBlack,CircleShape)
                            .then(if(selected) Modifier.border(2.dp,PocketInk,CircleShape) else Modifier)
                            .padding(horizontal=4.dp,vertical=4.dp),
                        contentAlignment=Alignment.Center,
                    ) {
                        Text(
                            text=stringResource(option.label),
                            style=MaterialTheme.typography.titleMedium,
                            color=PocketInk,
                            fontWeight=FontWeight.SemiBold,
                            textAlign=TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
