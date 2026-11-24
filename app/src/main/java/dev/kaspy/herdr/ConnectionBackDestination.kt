package dev.kaspy.herdr

internal enum class ConnectionBackDestination { AGENTS, TERMINAL }

/** A settings back action is available only for the currently displayed, still-live PTY. */
internal fun connectionBackDestination(
    connected: Boolean,
    terminalVisible: Boolean,
    agentsVisible: Boolean,
    displayedPty: Any?,
    livePty: Any?,
    activeCoreAddress: String?,
): ConnectionBackDestination? = when {
    !connected || terminalVisible || agentsVisible || displayedPty == null || livePty !== displayedPty -> null
    activeCoreAddress != null -> ConnectionBackDestination.AGENTS
    else -> ConnectionBackDestination.TERMINAL
}
