package dev.kaspy.herdr

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun AgentPickerRow(
    session: CoreSession,
    current: Boolean,
    tui: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?=null,
    onTogglePin: (() -> Unit)?=null,
    pinned: Boolean=false,
    pinnedTile: Boolean=false,
    spacesMode: Boolean=false,
    compactAgent: Boolean=false,
    avatarScope: AgentAvatarScope?=null,
) {
    val scheme=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(18.dp)
    val showCurrentHighlight=current && !compactAgent && !spacesMode
    val agentCard=session.isAgent && (compactAgent || spacesMode)
    val name=if(spacesMode && !agentCard) session.spacesName ?: session.name else session.name
    val status=TuiAgents.status(session.status)
    val reportedError=session.status.equals("error",ignoreCase=true)
    val statusLabel=stringResource(when {
        !session.isAgent -> R.string.roster_row_terminal
        reportedError -> R.string.roster_row_error
        else -> when(status) {
            TuiAgents.Status.WORKING -> R.string.agent_status_working
            TuiAgents.Status.BLOCKED -> R.string.agent_status_blocked
            TuiAgents.Status.IDLE -> R.string.agent_status_idle
            TuiAgents.Status.DONE -> R.string.agent_status_done
            TuiAgents.Status.UNKNOWN -> R.string.agent_status_unknown
        }
    })
    val location=session.rosterPlacement
    val workspace=location?.workspaceLabel.takeIf { !spacesMode }
    val hasGoToLabel=spacesMode && session.spacesName!=null
    val tab=location?.tabLabel?.takeUnless {hasGoToLabel || it.equals(name,ignoreCase=true)}
    val pane=location?.paneOrdinal?.takeUnless {hasGoToLabel}?.let {stringResource(R.string.roster_row_pane_number,it)}
    val breadcrumb=listOfNotNull(workspace,tab,pane)
        .filterNot {it.isBlank()}.distinct().joinToString(" · ")
    val runner=session.agent.takeIf { session.isAgent && it.isNotBlank() && !it.equals(name,ignoreCase=true) }
    val stateAndRunner=listOfNotNull(statusLabel,runner).joinToString(" · ")
    val description=session.description?.takeUnless {
        it.isBlank() || it.equals(name,ignoreCase=true) || it.equals(runner,ignoreCase=true)
    }

    var menuOpen by remember(session.address,enabled) { mutableStateOf(false) }
    val canOpenMenu=agentCard && (onRename!=null || onTogglePin!=null)
    val menuLabel=when {
        canOpenMenu -> stringResource(R.string.roster_agent_options)
        onRename!=null -> stringResource(R.string.roster_row_rename)
        else -> null
    }
    val pinnedLabel=stringResource(R.string.roster_agent_pinned)
    val avatarStatus=if(reportedError) TuiAgents.Status.BLOCKED else status
    val foreground=if(agentCard) Color.White else scheme.onSurface
    val secondary=if(agentCard) Color(0xFFDEDEDE) else scheme.onSurfaceVariant

    Box(Modifier.then(if(pinnedTile) Modifier.width(116.dp) else Modifier.fillMaxWidth())) {
      Surface(
        shape=shape,
        color=if(agentCard) Color.Transparent else if(showCurrentHighlight) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
        contentColor=foreground,
        border=if(showCurrentHighlight) BorderStroke(1.dp,scheme.primary) else null,
        modifier=Modifier.fillMaxWidth().heightIn(min=64.dp).clip(shape)
            .combinedClickable(enabled=enabled,role=Role.Button,onClick=onClick,
                onLongClickLabel=menuLabel,
                onLongClick=if(canOpenMenu) ({menuOpen=true}) else onRename)
            .then(if(compactAgent || spacesMode) Modifier else Modifier.semantics {selected=current})
            .then(if(pinned) Modifier.semantics {stateDescription=pinnedLabel} else Modifier),
      ) {
        if(pinnedTile) {
            Column(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=8.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                AgentFaceAvatar(avatarScope,session.address,avatarStatus,statusLabel,diameter=68.dp)
                Text(name,color=foreground,fontSize=17.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,
                    maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        } else Row(
            Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(14.dp),
        ) {
            if(session.isAgent && (compactAgent || spacesMode))
                AgentFaceAvatar(avatarScope,session.address,avatarStatus,statusLabel.takeIf {compactAgent})
            else if(session.isAgent) AgentStatusAvatar(avatarStatus)
            else TerminalAvatar()
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(agentCard) {
                    Text(name,color=foreground,fontSize=22.sp,fontWeight=FontWeight.SemiBold,maxLines=2,
                        overflow=TextOverflow.Ellipsis)
                    agentCardSummary(session).description?.let {
                        // Text semantics retain the entire line, even when visually ellipsized.
                        Text(it,fontSize=15.sp,color=secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    if(spacesMode) Text(statusLabel,fontSize=15.sp,fontWeight=FontWeight.Medium)
                } else {
                    Text(name,fontSize=22.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(stateAndRunner,fontSize=15.sp,fontWeight=FontWeight.Medium)
                    val metadata=if(tui) breadcrumb.ifBlank {null} else session.address
                    metadata?.let { Text(it,fontSize=15.sp,color=scheme.onSurfaceVariant,
                        maxLines=2,overflow=TextOverflow.Ellipsis) }
                    description?.let { Text(it,fontSize=16.sp,color=scheme.onSurfaceVariant,
                        maxLines=2,overflow=TextOverflow.Ellipsis) }
                    val path=if(spacesMode) session.spacesPath ?: session.workingDirectory else session.workingDirectory
                    path?.takeIf {it.isNotBlank()}?.let {
                        Text(it,fontSize=15.sp,color=scheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
      }
      PocketMenu(expanded=menuOpen && enabled && canOpenMenu,onDismissRequest={menuOpen=false}) {
          onTogglePin?.let { action ->
              DropdownMenuItem(
                  text={Text(stringResource(if(pinned) R.string.roster_agent_unpin else R.string.roster_agent_pin))},
                  onClick={menuOpen=false;action()},
              )
          }
          onRename?.let { action ->
              DropdownMenuItem(text={Text(stringResource(R.string.roster_row_rename))},
                  onClick={menuOpen=false;action()})
          }
      }
    }
}

@Composable internal fun TerminalAvatar(diameter:androidx.compose.ui.unit.Dp=54.dp) {
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(diameter).background(ink.copy(alpha=0.15f),CircleShape),contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(diameter*(29f/54f))) {
            val stroke=(diameter*(2f/54f)).toPx()
            fun p(x:Float,y:Float)=Offset(size.width*x,size.height*y)
            drawRoundRect(ink,topLeft=p(.10f,.18f),size=Size(size.width*.80f,size.height*.64f),
                cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(width=stroke))
            drawLine(ink,p(.29f,.39f),p(.42f,.50f),stroke,StrokeCap.Round)
            drawLine(ink,p(.42f,.50f),p(.29f,.61f),stroke,StrokeCap.Round)
            drawLine(ink,p(.53f,.61f),p(.72f,.61f),stroke,StrokeCap.Round)
        }
    }
}

@Composable private fun AgentStatusAvatar(status:TuiAgents.Status) {
    val scheme=MaterialTheme.colorScheme
    val tint=when(status) {
        TuiAgents.Status.WORKING -> scheme.primary
        TuiAgents.Status.BLOCKED -> scheme.error
        TuiAgents.Status.DONE -> scheme.tertiary
        else -> scheme.onSurfaceVariant
    }
    // Keep a readable static symbol when the system disables animations.
    val angle=if(status==TuiAgents.Status.WORKING && ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label="Agent working").animateFloat(
            initialValue=0f,
            targetValue=360f,
            animationSpec=infiniteRepeatable(animation=tween(1250,easing=LinearEasing)),
            label="Agent status rotation",
        )
    } else null
    Box(
        modifier=Modifier.size(54.dp).background(tint.copy(alpha=0.15f),CircleShape),
        contentAlignment=Alignment.Center,
    ) {
        Canvas(Modifier.size(32.dp).graphicsLayer {rotationZ=angle?.value ?: 0f}) {
            fun point(x:Float,y:Float)=Offset(size.width*x,size.height*y)
            val stroke=2.dp.toPx()
            fun line(x1:Float,y1:Float,x2:Float,y2:Float) =
                drawLine(tint,point(x1,y1),point(x2,y2),stroke,StrokeCap.Round)
            fun arc(start:Float,sweep:Float,inset:Float=0.19f) = drawArc(tint,start,sweep,false,
                topLeft=point(inset,inset), size=Size(size.width*(1f-2f*inset),size.height*(1f-2f*inset)),
                style=Stroke(stroke,cap=StrokeCap.Round))
            fun dot(x:Float,y:Float)=drawCircle(tint,stroke*0.64f,point(x,y))
            when(status) {
                TuiAgents.Status.WORKING -> {
                    arc(198f,142f); arc(18f,142f)
                    line(0.65f,0.32f,0.79f,0.39f); line(0.79f,0.39f,0.81f,0.23f)
                    line(0.35f,0.68f,0.21f,0.61f); line(0.21f,0.61f,0.19f,0.77f)
                }
                TuiAgents.Status.BLOCKED -> {
                    arc(0f,360f,0.12f)
                    line(0.5f,0.29f,0.5f,0.54f); dot(0.5f,0.7f)
                }
                TuiAgents.Status.IDLE -> {
                    line(0.18f,0.46f,0.48f,0.46f); line(0.48f,0.46f,0.18f,0.76f)
                    line(0.18f,0.76f,0.48f,0.76f)
                    line(0.58f,0.25f,0.82f,0.25f); line(0.82f,0.25f,0.58f,0.49f)
                    line(0.58f,0.49f,0.82f,0.49f)
                }
                TuiAgents.Status.DONE -> {
                    arc(0f,360f,0.12f)
                    line(0.30f,0.5f,0.45f,0.64f); line(0.45f,0.64f,0.70f,0.36f)
                }
                TuiAgents.Status.UNKNOWN -> {
                    arc(0f,360f,0.12f)
                    line(0.33f,0.5f,0.67f,0.5f)
                }
            }
        }
    }
}
