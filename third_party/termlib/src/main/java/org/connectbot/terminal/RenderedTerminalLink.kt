/*
 * Herdr Pocket addition to the ConnectBot Termlib source overlay.
 * Licensed under the Apache License, Version 2.0.
 */
package org.connectbot.terminal

/**
 * One link hit in the last drawn viewport. Comparing hits is intentionally
 * strict. A change to another row or the cursor alone need not invalidate
 * a tap if the jointly drawn target line, exact address, span and layout survive.
 */
class RenderedTerminalLink internal constructor(
    val url: String,
    val osc8: Boolean,
    private val view: TerminalLinkViewport,
    private val lineIdentity: Any,
    private val row: Int,
    private val start: Int,
    private val end: Int,
) {
    fun matches(other: RenderedTerminalLink?): Boolean =
        other != null && view == other.view && lineIdentity === other.lineIdentity && row == other.row &&
            start == other.start && end == other.end && url == other.url && osc8 == other.osc8
}

internal data class TerminalLinkViewport(
    val absoluteRow: Int,
    val scroll: Int,
    val cols: Int,
    val rows: Int,
    val charWidth: Float,
    val charHeight: Float,
    val width: Float,
    val height: Float,
)

// Top-level and explicit properties avoid a K2/UAST lint bug with
// constructor properties on a private nested class in this module.
private class TerminalLinkDrawStamp(
    snapshot: TerminalSnapshot,
    scroll: Int,
    charWidth: Float,
    charHeight: Float,
    width: Float,
    height: Float,
) {
    val snapshot = snapshot
    val scroll = scroll
    private val charWidth = charWidth
    private val charHeight = charHeight
    private val width = width
    private val height = height

    fun matches(snapshot: TerminalSnapshot, scroll: Int, cw: Float, ch: Float, w: Float, h: Float) =
        this.snapshot === snapshot && this.scroll == scroll && charWidth == cw &&
            charHeight == ch && width == w && height == h
}

internal class TerminalLinkFrames {
    // Plain main-thread state, not Compose state: publishing a drawn frame must
    // never schedule another draw or relink a cell between pointer passes.
    private var drawn: TerminalLinkDrawStamp? = null

    fun publish(state: TerminalScreenState, cw: Float, ch: Float, w: Float, h: Float, allowed: Boolean) {
        if (!allowed) {
            drawn = null
        } else if (drawn?.matches(state.snapshot, state.scrollbackPosition, cw, ch, w, h) != true) {
            drawn = TerminalLinkDrawStamp(state.snapshot, state.scrollbackPosition, cw, ch, w, h)
        }
    }

    fun hit(
        state: TerminalScreenState,
        x: Float, y: Float,
        cw: Float, ch: Float, w: Float, h: Float,
        logicalColumn: (row: Int, visualColumn: Int) -> Int,
    ): RenderedTerminalLink? {
        val frame = drawn ?: return null
        if (!frame.matches(state.snapshot, state.scrollbackPosition, cw, ch, w, h) ||
            !x.isFinite() || !y.isFinite() || x < 0f || y < 0f ||
            cw <= 0f || ch <= 0f || x >= w || y >= h ||
            x >= state.snapshot.cols * cw || y >= state.snapshot.rows * ch
        ) return null
        val row = (y / ch).toInt()
        val visual = (x / cw).toInt()
        val col = logicalColumn(row, visual)
        val line = state.getVisibleLine(row)
        if (col !in line.cells.indices) return null
        val absolute = state.visibleLineIndex(row)
        val view = TerminalLinkViewport(absolute, frame.scroll, frame.snapshot.cols, frame.snapshot.rows, cw, ch, w, h)

        val explicit = line.semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }
        if (explicit != null) {
            // A hidden destination, even a same-looking label, always gets a
            // separate destination confirmation in the app.
            return explicit.metadata?.let {
                RenderedTerminalLink(it, true, view, line, row, explicit.startCol, explicit.endCol)
            }
        }

        val link = strictTerminalLinkAt(absolute, col, state.totalLines) {
            val l = state.getLine(it)
            TerminalLinkRow(l.linkColumnText, l.softWrapped)
        } ?: return null
        return RenderedTerminalLink(link.url, false, view, line, row, link.start, link.endExclusive)
    }
}
