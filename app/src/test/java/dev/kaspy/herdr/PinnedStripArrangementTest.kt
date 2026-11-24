package dev.kaspy.herdr

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PinnedStripArrangementTest {
    @Test fun centersOneTileAndTwoTilesWithinAvailableWidth() {
        assertArrayEquals(intArrayOf(122), arrange(360,116))
        assertArrayEquals(intArrayOf(60,184), arrange(360,116,116))
        assertArrayEquals(intArrayOf(0,124), arrange(240,116,116))
    }

    private fun arrange(width:Int,vararg tiles:Int)=IntArray(tiles.size).also { positions ->
        with(pinnedStripArrangement) { Density(1f).arrange(width,tiles,LayoutDirection.Ltr,positions) }
    }
}
