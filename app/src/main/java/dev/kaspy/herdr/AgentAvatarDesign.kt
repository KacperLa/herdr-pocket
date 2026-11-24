package dev.kaspy.herdr

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable scoped terminal identity, independent of name, task, order and status. */
internal data class AgentAvatarScope(val backend:String,val host:String,val port:Int,val login:String,val session:String?)
internal data class AgentFaceChoice(val face:Int,val color:Int)
internal data class AgentFaceColor(val background:Int,val tint:Int)
internal data class BadgeGlyph(val data:String,val stroke:Float)

internal object AgentAvatarDesign {
    const val CANVAS_SIZE=54f
    const val CIRCLE_CENTER=24.5f
    const val CIRCLE_RADIUS=23.8f
    const val FACE_SIZE=32f
    const val FACE_COUNT=8
    const val BADGE_CENTER=43.5f
    const val BADGE_EDGE_RADIUS=10.5f
    const val BADGE_FACE_RADIUS=8.7f
    const val BADGE_EDGE=0x101219
    const val BADGE_INK=0x121B24

    const val COLOR_COUNT=72
    // Independent opaque-ID hue bucket. Keep saturation and luminance controlled across the whole wheel.
    val colors=List(COLOR_COUNT) { slot ->
        val hue=slot*360.0/COLOR_COUNT
        AgentFaceColor(hsl(hue,.27,.16),hsl(hue,.74,.80))
    }

    private fun hsl(hue:Double,saturation:Double,lightness:Double):Int {
        val chroma=(1.0-abs(2.0*lightness-1.0))*saturation
        val sector=hue/60.0
        val x=chroma*(1.0-abs(sector%2.0-1.0))
        val (red,green,blue)=when(sector.toInt()) {
            0 -> Triple(chroma,x,0.0)
            1 -> Triple(x,chroma,0.0)
            2 -> Triple(0.0,chroma,x)
            3 -> Triple(0.0,x,chroma)
            4 -> Triple(x,0.0,chroma)
            else -> Triple(chroma,0.0,x)
        }
        val offset=lightness-chroma/2.0
        fun channel(value:Double)=((value+offset)*255.0).roundToInt().coerceIn(0,255)
        return (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    fun pick(scope:AgentAvatarScope?,address:String):AgentFaceChoice {
        if(address.isBlank()) return AgentFaceChoice(0,0)
        val fields=listOf(scope?.backend.orEmpty(),scope?.host.orEmpty(),scope?.port?.toString().orEmpty(),
            scope?.login.orEmpty(),scope?.session.orEmpty().ifBlank {"default"},address)
        val bytes=MessageDigest.getInstance("SHA-256").digest(
            fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        )
        fun pickAt(i:Int,size:Int) = (((bytes[i].toInt() and 255) shl 8) or
            (bytes[i+1].toInt() and 255)) % size
        return AgentFaceChoice(pickAt(0,FACE_COUNT),pickAt(2,colors.size))
    }
}

internal enum class AgentStatusBadge(val color:Int,val glyph:BadgeGlyph) {
    WORKING(0x62D6F3,BadgeGlyph("M-4.2 -3L-1.3 0L-4.2 3M.7 -3L3.6 0L.7 3",1.9f)),
    BLOCKED(0xFFC875,BadgeGlyph("M0 -4V.8M0 4V4.2",2.25f)),
    DONE(0x8EECB4,BadgeGlyph("M-4 .2L-1.2 2.8L4.1 -3.2",2.05f)),
    IDLE(0xC5A4FE,BadgeGlyph("M-5 -3.4H-.5L-5 1.2H-.5M1.3 .5H4.9L1.3 4.2H4.9",1.55f)),
    UNKNOWN(0xBBC8D9,BadgeGlyph("M-2.7 -2.8Q-2.5 -4.5 .1 -4.5Q2.8 -4.5 2.7 -2.4Q2.7 -1 .9 .3Q0 1 0 1.7M0 4.2V4.4",1.7f));
}
