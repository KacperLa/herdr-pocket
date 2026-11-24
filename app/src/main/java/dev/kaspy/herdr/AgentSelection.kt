package dev.kaspy.herdr

internal enum class AgentSelection { RESUME_CURRENT, SWITCH, UNAVAILABLE }

internal fun agentSelection(
    selected: CoreSession,
    sessions: List<CoreSession>,
    activeAddress: String?,
    displayedPty: Any?,
    livePty: Any?,
): AgentSelection = when {
    activeAddress == null || displayedPty == null || displayedPty !== livePty ||
        selected.state != "live" || selected !in sessions -> AgentSelection.UNAVAILABLE
    selected.address == activeAddress -> AgentSelection.RESUME_CURRENT
    else -> AgentSelection.SWITCH
}
