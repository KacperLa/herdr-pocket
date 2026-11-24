package dev.kaspy.herdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val agentFaces=intArrayOf(
    R.drawable.ic_agent_face_smile,R.drawable.ic_agent_face_grin,R.drawable.ic_agent_face_cool,
    R.drawable.ic_agent_face_neutral,R.drawable.ic_agent_face_excited,R.drawable.ic_agent_face_wink,
    R.drawable.ic_agent_face_tongue,R.drawable.ic_agent_face_confused,
)

@Composable internal fun AgentFaceAvatar(
    scope:AgentAvatarScope?,address:String,status:TuiAgents.Status,statusDescription:String?,
    diameter:Dp=AgentAvatarDesign.CANVAS_SIZE.dp,
) {
    val choice=remember(scope,address) {AgentAvatarDesign.pick(scope,address)}
    val palette=AgentAvatarDesign.colors[choice.color]
    val face=painterResource(agentFaces[choice.face])
    val badge=when(status) {
        TuiAgents.Status.WORKING -> AgentStatusBadge.WORKING
        TuiAgents.Status.BLOCKED -> AgentStatusBadge.BLOCKED
        TuiAgents.Status.DONE -> AgentStatusBadge.DONE
        TuiAgents.Status.IDLE -> AgentStatusBadge.IDLE
        TuiAgents.Status.UNKNOWN -> AgentStatusBadge.UNKNOWN
    }
    val badgePath=remember(badge) {PathParser().parsePathString(badge.glyph.data).toPath()}
    fun color(rgb:Int) = Color(0xFF000000L or rgb.toLong())
    val geometry=AgentAvatarDesign
    Canvas(Modifier.size(diameter)
        .then(if(statusDescription==null) Modifier else Modifier.semantics {contentDescription=statusDescription})) {
        scale(size.width/geometry.CANVAS_SIZE,size.height/geometry.CANVAS_SIZE,pivot=Offset.Zero) {
            val circle=Offset(geometry.CIRCLE_CENTER,geometry.CIRCLE_CENTER)
            val faceOrigin=geometry.CIRCLE_CENTER-geometry.FACE_SIZE/2
            drawCircle(color(palette.background),geometry.CIRCLE_RADIUS,circle)
            translate(faceOrigin,faceOrigin) {
                with(face) { draw(Size(geometry.FACE_SIZE,geometry.FACE_SIZE),colorFilter=ColorFilter.tint(color(palette.tint))) }
            }
            val corner=Offset(geometry.BADGE_CENTER,geometry.BADGE_CENTER)
            drawCircle(color(geometry.BADGE_EDGE),geometry.BADGE_EDGE_RADIUS,corner)
            drawCircle(color(badge.color),geometry.BADGE_FACE_RADIUS,corner)
            translate(corner.x,corner.y) {
                drawPath(badgePath,color(geometry.BADGE_INK),
                    style=Stroke(width=badge.glyph.stroke,cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
        }
    }
}
