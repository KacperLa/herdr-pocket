/*
 * Herdr Pocket addition to the ConnectBot Termlib source overlay.
 * Licensed under the Apache License, Version 2.0.
 */
package org.connectbot.terminal

/** Visible cells of one physical terminal row. A hard newline is never joined. */
data class TerminalLinkRow(val text: String, val softWrapped: Boolean)

data class StrictTerminalLink(val url: String, val start: Int, val endExclusive: Int)

/**
 * Look up a plain HTTP(S) token at one actual cell. The only allowed cross-row
 * continuation is a native soft wrap; decoration/whitespace is real text.
 * Blank cells (NUL), oversized chains and unknown boundaries fail closed.
 */
fun strictTerminalLinkAt(
    row: Int,
    column: Int,
    lineCount: Int,
    lineAt: (Int) -> TerminalLinkRow,
): StrictTerminalLink? {
    if (row !in 0 until lineCount || column < 0) return null
    val selected = lineAt(row)
    if (column !in selected.text.indices || !selected.text[column].webTokenChar()) return null

    var first = row
    while (first > 0 && lineAt(first - 1).softWrapped) {
        if (row - first >= MAX_WRAP_ROWS - 1) return null
        first--
    }
    val chain = StringBuilder()
    var tapOffset = -1
    for (i in first until minOf(lineCount, first + MAX_WRAP_ROWS)) {
        val visible = if (i == row) selected else lineAt(i)
        if (chain.length + visible.text.length > MAX_SCAN_LENGTH) return null
        if (i == row) tapOffset = chain.length + column
        chain.append(visible.text)
        if (!visible.softWrapped) break
        // Do not expose a partial URL where the next soft-wrapped row is unavailable.
        if (i == lineCount - 1 || i == first + MAX_WRAP_ROWS - 1) return null
    }
    if (tapOffset < 0) return null

    var from = 0
    while (from < chain.length) {
        val opening = HTTP_SCHEME.find(chain, from) ?: return null
        val start = opening.range.first
        if (start > tapOffset) return null
        var end = opening.range.last + 1
        while (end < chain.length && chain[end].webTokenChar()) end++
        from = end
        // Never truncate an oversized/unsafe token into a different destination.
        if (tapOffset !in start until end || end - start > MAX_LINK_LENGTH) continue
        // A full physical row with no terminator can be a clipped/wrapped
        // server redraw whose cursor protocol did not preserve soft-wrap data.
        if (end == chain.length || !chain[end].webTokenTerminator()) return null
        val displayed = chain.substring(start, end).trimDetectedUrl()
        val last = start + displayed.length
        if (tapOffset < last && displayed.length > opening.value.length) {
            return StrictTerminalLink(displayed, start, last)
        }
    }
    return null
}

private val HTTP_SCHEME = Regex("(?<![\\p{L}\\p{N}\\p{M}\\p{Cf}_:/@.%=-])https?://", RegexOption.IGNORE_CASE)
private const val MAX_LINK_LENGTH = 4096
private const val MAX_SCAN_LENGTH = 8192
private const val MAX_WRAP_ROWS = 24
private fun Char.webTokenChar() =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "/:@!$&'()*+,;=-._~%?#[]"

private fun Char.webTokenTerminator() =
    this == '\u0000' || isWhitespace() || this in "<>\"{}|`“”‘’"
