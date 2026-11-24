package dev.kaspy.herdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun AgentRobotAvatar(
    scope:AgentAvatarScope?,address:String,status:TuiAgents.Status,statusDescription:String?,
) {
    val design=remember(scope,address) {AgentAvatarDesign.pick(scope,address)}
    val palette=AgentAvatarDesign.palettes[design.palette]
    val pieces=remember(design) {
        (AgentAvatarDesign.heads[design.head]+AgentAvatarDesign.faces[design.face])
            .map {it to PathParser().parsePathString(it.data).toPath()}
    }
    val badge=when(status) {
        TuiAgents.Status.WORKING -> RobotBadge.WORKING
        TuiAgents.Status.BLOCKED -> RobotBadge.BLOCKED
        TuiAgents.Status.DONE -> RobotBadge.DONE
        TuiAgents.Status.IDLE -> RobotBadge.IDLE
        TuiAgents.Status.UNKNOWN -> RobotBadge.UNKNOWN
    }
    val badgePath=remember(badge) {PathParser().parsePathString(badge.glyph.data).toPath()}
    fun color(rgb:Int) = Color((0xFF000000L or rgb.toLong()))
    fun stroke(width:Float)=Stroke(width=width,cap=StrokeCap.Round,join=StrokeJoin.Round)

    val geometry=AgentAvatarDesign
    Canvas(Modifier.size(geometry.CANVAS_SIZE.dp)
        .then(if(statusDescription==null) Modifier else Modifier.semantics {contentDescription=statusDescription})) {
        scale(size.width/geometry.CANVAS_SIZE,size.height/geometry.CANVAS_SIZE,pivot=Offset.Zero) {
            val circle=Offset(geometry.CIRCLE_CENTER,geometry.CIRCLE_CENTER)
            drawCircle(color(palette.backdrop),geometry.CIRCLE_RADIUS,circle)
            drawCircle(color(palette.shell),geometry.FRAME_RADIUS,circle,style=stroke(geometry.FRAME_STROKE))
            // Align the eye/visor center with the optical center of the circle. Reserve
            // clear space around the entire head (including antennae) and the badge.
            translate(circle.x-geometry.ART_OPTICAL_X,circle.y-geometry.ART_OPTICAL_Y) {
                scale(geometry.ART_SCALE,geometry.ART_SCALE,pivot=Offset(geometry.ART_OPTICAL_X,geometry.ART_OPTICAL_Y)) {
                    for((piece,path) in pieces) {
                        val ink=color(when(piece.tone) {
                            RobotTone.SHELL -> palette.shell
                            RobotTone.VISOR -> geometry.VISOR
                            RobotTone.EYES -> geometry.EYES
                            RobotTone.BADGE_INK -> geometry.BADGE_INK
                        })
                        drawPath(path,ink,style=if(piece.stroke>0f) stroke(piece.stroke) else Fill)
                    }
                }
            }
            val corner=Offset(geometry.BADGE_CENTER,geometry.BADGE_CENTER)
            drawCircle(color(geometry.BADGE_EDGE),geometry.BADGE_EDGE_RADIUS,corner)
            drawCircle(color(badge.color),geometry.BADGE_FACE_RADIUS,corner)
            translate(corner.x,corner.y) {
                drawPath(badgePath,color(AgentAvatarDesign.BADGE_INK),style=stroke(badge.glyph.stroke))
            }
        }
    }
}
