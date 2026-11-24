package org.connectbot.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedTerminalLinkTest {
    @Test fun stillPaintedTargetCanSurviveUnrelatedOutput() {
        val line = Any()
        val view = TerminalLinkViewport(4, 0, 80, 24, 7f, 10f, 563f, 242f)
        fun hit(identity: Any = line, layout: TerminalLinkViewport = view,
                url: String = "https://example.test/", osc: Boolean = false,
                row: Int = 3, start: Int = 5, end: Int = 24) =
            RenderedTerminalLink(url, osc, layout, identity, row, start, end)
        val down = hit()
        assertTrue(down.matches(hit(layout = view.copy())))
        assertFalse(down.matches(null))
        assertFalse(down.matches(hit(identity = Any())))
        assertFalse(down.matches(hit(url = "https://second.test/")))
        assertFalse(down.matches(hit(osc = true)))
        assertFalse(down.matches(hit(end = 25)))
        assertFalse(down.matches(hit(row = 4)))
        assertFalse(down.matches(hit(layout = view.copy(absoluteRow = 5))))
        assertFalse(down.matches(hit(layout = view.copy(scroll = 3))))
        assertFalse(down.matches(hit(layout = view.copy(charWidth = 8f))))
    }
}
