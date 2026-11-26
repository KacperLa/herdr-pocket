package dev.kaspy.herdr

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpadSlideTest {
    private val grid = listOf(
        listOf(DpadShortcut.HOME, DpadShortcut.UP, DpadShortcut.PAGE_UP),
        listOf(DpadShortcut.LEFT, DpadShortcut.ENTER, DpadShortcut.RIGHT),
        listOf(DpadShortcut.END, DpadShortcut.DOWN, DpadShortcut.PAGE_DOWN),
    )
    private val keyCodes = listOf(
        listOf(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP),
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT),
        listOf(KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN),
    )
    private fun bounds(x: Float, y: Float) = grid.flatMapIndexed { row, keys ->
        keys.mapIndexed { col, key ->
            val left = x + col * 104f
            val top = y + row * 104f
            key to Rect(left, top, left + 96f, top + 96f)
        }
    }

    @Test fun centersResolveToTheNineExistingTerminalKeyCodesAfterPopupIsClamped() {
        val window = IntSize(840, 1100)
        val menu = IntSize(320, 320)
        val placements = listOf(
            IntRect(420, 892, 516, 988) to IntOffset(308, 572),
            IntRect(0, 126, 96, 222) to IntOffset(0, 222),
            IntRect(744, 760, 840, 856) to IntOffset(520, 440),
        )
        for ((anchor, expected) in placements) {
            val location = DirectionPopupPosition.calculatePosition(anchor, window, LayoutDirection.Ltr, menu)
            assertEquals(expected, location)
            val x = location.x + 8f
            val y = location.y + 8f
            val layout = bounds(x, y)
            for ((row, keys) in keyCodes.withIndex()) {
                for ((col, code) in keys.withIndex()) {
                    val point = Offset(x + col * 104f + 48f, y + row * 104f + 48f)
                    assertEquals(code, dpadKeyAt(point, layout)?.keyCode)
                }
            }
        }
    }

    @Test fun slippingIntoGapsOrOffThePadDoesNotSelectTheLastHoveredKey() {
        val layout = bounds(30f, 100f)
        assertEquals(DpadShortcut.HOME, dpadKeyAt(Offset(40f, 110f), layout))
        assertNull(dpadKeyAt(Offset(130f, 110f), layout))
        assertNull(dpadKeyAt(Offset(40f, 200f), layout))
        assertNull(dpadKeyAt(Offset(32f, 430f), layout))
        assertEquals(DpadShortcut.DOWN, dpadKeyAt(Offset(140f, 320f), layout))
    }

    @Test fun missingLayoutOrUnresolvableWindowPositionNeverSelectsAKey() {
        assertNull(dpadKeyAt(Offset.Unspecified, bounds(0f, 0f)))
        assertNull(dpadKeyAt(Offset(Float.POSITIVE_INFINITY, 4f), bounds(0f, 0f)))
        assertNull(dpadKeyAt(Offset(4f, 4f), emptyList()))
    }
}
