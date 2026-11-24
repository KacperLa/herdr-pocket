package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class TerminalTouchTapTest {
    @Test fun validTapMustStayInsideSlopAndNotBecomeLongPress() {
        val tap=TerminalTouchTap(10f,10f,100L,8f,500L)
        tap.move(12f,13f)
        assertTrue(tap.canRelease(180L))
        assertFalse(tap.canRelease(600L))
        tap.move(18f,11f)
        tap.move(10f,10f)
        assertFalse(tap.canRelease(190L))
    }
    @Test fun canceledTouchCannotEmitOnUp() {
        val tap=TerminalTouchTap(10f,10f,100L,8f,500L)
        tap.cancel()
        assertFalse(tap.canRelease(180L))
    }
    @Test fun onlyAnUnmodifiedExclusivePrimaryMouseDownCanArm() {
        assertTrue(isExclusiveTerminalPrimaryButton(true,0,false))
        assertFalse(isExclusiveTerminalPrimaryButton(false,-1,false))
        assertFalse(isExclusiveTerminalPrimaryButton(false,1,false))
        assertFalse(isExclusiveTerminalPrimaryButton(true,1,false))
        assertFalse(isExclusiveTerminalPrimaryButton(true,4,false))
        assertFalse(isExclusiveTerminalPrimaryButton(true,0,true))
    }
    @Test fun buttonUpWithNoButtonsProducesAtMostOneCandidate() {
        val tap=TerminalTouchTap(10f,10f,100L,2f,500L,physicalMouse=true)
        tap.observeMouseButtons(true,true,true,false)
        tap.move(10.5f,10f)
        tap.observeMouseButtons(false,false,false,false)
        assertTrue(tap.releaseOnce(140L))
        assertFalse(tap.releaseOnce(141L))
    }
    @Test fun mouseChordDragOrModifierCannotProduceRemoteClick() {
        fun mouse()=TerminalTouchTap(10f,10f,100L,2f,500L,physicalMouse=true)
        assertFalse(mouse().apply {observeMouseButtons(true,false,true,false)}.releaseOnce(130L))
        assertFalse(mouse().apply {observeMouseButtons(false,false,true,false)}.releaseOnce(130L))
        assertFalse(mouse().apply {observeMouseButtons(true,false,true,true)}.releaseOnce(130L))
        assertFalse(mouse().apply {move(12f,10f);move(10f,10f)}.releaseOnce(130L))
        assertFalse(mouse().apply {cancel()}.releaseOnce(130L))
    }
    @Test fun preciseCellsExcludeRightBottomGuttersAndInvalidCoordinates() {
        assertEquals(TerminalCell(1,1),terminalCellAt(0f,0f,10.25f,22f,9,4))
        assertEquals(TerminalCell(2,2),terminalCellAt(10.25f,22f,10.25f,22f,9,4))
        assertEquals(TerminalCell(9,4),terminalCellAt(92.24f,87.99f,10.25f,22f,9,4))
        assertNull(terminalCellAt(92.25f,10f,10.25f,22f,9,4))
        assertNull(terminalCellAt(20f,88f,10.25f,22f,9,4))
        assertNull(terminalCellAt(-1f,30f,10.25f,22f,9,4))
        assertNull(terminalCellAt(Float.NaN,30f,10.25f,22f,9,4))
    }
    @Test fun pinchCanChangeGridWithoutChangingViewportPixels() {
        assertTrue(terminalGridMatches(100,100,10f,20f,10,5))
        assertFalse(terminalGridMatches(100,100,12f,25f,10,5))
        assertTrue(terminalGridMatches(100,100,12f,25f,8,4))
        assertFalse(terminalGridMatches(0,100,12f,25f,8,4))
    }
    @Test fun targetNeedsSamePtyAndOriginalModeGeneration() {
        val pty=Any()
        val target=RemoteClickTarget(pty,3)
        assertTrue(target.matches(RemoteClickTarget(pty,3)))
        assertFalse(target.matches(RemoteClickTarget(Any(),3)))
        assertFalse(target.matches(RemoteClickTarget(pty,4)))
        assertFalse(target.matches(null))
    }
}
