package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class PreviewFileLinesTest {
    @Test fun sourceOffsetsNormalizeCrLfAndCrWithoutInkWidthsOrVisualWrap() {
        val display=displayPreviewText("one\r\n😀\r\n\n")
        assertEquals("one\n😀\n\n",display)
        assertArrayEquals(intArrayOf(0,4,7,8),previewSourceLineStarts(display))
        assertEquals("one\ntwo",displayPreviewText("one\rtwo"))
        assertArrayEquals(intArrayOf(0),previewSourceLineStarts("x".repeat(8192)))
        assertArrayEquals(intArrayOf(),previewSourceLineStarts(""))
    }
}
