package dev.kaspy.herdr

import kotlin.math.abs

internal object TerminalFont {
    private const val DEFAULT_SP=18f
    private const val MIN_SP=6f
    private const val MAX_SP=40f

    fun resize(current:Float,factor:Float):Float {
        val base=(if(current.isFinite()) current else DEFAULT_SP).coerceIn(MIN_SP,MAX_SP)
        if(!factor.isFinite() || factor<=0f || abs(factor-1f)<0.035f) return base
        return (base*factor).coerceIn(MIN_SP,MAX_SP)
    }
}
