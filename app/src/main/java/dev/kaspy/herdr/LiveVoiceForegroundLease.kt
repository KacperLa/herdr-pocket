package dev.kaspy.herdr

import kotlinx.coroutines.CompletableDeferred

/**
 * Process-local, main-thread-only microphone lease. Activity replacement is not
 * an owner transition; only the exact call and its promoted service own it.
 */
internal class LiveVoiceForegroundLeaseGate {
    internal enum class End { USER, LOST }

    internal class Lease(val token: Long, val onEnd: (End) -> Unit) {
        val ready = CompletableDeferred<Boolean>()
        var promoted = false
            internal set
    }

    private var next = java.util.concurrent.ThreadLocalRandom.current().nextLong()
    private var held: Lease? = null

    fun claim(onEnd: (End) -> Unit): Lease? {
        if (held != null) return null
        do { next++ } while (next == 0L)
        return Lease(next, onEnd).also { held = it }
    }

    fun owns(token: Long): Boolean = held?.token == token
    fun isPromoted(token: Long): Boolean = owns(token) && held?.promoted == true

    fun promote(token: Long): Boolean {
        val lease = held?.takeIf { it.token == token && !it.promoted } ?: return false
        lease.promoted = true
        lease.ready.complete(true)
        return true
    }

    /** Cut off old notification actions, starts, and destruction callbacks. */
    fun clear(token: Long, reason: End? = null): Boolean {
        val lease = held?.takeIf { it.token == token } ?: return false
        held = null
        lease.ready.complete(false)
        if (reason != null && lease.promoted) lease.onEnd(reason)
        return true
    }
}
