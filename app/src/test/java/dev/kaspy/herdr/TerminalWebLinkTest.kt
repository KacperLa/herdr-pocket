package dev.kaspy.herdr

import org.connectbot.terminal.TerminalLinkRow
import org.connectbot.terminal.strictTerminalLinkAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalWebLinkTest {
    @Test fun acceptsOnlyWellFormedWebUrls() {
        assertEquals("https://Example.test:8443/a%20b?q=x#answer",
            TerminalWebLink.parse("HTTPS://Example.test:8443/a%20b?q=x#answer")?.url)
        assertEquals("http://127.0.0.1:8080/path", TerminalWebLink.parse("http://127.0.0.1:8080/path")?.url)
        assertEquals("http://[::1]:8080/", TerminalWebLink.parse("http://[::1]:8080/")?.url)
        assertEquals("http://localhost/", TerminalWebLink.parse("http://localhost/")?.url)
        listOf("intent://example.test/#Intent;scheme=https;end", "javascript:alert(1)", "file:///tmp/test",
            "content://example.test/test", "ftp://example.test/test", "//example.test", "https:///path",
            "https://user@example.test", "https://foo%40example.test", "https://example.test:/",
            "https://example.test:65536/", "https://example.test:0/", "https://example.test/\u202Ehidden",
            " https://example.test", "https://example.test\\bad", "https://example.test/%0A", "https://a.test/%",
            "https://example.test/\n", "https://example.test/" + "x".repeat(4096)).forEach {
            assertNull(it.take(35), TerminalWebLink.parse(it))
        }
    }

    @Test fun opensCompleteVisibleTokensOnly() {
        val text = "Open (HTTPS://example.test/p(a)), then https://two.test.\u0000"
        assertEquals("HTTPS://example.test/p(a)", hit(0, text.indexOf("example"), row(text)))
        assertEquals("https://two.test", hit(0, text.indexOf("two"), row(text)))
        assertNull(hit(0, text.indexOf("then"), row(text)))
        assertNull(hit(0, text.lastIndex, row(text)))
        assertNull(hit(0, 5, row("https://example.test")))
        assertNull(hit(0, 5, row("ftp://example.test  ")))
        assertNull(hit(0, 16, row("javascript:https://example.test ")))
        assertNull(hit(0, 12, row("https://exam\uFFFDple.test/ ")))
        assertNull(hit(0, 10, row("https://exämple.test/ ")))
        assertNull(hit(0, 10, row("https://example.test\\evil/ ")))
        assertNull(hit(0, 10, row("https://example.test\u202Eevil/ ")))
        assertNull(hit(0, 10, row("https://example.test/café/ ")))
    }

    @Test fun joinsOnlyActualWrapAndRejectsClippedOrMovedBoundaries() {
        val first = row("see https://example.te", soft = true)
        val second = row("st:443/ab?c=d \u0000")
        assertEquals("https://example.test:443/ab?c=d", hit(0, 11, first, second))
        assertEquals("https://example.test:443/ab?c=d", hit(1, 5, first, second))
        assertNull(hit(0, 11, first))
        assertNull(hit(0, 11, first.copy(softWrapped = false), second))
        assertNull(hit(1, 5, first.copy(softWrapped = false), second))
        val hardLine = row("https://one.test/  ")
        assertEquals("https://one.test/", hit(0, 8, hardLine, row("?never-join-this ")))
        assertNull(hit(1, 5, hardLine, row("?never-join-this ")))
        assertNull(hit(0, 9, row("https://a.test/" + "x".repeat(4100) + " ")))
    }

    private fun row(text: String, soft: Boolean = false) = TerminalLinkRow(text, soft)
    private fun hit(r: Int, c: Int, vararg rows: TerminalLinkRow) =
        strictTerminalLinkAt(r, c, rows.size) { rows[it] }?.url
}
