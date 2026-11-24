package dev.kaspy.herdr

/** Local UI intent only. Do not save this across terminal changes or navigation. */
internal data class TerminalKeyboard(val requested: Boolean = false, val visible: Boolean = false) {
    val shownOrRequested get() = visible || requested

    fun show() = copy(requested = true)
    fun toggle() = copy(requested = !shownOrRequested)

    fun reportVisibility(isVisible: Boolean) = copy(
        // System Back can hide the IME independently of the dock.
        requested = if (visible && !isVisible) false else requested,
        visible = isVisible,
    )
}
