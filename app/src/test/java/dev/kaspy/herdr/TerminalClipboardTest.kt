package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalClipboardTest {
    @Test fun preservesMultilineUnicodeWithoutAddingEnter() {
        for (text in listOf("café 😀", "   ", "a\tβ\r\nsecond\nthird")) {
            assertEquals(text, terminalClipboardText(text))
        }
    }

    @Test fun rejectsEmptyOverBudgetAndEscapeSequences() {
        for (text in listOf(null, "", "okay\u001b[201~\nsh", "x\u009by", "é".repeat(32763))) {
            assertNull(terminalClipboardText(text))
        }
        val max = "é".repeat(32762)
        assertEquals(max, terminalClipboardText(max))
    }
}
