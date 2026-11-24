package dev.kaspy.herdr

/** Identifiers only: retries still load the key and authenticate through the normal SSH flow. */
internal data class ReconnectProfile(val endpoint: HostEndpoint, val user: String, val pin: String, val keyUri: String)
internal sealed interface ReconnectMode {
    data class AgentSession(val autoSelect: Boolean, val preferredAgent: String?, val backend:AgentBackend=AgentBackend.CORE, val tuiSession:String?=null) : ReconnectMode
    data class Custom(val command: String) : ReconnectMode
}
internal data class ReconnectTarget(val profile: ReconnectProfile, val mode: ReconnectMode) {
    fun isAvailable(current: ReconnectProfile?, unavailable: Boolean, command: String, tuiSession:String?=null) =
        !unavailable && current==profile && when(mode) {
            is ReconnectMode.Custom -> mode.command==command
            is ReconnectMode.AgentSession -> mode.backend==AgentBackend.CORE || mode.tuiSession==tuiSession
        }
}


/** UI only: canRetry already enforces same saved profile, host pin and dial state. */
internal enum class TerminalRecoveryUi { RECONNECTING, RECONNECT, SETTINGS }

internal fun terminalRecoveryUi(
    retained: Boolean,
    terminalVisible: Boolean,
    inFlight: Boolean,
    canRetry: Boolean,
): TerminalRecoveryUi? = when {
    !retained || !terminalVisible -> null
    inFlight -> TerminalRecoveryUi.RECONNECTING
    canRetry -> TerminalRecoveryUi.RECONNECT
    else -> TerminalRecoveryUi.SETTINGS
}
