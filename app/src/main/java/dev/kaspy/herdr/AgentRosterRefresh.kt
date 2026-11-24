package dev.kaspy.herdr

/** Matches only roster reads, not other operations sharing the same SSH channel queue. */
internal data class AgentRosterRefresh(val operationId:Long?=null) {
    val active get()=operationId!=null
    fun complete(id:Long) = if(operationId==id) AgentRosterRefresh() else this
}

internal fun canRefreshAgentRoster(
    visible:Boolean,
    connected:Boolean,
    hasAgentTarget:Boolean,
    operationPending:Boolean,
    dialogOpen:Boolean,
)=visible && connected && hasAgentTarget && !operationPending && !dialogOpen

/** On a healthy nonempty roster, the cards alone are the normal view. */
internal fun agentRosterNotice(
    liveCount:Int,
    backendLabel:String,
    confirmation:String?,
    interruption:String?,
):String = confirmation ?: interruption ?: if(liveCount==0) "No live ${backendLabel} sessions found." else ""
