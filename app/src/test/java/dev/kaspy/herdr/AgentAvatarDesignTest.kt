package dev.kaspy.herdr

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class AgentAvatarDesignTest {
    private val scope=AgentAvatarScope("TUI","example.org",22,"tester","work")

    @Test fun shapesStayStableAndUseOnlyValidIndices() {
        val ids=(0..255).map {"term_${it.toString(16)}"}
        val choices=ids.associateWith {AgentAvatarDesign.pick(scope,it)}
        for(id in ids.reversed()) {
            val selected=choices.getValue(id)
            assertEquals(selected,AgentAvatarDesign.pick(scope.copy(),id))
            assertTrue(selected.head in AgentAvatarDesign.heads.indices)
            assertTrue(selected.face in AgentAvatarDesign.faces.indices)
            assertTrue(selected.palette in AgentAvatarDesign.palettes.indices)
        }
        assertEquals(AgentAvatarDesign.heads.size,choices.values.map {it.head}.toSet().size)
        assertEquals(AgentAvatarDesign.faces.size,choices.values.map {it.face}.toSet().size)
        assertEquals(AgentAvatarDesign.palettes.size,choices.values.map {it.palette}.toSet().size)
        assertTrue(choices.values.groupBy {it.head}.values.all {rows -> rows.map {it.face}.toSet().size>1 })
    }

    @Test fun routingScopeDistinguishesOriginsAndCanonicalizesDefaultSession() {
        val ids=(0..15).map {"term_${it.toString(16)}"}
        val baseline=ids.map {AgentAvatarDesign.pick(scope,it)}
        for(other in listOf(
            scope.copy(backend="CORE"),scope.copy(host="other.example.org"),
            scope.copy(port=2222),scope.copy(login="other"),scope.copy(session="play"),
        )) assertNotEquals(baseline,ids.map {AgentAvatarDesign.pick(other,it)})

        assertEquals(AgentAvatarDesign.pick(scope.copy(session=null),"term_1"),
            AgentAvatarDesign.pick(scope.copy(session="default"),"term_1"))
        assertEquals(AgentAvatarDesign.pick(null,"term_1"),AgentAvatarDesign.pick(null,"term_2"))
    }

    @Test fun circleAndBadgeFitTheSame54DpSlot() {
        val art=AgentAvatarDesign
        assertEquals(54f,art.CANVAS_SIZE,0f)
        assertTrue(art.CIRCLE_CENTER-art.CIRCLE_RADIUS>=0f)
        assertTrue(art.CIRCLE_CENTER+art.CIRCLE_RADIUS<=art.CANVAS_SIZE)
        assertTrue(art.BADGE_CENTER+art.BADGE_EDGE_RADIUS<=art.CANVAS_SIZE)
        assertTrue(art.BADGE_EDGE_RADIUS-art.BADGE_FACE_RADIUS>=1.5f)
        assertTrue(art.ART_SCALE in .80f.. .87f)
    }

    @Test fun badgeGlyphsAreDistinctAndHaveStrongContrast() {
        val badges=RobotBadge.entries
        assertEquals(5,badges.map {it.glyph.data}.toSet().size)
        assertEquals(5,badges.map {it.color}.toSet().size)
        for(badge in badges) {
            assertTrue(badge.name,contrast(badge.color,AgentAvatarDesign.BADGE_INK)>=7.0)
            assertTrue(badge.name,contrast(badge.color,AgentAvatarDesign.BADGE_EDGE)>=7.0)
            assertTrue(badge.name,badge.glyph.stroke>=1.5f)
        }
        for(palette in AgentAvatarDesign.palettes) {
            assertTrue(contrast(palette.shell,palette.backdrop)>=5.0)
            assertTrue(contrast(palette.shell,AgentAvatarDesign.VISOR)>=7.0)
        }
        assertTrue(contrast(AgentAvatarDesign.EYES,AgentAvatarDesign.VISOR)>=7.0)
    }

    private fun contrast(a:Int,b:Int):Double {
        fun luminance(rgb:Int):Double {
            fun channel(shift:Int):Double {
                val value=((rgb shr shift) and 255)/255.0
                return if(value<=.04045) value/12.92 else ((value+.055)/1.055).pow(2.4)
            }
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        val (dark,light)=listOf(luminance(a),luminance(b)).sorted()
        return (light+.05)/(dark+.05)
    }
}
