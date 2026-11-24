package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class TerminalDockKeyLogicTest {
    @Test fun togglesUseNativeVtermBitsAndSecondTapCancels() {
        val blank=TerminalDockLatch()
        assertEquals(0,blank.mask)
        assertEquals(4,blank.toggleCtrl().mask)
        assertEquals(2,blank.toggleAlt().mask)
        assertEquals(6,blank.toggleCtrl().toggleAlt().mask)
        assertEquals(blank,blank.toggleCtrl().toggleCtrl())
        assertEquals(blank,blank.toggleAlt().toggleAlt())
    }

    @Test fun aDelayedRendererOutputCannotFollowPtyChanges() {
        val original=Any()
        val successor=Any()
        val renderer=TerminalOutputBinding()
        assertNull(renderer.owner)
        assertTrue(renderer.bind(original))
        assertFalse(renderer.bind(successor))
        assertTrue(renderer.bind(original))
        assertSame(original,renderer.owner)
        assertSame(successor,TerminalOutputBinding(successor).owner)
    }

    @Test fun reservesBothUntilAnActualAcceptedPacketAndDoesNotReuseThem() {
        val first=Any()
        val second=Any()
        var current:Any?=first
        var accepts=false
        val pending=mutableListOf<(ByteArray)->Unit>()
        val writes=mutableListOf<Pair<Any,List<Byte>>>()
        val modifiers=DockModifierManager(
            {current},{_,deliver -> pending.add(deliver)},{pty,bytes ->
                if(!accepts) false else writes.add(pty to bytes.toList())
            },
        )
        modifiers.bind(first)
        modifiers.toggleCtrl(first)
        modifiers.toggleAlt(first)
        assertTrue(modifiers.ctrlSelected(first))
        assertTrue(modifiers.altSelected(first))
        modifiers.clearTransients()
        assertTrue(modifiers.isCtrlActive())
        assertTrue(modifiers.isAltActive())

        assertTrue(modifiers.submitModifiedInput {})
        assertFalse(modifiers.submitModifiedInput {})
        assertFalse(modifiers.isCtrlActive())
        assertFalse(modifiers.isAltActive())
        assertTrue(modifiers.ctrlSelected(first)) // not consumed before enqueue acknowledgement
        pending.removeAt(0)(byteArrayOf(0x1b,0x03)) // Alt + Ctrl+C from the native encoder
        assertTrue(modifiers.isCtrlActive())        // failed enqueue restored both
        assertTrue(modifiers.isAltActive())

        accepts=true
        assertTrue(modifiers.submitModifiedInput {})
        pending.removeAt(0)(byteArrayOf(0x1b,0x03))
        assertEquals(listOf(first to listOf<Byte>(0x1b,0x03)),writes)
        assertFalse(modifiers.ctrlSelected(first))
        assertFalse(modifiers.altSelected(first))

        modifiers.toggleAlt(first)
        assertTrue(modifiers.submitModifiedInput {})
        current=second
        modifiers.bind(second)
        modifiers.toggleCtrl(second)
        pending.removeAt(0)(byteArrayOf(0x1b,0x78))
        assertEquals(1,writes.size)
        assertTrue(modifiers.ctrlSelected(second))
        assertFalse(modifiers.altSelected(second))
    }

    @Test fun independentOffAndAnOldAckCannotConsumeFreshlyArmedState() {
        val initial=DockModifierState()
        val combined=initial.copy(ctrl=initial.ctrl.toggle(),alt=initial.alt.toggle())
        assertEquals(6,combined.mask)
        assertEquals(2,combined.copy(ctrl=combined.ctrl.toggle()).mask)
        assertEquals(4,combined.copy(alt=combined.alt.toggle()).mask)
        val (reserved,old)=checkNotNull(combined.reserve())
        assertEquals(0,reserved.mask)
        val fresh=reserved.copy(alt=reserved.alt.toggle().toggle())
        val (second,another)=checkNotNull(fresh.reserve())
        assertEquals(2,another.mask)
        val acknowledged=second.finish(old,true)
        assertFalse(acknowledged.ctrl.selected)
        assertTrue(acknowledged.alt.selected)
        assertEquals(0,acknowledged.mask)
        assertEquals(2,acknowledged.finish(another,false).mask)
    }

    @Test fun virtualKeyRoutesCombinedBitsThenNextKeyRoutesZero() {
        val pty=Any()
        val calls=mutableListOf<Pair<Int,Int>>()
        val sink=object : org.connectbot.terminal.TerminalKeySink {
            override fun dispatchKey(modifiers:Int,key:Int) {calls+=modifiers to key}
            override fun dispatchCharacter(modifiers:Int,codepoint:Int) {calls+=modifiers to codepoint}
        }
        val modifier=DockModifierManager({pty},{encode,deliver ->
            encode(sink)
            deliver(byteArrayOf(27,91,49,59,55,65))
            true
        },{_,_ -> true})
        modifier.bind(pty)
        modifier.toggleCtrl(pty)
        modifier.toggleAlt(pty)
        val virtual=checkNotNull(termlibDockKey(android.view.KeyEvent.KEYCODE_DPAD_UP))
        modifier.sendDockInput(sink,pty) {target,mask -> target.dispatchKey(mask,virtual)}
        modifier.sendDockInput(sink,pty) {target,mask -> target.dispatchKey(mask,virtual)}
        assertEquals(listOf(6 to org.connectbot.terminal.VTermKey.UP,0 to org.connectbot.terminal.VTermKey.UP),calls)
    }

    @Test fun navigationPopupKeysMapToNativeTerminalKeys() {
        val keyCodes=listOf(
            android.view.KeyEvent.KEYCODE_MOVE_HOME,
            android.view.KeyEvent.KEYCODE_MOVE_END,
            android.view.KeyEvent.KEYCODE_PAGE_UP,
            android.view.KeyEvent.KEYCODE_PAGE_DOWN,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_ESCAPE,
        )
        val native=listOf(
            org.connectbot.terminal.VTermKey.HOME,
            org.connectbot.terminal.VTermKey.END,
            org.connectbot.terminal.VTermKey.PAGEUP,
            org.connectbot.terminal.VTermKey.PAGEDOWN,
            org.connectbot.terminal.VTermKey.ENTER,
            org.connectbot.terminal.VTermKey.TAB,
            org.connectbot.terminal.VTermKey.ESCAPE,
        )
        assertEquals(native,keyCodes.map(::termlibDockKey))
    }

    @Test fun emptyOrUnpostedEncodingKeepsAnArmedKey() {
        val pty=Any()
        val state=DockModifierManager({pty},{_,_ -> false},{_,_ -> error("not posted")})
        state.bind(pty)
        state.toggleCtrl(pty)
        assertFalse(state.submitModifiedInput {})
        assertTrue(state.isCtrlActive())
    }
}
