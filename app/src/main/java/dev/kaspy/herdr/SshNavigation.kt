package dev.kaspy.herdr

/** Navigation never changes which SSH profile owns the live transport. */
internal sealed interface SshNavigation {
    data object Session : SshNavigation
    data object Settings : SshNavigation
    data object AppSettings : SshNavigation
    data object LucidChat : SshNavigation
    data object Connections : SshNavigation
    data class Edit(val id:String?) : SshNavigation

    fun back():SshNavigation = when(this) {
        Session, Settings, AppSettings, is Edit -> Connections
        LucidChat -> Session
        Connections -> Connections
    }
}

internal enum class SavedHostPrimaryAction { OPEN_AGENTS, OPEN_TERMINAL, CONNECT }

internal fun savedHostPrimaryAction(isSelected:Boolean,connectionOpen:Boolean,hasAgentsView:Boolean) = when {
    !isSelected || !connectionOpen -> SavedHostPrimaryAction.CONNECT
    hasAgentsView -> SavedHostPrimaryAction.OPEN_AGENTS
    else -> SavedHostPrimaryAction.OPEN_TERMINAL
}

internal enum class TerminalBackDestination { AGENTS, CONNECTIONS }

internal fun terminalBackDestination(hasAgentsView:Boolean) =
    if(hasAgentsView) TerminalBackDestination.AGENTS else TerminalBackDestination.CONNECTIONS
