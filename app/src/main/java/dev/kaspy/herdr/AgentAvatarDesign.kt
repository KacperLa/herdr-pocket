package dev.kaspy.herdr

import java.security.MessageDigest

/** A terminal's avatar is independent of its current name, task, row position or status. */
internal data class AgentAvatarScope(val backend:String,val host:String,val port:Int,val login:String,val session:String?)
internal data class RobotDesign(val head:Int,val face:Int,val palette:Int)
internal data class RobotPalette(val backdrop:Int,val shell:Int)
internal enum class RobotTone { SHELL,VISOR,EYES,BADGE_INK }
internal data class RobotPiece(val tone:RobotTone,val data:String,val stroke:Float=0f)

internal object AgentAvatarDesign {
    const val CANVAS_SIZE=54f
    const val CIRCLE_CENTER=24.5f
    const val CIRCLE_RADIUS=23.8f
    const val FRAME_RADIUS=23.2f
    const val FRAME_STROKE=1.05f
    const val ART_SCALE=.84f
    const val ART_OPTICAL_X=24.5f
    const val ART_OPTICAL_Y=23.5f
    const val BADGE_CENTER=43.5f
    const val BADGE_EDGE_RADIUS=10.5f
    const val BADGE_FACE_RADIUS=8.7f

    val palettes=listOf(
        RobotPalette(0x16303B,0x62D6F3), RobotPalette(0x3B2D20,0xFFC875),
        RobotPalette(0x18382F,0x8EECB4), RobotPalette(0x302743,0xC5A4FE),
        RobotPalette(0x3A2633,0xFDA9C5), RobotPalette(0x233047,0xA6BFFF),
    )
    const val VISOR=0x0D1823
    const val EYES=0xF2FDFF
    const val BADGE_EDGE=0x101219
    const val BADGE_INK=0x121B24
    val heads=listOf(
        listOf(
            RobotPiece(RobotTone.SHELL,"M24.5 15V7",1.8f),
            RobotPiece(RobotTone.SHELL,"M22 6a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
            RobotPiece(RobotTone.SHELL,"M8.5 19H40.5Q44 19 44 23V27Q44 31 40.5 31H8.5Q5 31 5 27V23Q5 19 8.5 19Z"),
            RobotPiece(RobotTone.SHELL,"M19 12H30Q41 12 41 22V28Q41 38 30 38H19Q8 38 8 28V22Q8 12 19 12Z"),
        ),
        listOf(
            RobotPiece(RobotTone.SHELL,"M15.5 15L12 7M33.5 15L37 7",1.8f),
            RobotPiece(RobotTone.SHELL,"M9.8 7a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0M34.8 7a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0"),
            RobotPiece(RobotTone.SHELL,"M8 17Q8 12 15 11H34Q41 12 41 17L38 34Q38 38 32.5 38H16.5Q11 38 11 34Z"),
        ),
        listOf(
            RobotPiece(RobotTone.SHELL,"M8 19H41Q43 19 43 21V28Q43 31 41 31H8Q6 31 6 28V21Q6 19 8 19Z"),
            RobotPiece(RobotTone.SHELL,"M9 19Q12 9 24.5 9Q37 9 40 19L39 32Q38 38 33 38H16Q11 38 10 32Z"),
        ),
        listOf(
            RobotPiece(RobotTone.SHELL,"M10 16Q7 6 16 11L21 14M39 16Q42 6 33 11L28 14",3f),
            RobotPiece(RobotTone.SHELL,"M20 13H29Q42 13 42 25Q42 38 29 38H20Q7 38 7 25Q7 13 20 13Z"),
        ),
    )
    val faces=listOf(
        listOf(
            RobotPiece(RobotTone.VISOR,"M18 18H31Q36 18 36 23.5Q36 29 31 29H18Q13 29 13 23.5Q13 18 18 18Z"),
            RobotPiece(RobotTone.EYES,"M17.4 23.5a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0M26.4 23.5a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0"),
            RobotPiece(RobotTone.VISOR,"M21 33.5H28",1.5f),
        ),
        listOf(
            RobotPiece(RobotTone.VISOR,"M16 18H33Q36 18 36 21V26Q36 29 33 29H16Q13 29 13 26V21Q13 18 16 18Z"),
            RobotPiece(RobotTone.EYES,"M17 23.5H22M27 23.5H32",2f),
            RobotPiece(RobotTone.VISOR,"M21 33.5H28",1.5f),
        ),
        listOf(
            RobotPiece(RobotTone.VISOR,"M19 17.5H30Q36 17.5 36 24.5Q36 31.5 30 31.5H19Q13 31.5 13 24.5Q13 17.5 19 17.5Z"),
            RobotPiece(RobotTone.EYES,"M16.5 24Q19.2 19.8 22 24M27 24Q29.8 19.8 32.5 24M22 27.5Q24.5 30 27 27.5",1.45f),
        ),
        listOf(
            RobotPiece(RobotTone.VISOR,"M12.8 23.5a5.7 5.7 0 1 0 11.4 0a5.7 5.7 0 1 0-11.4 0M24.8 23.5a5.7 5.7 0 1 0 11.4 0a5.7 5.7 0 1 0-11.4 0"),
            RobotPiece(RobotTone.EYES,"M16 23.4Q18.5 27 21 23.4M28 23.4Q30.5 27 33 23.4",1.5f),
            RobotPiece(RobotTone.VISOR,"M23 32L24.5 34L26 32",1.4f),
        ),
    )

    fun pick(scope:AgentAvatarScope?,address:String):RobotDesign {
        if(scope==null || address.isBlank()) return RobotDesign(0,0,0)
        val fields=listOf(scope.backend,scope.host,scope.port.toString(),scope.login,
            scope.session.orEmpty().ifBlank {"default"},address)
        val bytes=MessageDigest.getInstance("SHA-256").digest(
            fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        )
        fun pickAt(i:Int,size:Int) = (((bytes[i].toInt() and 255) shl 8) or
            (bytes[i+1].toInt() and 255)) % size
        return RobotDesign(pickAt(0,heads.size),pickAt(2,faces.size),pickAt(4,palettes.size))
    }
}

internal enum class RobotBadge(val color:Int,val glyph:RobotPiece) {
    WORKING(0x62D6F3,RobotPiece(RobotTone.BADGE_INK,"M-4.2 -3L-1.3 0L-4.2 3M.7 -3L3.6 0L.7 3",1.9f)),
    BLOCKED(0xFFC875,RobotPiece(RobotTone.BADGE_INK,"M0 -4V.8M0 4V4.2",2.25f)),
    DONE(0x8EECB4,RobotPiece(RobotTone.BADGE_INK,"M-4 .2L-1.2 2.8L4.1 -3.2",2.05f)),
    IDLE(0xC5A4FE,RobotPiece(RobotTone.BADGE_INK,"M-5 -3.4H-.5L-5 1.2H-.5M1.3 .5H4.9L1.3 4.2H4.9",1.55f)),
    UNKNOWN(0xBBC8D9,RobotPiece(RobotTone.BADGE_INK,"M-2.7 -2.8Q-2.5 -4.5 .1 -4.5Q2.8 -4.5 2.7 -2.4Q2.7 -1 .9 .3Q0 1 0 1.7M0 4.2V4.4",1.7f));
}
