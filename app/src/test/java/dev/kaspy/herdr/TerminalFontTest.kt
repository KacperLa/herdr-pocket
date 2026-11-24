package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFontTest {
    @Test fun zoomChangesBaseFontAndPersistsAcrossGestures() {
        val bigger=TerminalFont.resize(18f,1.5f)
        assertEquals(27f,bigger,0f)
        assertEquals(18f,TerminalFont.resize(bigger,2f/3f),0.001f)
    }

    @Test fun bracketsAndIgnoresAccidentalGestureNoise() {
        assertEquals(6f,TerminalFont.resize(18f,0.1f),0f)
        assertEquals(40f,TerminalFont.resize(18f,10f),0f)
        assertEquals(18f,TerminalFont.resize(18f,1.02f),0f)
        assertEquals(18f,TerminalFont.resize(Float.NaN,Float.NaN),0f)
    }
}
