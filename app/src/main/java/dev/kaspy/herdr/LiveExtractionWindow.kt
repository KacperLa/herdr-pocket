package dev.kaspy.herdr

import java.security.MessageDigest

/** This crop is for the external extractor only, never for readiness or stale-screen guards. */
internal object LiveExtractionWindow {
    const val MAX_IGNORED_LINES=20

    fun normalize(value:Int)=value.coerceIn(0,MAX_IGNORED_LINES)

    fun crop(screen:String,ignoredBottomLines:Int):String {
        val count=normalize(ignoredBottomLines)
        return if(count==0) screen else screen.lines().dropLast(count).joinToString("\n").trimEnd()
    }

    fun preferenceKey(scope:AgentPinScope,address:String):String {
        val fields=listOf(scope.preferenceKey,address)
        val encoded=fields.joinToString("") {"${it.length}:$it"}
        val hash=MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        return "live_extract_bottom_v1_"+hash.joinToString("") {"%02x".format(it.toInt() and 255)}
    }
}
