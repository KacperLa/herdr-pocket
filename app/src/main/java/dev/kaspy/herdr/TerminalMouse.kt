package dev.kaspy.herdr

/**
 * Observes the same UTF-8 VT stream as the renderer. Only top-level, complete
 * 7-bit DEC mode changes count; never infer tracking from text/payload bytes.
 * Keep one instance per displayed PTY and feed it from the ordered UI stream.
 */
class TerminalMouse {
    enum class Direction { UP, DOWN }

    private enum class State { GROUND, ESCAPE, ESCAPE_INTERMEDIATE, CSI, STRING, STRING_ESCAPE }
    private var state = State.GROUND
    private var osc = false
    private var tracking = false
    private var sgr = false
    private var leader = 0
    private var pristine = true
    private var invalid = false
    private var digits = 0
    private var number = 0
    private var modeCount = 0
    private val modes = IntArray(24)

    var alternateScreen = false
        private set

    val wheelEnabled: Boolean get() = tracking && sgr
    val clickEnabled: Boolean get() = tracking && sgr
    var touchModeRevision: Long = 0
        private set

    fun reset() {
        touchModeRevision++
        state = State.GROUND
        tracking = false
        sgr = false
        alternateScreen = false
        osc = false
    }

    fun observe(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        for (i in offset until offset + length) accept(bytes[i].toInt() and 0xff)
    }

    /** SGR wheel press; wheel events have no corresponding release packet. */
    fun wheel(direction: Direction, column: Int, row: Int): ByteArray? {
        if (!wheelEnabled || column < 1 || row < 1) return null
        val button = if (direction == Direction.UP) 64 else 65
        return "\u001b[<$button;$column;${row}M".toByteArray(Charsets.US_ASCII)
    }

    /** One complete left click in one SSH queue item; uppercase press, lowercase release. */
    fun click(column: Int, row: Int): ByteArray? {
        if (!clickEnabled || column < 1 || row < 1) return null
        return "\u001b[<0;$column;${row}M\u001b[<0;$column;${row}m".toByteArray(Charsets.US_ASCII)
    }

    private fun accept(byte: Int) {
        // CAN/SUB cancel the sequence in progress, without changing prior modes.
        if (byte == 0x18 || byte == 0x1a) {
            state = State.GROUND
            return
        }
        if (state == State.STRING || state == State.STRING_ESCAPE) {
            when {
                (osc && byte == 0x07) || (state == State.STRING_ESCAPE && byte == 0x5c) -> {
                    state = State.GROUND
                    osc = false
                }
                byte == 0x1b -> state = State.STRING_ESCAPE
                byte >= 0x20 && byte != 0x7f -> state = State.STRING
            }
            return
        }
        if (byte == 0x1b) {
            state = State.ESCAPE
            return
        }
        if (byte < 0x20 || byte == 0x7f) return
        when (state) {
            State.GROUND -> Unit
            State.ESCAPE -> when (byte) {
                '['.code -> beginCsi()
                ']'.code, 'P'.code, '_'.code, 'X'.code, '^'.code -> {
                    osc = byte == ']'.code
                    state = State.STRING
                }
                'c'.code -> reset() // RIS
                in 0x20..0x2f -> state = State.ESCAPE_INTERMEDIATE
                else -> state = State.GROUND
            }
            State.ESCAPE_INTERMEDIATE -> if (byte !in 0x20..0x2f) state = State.GROUND
            State.CSI -> acceptCsi(byte)
            State.STRING, State.STRING_ESCAPE -> Unit
        }
    }

    private fun beginCsi() {
        state = State.CSI
        leader = 0
        pristine = true
        invalid = false
        digits = 0
        number = 0
        modeCount = 0
    }

    private fun acceptCsi(byte: Int) {
        if (byte in 0x40..0x7e) {
            state = State.GROUND
            if (!invalid && leader == '!'.code && byte == 'p'.code) {
                // DECSTR clears Termlib's active mouse flags but does not switch
                // the displayed buffer or reset the negotiated coordinate format.
                if (tracking) touchModeRevision++
                tracking = false
            } else if (!invalid && leader == '?'.code && (byte == 'h'.code || byte == 'l'.code)) {
                pushMode()
                if (!invalid) for (i in 0 until modeCount) setMode(modes[i], byte == 'h'.code)
            }
            return
        }
        if (invalid) return
        if (pristine && (byte == '?'.code || byte == '!'.code)) {
            leader = byte
        } else if (leader == '?'.code) {
            when (byte) {
                in '0'.code..'9'.code -> {
                    if (digits >= 5) invalid = true
                    else {
                        digits++
                        number = number * 10 + byte - '0'.code
                    }
                }
                ';'.code -> pushMode()
                else -> invalid = true
            }
        } else {
            invalid = true
        }
        pristine = false
    }

    private fun pushMode() {
        if (digits == 0 || modeCount == modes.size) {
            invalid = true
            return
        }
        modes[modeCount++] = number
        digits = 0
        number = 0
    }

    private fun setMode(mode: Int, enabled: Boolean) {
        val wasClickEnabled = clickEnabled
        val wasAlternateScreen = alternateScreen
        when (mode) {
            // The renderer has one tracking mode, not separate stacked modes.
            1000, 1002, 1003 -> tracking = enabled
            9, 1001 -> if (enabled) tracking = false // X10 click/highlight, not wheel tracking
            1006 -> sgr = enabled
            1005, 1015 -> sgr = false // Termlib resets these alternate encodings to X10 on DECRST.
            1016 -> if (enabled) sgr = false // Not SGR cell coordinates (uses pixels).
            47, 1047, 1049 -> alternateScreen = enabled
        }
        if (clickEnabled != wasClickEnabled || alternateScreen != wasAlternateScreen) touchModeRevision++
    }
}
