package dev.kaspy.herdr

/** UI-thread state; old service callbacks cannot act on a new upload. */
internal class UploadForegroundGate {
    private var next = java.util.concurrent.ThreadLocalRandom.current().nextLong()
    private var held: Long? = null
    private var promoted = false

    fun claim(): Long? {
        if (held != null) return null
        do { next++ } while (next == 0L)
        return next.also { held = it; promoted = false }
    }

    fun owns(token: Long): Boolean = token == held

    fun isPromoted(token: Long): Boolean = owns(token) && promoted

    fun promote(token: Long): Boolean {
        if (!owns(token) || promoted) return false
        promoted = true
        return true
    }

    fun release(token: Long): Boolean {
        if (!owns(token)) return false
        held = null
        promoted = false
        return true
    }
}
