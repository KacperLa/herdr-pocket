package dev.kaspy.herdr

/** Start at most once for one eligible authenticated connection. */
internal class AgentWatchStartGate {
    private var attempted: Any? = null

    fun claim(lease: Any): Boolean {
        if (lease === attempted) return false
        attempted = lease
        return true
    }

    fun reset() {
        attempted = null
    }
}
