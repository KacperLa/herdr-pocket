package dev.kaspy.herdr

/**
 * Accumulates Compose scroll deltas in wheel ticks: positive down, negative up.
 * Owned by the gesture coroutine. Fractions don't cross PTY or route identities.
 */
internal class PhysicalMouseWheel {
    private var target: Any? = null
    private var remainder = 0.0

    fun reset() {
        target = null
        remainder = 0.0
    }

    fun update(identity: Any, deltaY: Float): Int {
        if (identity !== target) {
            target = identity
            remainder = 0.0
        }
        if (!deltaY.isFinite() || deltaY == 0f) return 0
        val total = remainder + deltaY.toDouble()
        val whole = if (total < 0) kotlin.math.ceil(total) else kotlin.math.floor(total)
        remainder = total - whole
        return whole.coerceIn(-MAX_STEPS.toDouble(), MAX_STEPS.toDouble()).toInt()
    }

    private companion object {
        // Bound remote work per event; discard excess whole steps rather than backlog them.
        const val MAX_STEPS = 12
    }
}
