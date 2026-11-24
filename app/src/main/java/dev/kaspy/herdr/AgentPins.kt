package dev.kaspy.herdr

import java.security.MessageDigest

/** Device-only pins; a changed server identity never inherits the old server's pins. */
internal data class AgentPinScope(
    val host:String,val port:Int,val login:String,val fingerprint:String,
    val backend:AgentBackend,val namedSession:String?,
) {
    companion object {
        fun from(config:SshConfig,backend:AgentBackend,namedSession:String?):AgentPinScope? {
            val endpoint=HostEndpoint.parse(config.host,config.port.toString()) ?: return null
            val pin=config.expectedPin?.takeIf(::isSshSha256Pin) ?: return null
            val login=config.user.trim().takeIf {it.isNotEmpty() && it.none(Char::isISOControl)} ?: return null
            val session=namedSession.takeIf {backend==AgentBackend.TUI && it!="default"}
            return AgentPinScope(endpoint.host,endpoint.port,login,pin,backend,session)
        }
    }

    val preferenceKey:String get() {
        val fields=listOf(host,port.toString(),login,fingerprint,backend.name,namedSession.orEmpty())
        val encoded=fields.joinToString("") {"${it.length}:$it"}
        return "agent_pins_v1_"+MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it.toInt() and 255)}
    }
}

internal class AgentPins(
    private val read:(String)->Set<String>,
    private val write:(String,Set<String>)->Unit,
) {
    fun values(scope:AgentPinScope):Set<String> =
        read(scope.preferenceKey).asSequence().filter(::validAddress).take(MAX_PINS).toSet()

    fun toggle(scope:AgentPinScope,address:String):Boolean {
        if(!validAddress(address)) return false
        val entries=values(scope)
        if(address !in entries && entries.size>=MAX_PINS) return false
        write(scope.preferenceKey,if(address in entries) entries-address else entries+address)
        return true
    }

    companion object {
        const val MAX_PINS=1000
        fun validAddress(value:String)=value.isNotBlank() && value.length<=256 && value.none(Char::isISOControl)
    }
}
