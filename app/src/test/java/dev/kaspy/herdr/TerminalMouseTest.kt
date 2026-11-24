package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalMouseTest {
    private val mouse = TerminalMouse()
    private fun send(text: String) = mouse.observe(text.toByteArray())
    private fun wheel(direction: TerminalMouse.Direction, x: Int = 37, y: Int = 18) =
        mouse.wheel(direction, x, y)?.toString(Charsets.US_ASCII)

    @Test fun defaultAndTrackingWithoutSgrDoNotEmitPackets() {
        assertFalse(mouse.wheelEnabled)
        assertNull(wheel(TerminalMouse.Direction.DOWN))
        send("\u001b[?1049;1000h")
        assertTrue(mouse.alternateScreen)
        assertFalse(mouse.wheelEnabled)
        assertNull(wheel(TerminalMouse.Direction.UP))
    }

    @Test fun parsesFragmentedCombinedNegotiationAndEncodesExactSgrCoordinates() {
        val negotiation = "hello\u001b[?1049;1002;1006h".toByteArray()
        for (i in negotiation.indices) mouse.observe(negotiation, i, 1)
        assertTrue(mouse.alternateScreen)
        assertTrue(mouse.wheelEnabled)
        assertEquals("\u001b[<64;37;18M", wheel(TerminalMouse.Direction.UP))
        assertEquals("\u001b[<65;2;1M", wheel(TerminalMouse.Direction.DOWN, 2, 1))
        assertNull(wheel(TerminalMouse.Direction.DOWN, 0, 1))
        assertNull(wheel(TerminalMouse.Direction.DOWN, 1, -1))
        send("\u001b[?1049l")
        assertFalse(mouse.alternateScreen)
        assertTrue(mouse.wheelEnabled) // apps may negotiate from the main screen
        send("\u001b[?1002;1006l")
        assertFalse(mouse.wheelEnabled)
    }

    @Test fun representativeTuiEnablesSgrAfterLegacyModesAndCleansUp() {
        send("\u001b[?1049h\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1015h\u001b[?1006h")
        assertTrue(mouse.alternateScreen)
        assertEquals("\u001b[<65;120;40M", wheel(TerminalMouse.Direction.DOWN, 120, 40))
        send("\u001b[?1006l\u001b[?1015l\u001b[?1003l\u001b[?1002l\u001b[?1000l\u001b[?1049l")
        assertNull(wheel(TerminalMouse.Direction.UP))
        assertFalse(mouse.alternateScreen)
    }

    @Test fun ignoresNegotiationsEmbeddedInEveryVtString() {
        for (start in listOf("]", "P", "_", "X", "^")) {
            mouse.reset()
            send("\u001b" + start + "inline\u001b[?1049;1000")
            send(";1006h\u001bc")
            send("\u001b")
            send("\\normal")
            assertFalse(mouse.wheelEnabled)
            assertFalse(mouse.alternateScreen)
        }
        send("\u001b]title\u001b[?1000;1006h\u0007")
        assertFalse(mouse.wheelEnabled)
        send("\u001b[?1003;1006h")
        assertTrue(mouse.wheelEnabled)
        send("\u001b_Payload\u001b[?1003;1006l\u001bc\u001b\\")
        assertTrue(mouse.wheelEnabled)
    }

    @Test fun ignoresQueriesMalformedSyntaxAndRawC1InUtf8Text() {
        send("\u001b[?1000;1006\$p") // DECRQM report request
        send("\u001b[?1000:1006h")
        send("\u001b[?1000;999999999;1006h")
        send("\u001b[?1000;;1006h")
        send("\u001b[1000;1006h")
        mouse.observe(byteArrayOf(0x9b.toByte()) + "?1000;1006h".toByteArray())
        send("Л?1000;1006h") // UTF-8 continuation byte 0x9b is not a C1 CSI
        assertFalse(mouse.wheelEnabled)
        send("\u001b[?1000\u0018;1006h")
        assertFalse(mouse.wheelEnabled)
    }

    @Test fun disablesAlternateEncodingsTrackingExitAndSequenceResets() {
        send("\u001b[?47;1000;1006h")
        assertTrue(mouse.wheelEnabled)
        send("\u001b[?1016h")
        assertFalse(mouse.wheelEnabled)
        send("\u001b[?1006h")
        assertTrue(mouse.wheelEnabled)
        send("\u001b[?1015h")
        assertFalse(mouse.wheelEnabled)
        send("\u001b[?1006;1003h\u001b[?1000l")
        assertFalse(mouse.wheelEnabled)
        send("\u001b[?1000h\u001b[c") // not RIS (this is a CSI query)
        assertTrue(mouse.wheelEnabled)
        send("\u001b")
        send("c")
        assertFalse(mouse.wheelEnabled)
        assertFalse(mouse.alternateScreen)
        send("\u001b[?1047;1002;1006h\u001b[!p")
        assertFalse(mouse.wheelEnabled)
        assertTrue(mouse.alternateScreen) // Herdr repaint uses DECSTR on the alternate screen.
        send("\u001b[?1000h")
        assertTrue(mouse.wheelEnabled) // The coordinate format survived the soft reset.
        send("\u001b[?1000;1006")
        mouse.reset()
        send("h")
        assertFalse(mouse.wheelEnabled)
    }

    @Test fun sendsExactlyOneSgrLeftPressAndMatchingReleaseOnlyWhenNegotiated() {
        assertNull(mouse.click(12,7))
        send("\u001b[?1000h")
        assertNull(mouse.click(12,7))
        send("\u001b[?1006h")
        assertEquals("\u001b[<0;12;7M\u001b[<0;12;7m",mouse.click(12,7)?.toString(Charsets.US_ASCII))
        assertNull(mouse.click(0,7))
        assertNull(mouse.click(12,0))
        send("\u001b[?1006l")
        assertNull(mouse.click(12,7))
    }

    @Test fun disablingThenRenegotiatingInvalidatesAnInProgressTap() {
        send("\u001b[?1000;1006h")
        val first=mouse.touchModeRevision
        send("\u001b[?1006l\u001b[?1006h")
        assertTrue(mouse.clickEnabled)
        assertTrue(mouse.touchModeRevision>first)
        send("\u001b[?1016h")
        assertNull(mouse.click(12,7))
    }
}
