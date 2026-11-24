package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalDictationTextTest {
    @Test fun onePrintableUnicodeLineWithoutAnEmbeddedEnter() {
        assertEquals(
            "café 😀 && echo test",
            terminalDictationText(" \r\n\u001b café\t\u0085😀 && echo\u2028test \r\n"),
        )
        assertEquals(
            "a b cż",
            terminalDictationText("a\u00A0b\u2029c\u202Eż\u0003\u0000\u009B\u007F"),
        )
    }

    @Test fun blankAndControlOnlyResultsDoNotSubmit() {
        assertNull(terminalDictationText(null))
        assertNull(terminalDictationText(""))
        assertNull(terminalDictationText("\u200C \u200D"))
        assertNull(terminalDictationText("\t \r\u2028\u200E\u0003\u001b"))
    }

    @Test fun finalCallbackIsSingleUseAndPinnedToItsOriginalTerminal() {
        val successful = TerminalDictationTicket("channel:1")
        assertEquals("hello", successful.finish("channel:1", listOf("", " \r", "hello")))
        assertNull(successful.finish("channel:1", listOf("duplicate")))

        val switched = TerminalDictationTicket("channel:1")
        assertNull(switched.finish("channel:2", listOf("wrong terminal")))
        assertNull(switched.finish("channel:1", listOf("stale callback")))

        val cancelled = TerminalDictationTicket("channel:1")
        cancelled.cancel()
        assertNull(cancelled.finish("channel:1", listOf("late result")))

        val disconnected = TerminalDictationTicket("channel:1")
        assertNull(disconnected.finish(null, listOf("offline")))
        assertNull(disconnected.finish("channel:1", listOf("reconnected")))

        val empty = TerminalDictationTicket("channel:1")
        assertNull(empty.finish("channel:1", listOf("\r\n")))
        assertNull(empty.finish("channel:1", listOf("unexpected second callback")))
    }

    @Test fun cancellationOrTerminalChangeRevokesPendingFinalDelivery() {
        val original = Any()
        val replacement = Any()
        val pending = TerminalDictationTicket(original)
        assertFalse(pending.allowsDelivery(original, resumed = true))
        assertEquals("submit me", pending.finish(original, listOf("submit me")))
        assertTrue(pending.allowsDelivery(original, resumed = true))
        assertFalse(pending.allowsDelivery(replacement, resumed = true))
        assertFalse(pending.allowsDelivery(original, resumed = false))
        pending.cancel()
        assertFalse(pending.allowsDelivery(original, resumed = true))
        assertNull(terminalDictationText("a".repeat(2049)))
    }

    @Test fun permissionResultCannotStartBeforeResumeOrStartTwice() {
        val original = Any()
        val ticket = TerminalDictationTicket(original, permissionGranted = false)
        assertFalse(ticket.claimStart(original, resumed = true))
        assertEquals(DictationPermissionResult.READY, ticket.permissionResult(true, original))
        assertFalse(ticket.claimStart(original, resumed = false))
        assertTrue(ticket.claimStart(original, resumed = true))
        assertFalse(ticket.claimStart(original, resumed = true))
    }

    @Test fun deniedCancelledOrEqualButDifferentTerminalCannotStart() {
        data class Pty(val name: String)
        val original = Pty("main")
        val replacement = Pty("main")
        val ticket = TerminalDictationTicket(original, permissionGranted = false)
        assertEquals(DictationPermissionResult.STALE, ticket.permissionResult(true, replacement))
        assertEquals(DictationPermissionResult.DENIED, ticket.permissionResult(false, original))
        assertFalse(ticket.claimStart(original, resumed = true))
        ticket.cancel()
        assertEquals(DictationPermissionResult.STALE, ticket.permissionResult(true, original))
        assertFalse(ticket.claimStart(original, resumed = true))
        assertNull(TerminalDictationTicket(original).finish(replacement, listOf("wrong channel")))
    }

    @Test fun supportMetadataDistinguishesOfflineModelStates() {
        assertEquals(DictationModel.INSTALLED, onDeviceSpeechModel("en-US", listOf("en_us"), listOf(), listOf("en-US")))
        assertEquals(DictationModel.PENDING, onDeviceSpeechModel("en-US", listOf(), listOf("en-US"), listOf("en-US")))
        assertEquals(DictationModel.NEEDS_DOWNLOAD, onDeviceSpeechModel("pl-PL", listOf("en-US"), listOf(), listOf("pl-PL")))
        assertEquals(DictationModel.UNKNOWN, onDeviceSpeechModel("pl-PL", listOf("pl"), listOf(), listOf()))
    }
}
