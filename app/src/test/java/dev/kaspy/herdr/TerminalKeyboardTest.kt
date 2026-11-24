package dev.kaspy.herdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyboardTest {
    @Test fun newTerminalDoesNotRequestKeyboard() {
        assertFalse(TerminalKeyboard().shownOrRequested)
    }

    @Test fun terminalTapOrDockCanShowAndDockCanHide() {
        val initial = TerminalKeyboard()
        assertTrue(initial.show().requested)
        val shown = initial.toggle().reportVisibility(true)
        assertTrue(shown.shownOrRequested)
        assertFalse(shown.toggle().requested)
        assertFalse(shown.toggle().reportVisibility(false).shownOrRequested)
    }

    @Test fun systemDismissalAllowsTheDockToShowAgain() {
        val dismissed = TerminalKeyboard().show().reportVisibility(true).reportVisibility(false)
        assertFalse(dismissed.shownOrRequested)
        assertTrue(dismissed.toggle().requested)
    }

    @Test fun initialHiddenReportDoesNotSwallowAnExplicitShow() {
        assertTrue(TerminalKeyboard().show().reportVisibility(false).requested)
    }
}
