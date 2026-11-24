package dev.kaspy.herdr

/**
 * A direct Herdr v0.9.5 attach forwards the initial server repaint verbatim.
 * It begins synchronized output, hides the cursor, resets OSC-8 and clears the
 * screen before painting (even for an empty pane), then ends synchronized output.
 * Raw-mode setup, capability queries and rejected attachments don't emit this.
 *
 * https://github.com/herdrdev/herdr/blob/v0.9.5/src/protocol/render_ansi.rs#L686-L726
 * https://github.com/herdrdev/herdr/blob/v0.9.5/src/client/mod.rs#L1602-L1613
 */
internal class TuiAttachFrame {
    private var modernMatch = 0
    private var olderMatch = 0
    private var endMatch = 0
    private var inFrame = false
    private var ready = false

    fun observe(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size-offset): Boolean {
        if (ready) return true
        for (i in offset until offset + count) {
            if (inFrame) {
                endMatch = advance(End, EndOverlap, endMatch, bytes[i])
                if (endMatch == End.size) {
                    ready = true
                    return true
                }
            } else {
                modernMatch = advance(Start, StartOverlap, modernMatch, bytes[i])
                olderMatch = advance(OlderStart, OlderStartOverlap, olderMatch, bytes[i])
                inFrame = modernMatch == Start.size || olderMatch == OlderStart.size
            }
        }
        return false
    }

    private companion object {
        val Start = "\u001b[?2026h\u001b[?25l\u001b]8;;\u001b\\\u001b[2J".toByteArray(Charsets.US_ASCII)
        // In v0.7.2 the cursor is hidden just before entering synchronized output.
        val OlderStart = "\u001b[?2026h\u001b]8;;\u001b\\\u001b[2J".toByteArray(Charsets.US_ASCII)
        val End = "\u001b[?2026l".toByteArray(Charsets.US_ASCII)
        val StartOverlap = overlap(Start)
        val OlderStartOverlap = overlap(OlderStart)
        val EndOverlap = overlap(End)

        fun advance(pattern: ByteArray, overlap: IntArray, prior: Int, value: Byte): Int {
            var n = prior
            while (n > 0 && value != pattern[n]) n = overlap[n-1]
            return if (value == pattern[n]) n+1 else n
        }

        // Stream the matcher across arbitrary SSH packet boundaries without
        // retaining a second copy of the PTY output or copying each packet.
        fun overlap(pattern: ByteArray) = IntArray(pattern.size).also { table ->
            var n = 0
            for (i in 1 until pattern.size) {
                while (n > 0 && pattern[i] != pattern[n]) n = table[n-1]
                if (pattern[i] == pattern[n]) n++
                table[i] = n
            }
        }
    }
}
