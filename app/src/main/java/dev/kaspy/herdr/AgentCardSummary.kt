package dev.kaspy.herdr

internal data class AgentCardSummary(val description:String?,val path:String?)

internal fun agentCardSummary(session:CoreSession):AgentCardSummary {
    val path=session.spacesPath?.takeIf {it.isNotBlank()} ?: session.workingDirectory?.takeIf {it.isNotBlank()}
    val description=session.compactDescription?.takeUnless {
        it.isBlank() || it.trim().equals(session.name.trim(),ignoreCase=true) ||
            it.trim().equals(session.agent.trim(),ignoreCase=true) || it.trim()==path?.trim()
    }
    return AgentCardSummary(description,path)
}
