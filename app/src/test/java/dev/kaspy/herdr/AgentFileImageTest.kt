package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentFileImageTest {
    @Test fun picksImageByContentsRatherThanSuffixAndKeepsText() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
        assertTrue(AgentSftp.knownRasterHeader(png))
        assertTrue(AgentSftp.knownRasterHeader("GIF89a".toByteArray()))
        assertTrue(AgentSftp.knownRasterHeader("RIFF0000WEBP".toByteArray()))
        assertFalse(AgentSftp.knownRasterHeader("BM some ordinary UTF-8 text".toByteArray()))
        assertFalse(AgentSftp.knownRasterHeader("<svg></svg>".toByteArray()))
        assertFalse(AgentSftp.knownRasterHeader(png.copyOf(4)))
    }

    @Test fun boundsBothDecodedSideAndPixelCountBeforeAllocation() {
        assertEquals(80 to 40, imagePreviewSize(80, 40))
        assertEquals(2000 to 2000, imagePreviewSize(4000, 4000))
        assertEquals(4096 to 1, imagePreviewSize(32768, 1))
        assertNull(imagePreviewSize(24000, 24000))
        assertNull(imagePreviewSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(imagePreviewSize(0, 500))
        assertNull(imagePreviewSize(64, 32769))
    }
}
