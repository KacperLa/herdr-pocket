package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionalModifierTest {
    @Test fun committedTextUsesBothModifiersOnTheFirstKeyOnly() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val terminal = fakeTerminal(calls)
        val handler = KeyboardHandler(terminal, modifierManager(terminal))
        handler.onCommittedText("")
        handler.onCommittedText("cx")
        handler.onCommittedText("b")
        assertEquals(
            listOf(Triple("dispatchCharacter", 6, 'c'.code), Triple("dispatchCharacter", 0, 'x'.code),
                Triple("dispatchCharacter", 0, 'b'.code)),
            calls,
        )
    }

    @Test fun crlfAndSupplementaryTextRemainSingleLogicalKeys() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val terminal = fakeTerminal(calls)
        val handler = KeyboardHandler(terminal, modifierManager(terminal))
        handler.onCommittedText("\r\nx")
        handler.modifierManager = modifierManager(terminal)
        handler.onCommittedText("😀a")
        assertEquals(
            listOf(Triple("dispatchKey", 6, VTermKey.ENTER), Triple("dispatchCharacter", 0, 'x'.code),
                Triple("dispatchCharacter", 6, 0x1F600), Triple("dispatchCharacter", 0, 'a'.code)),
            calls,
        )
    }

    @Test fun characterInputAndImeRewriteAreDifferentRoutes() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val terminal = fakeTerminal(calls)
        val handler = KeyboardHandler(terminal, modifierManager(terminal))
        handler.onImeDeleteBackward()
        handler.onCharacterInput('c')
        handler.onCharacterInput('c')
        assertEquals(
            listOf(Triple("dispatchKey", 0, VTermKey.BACKSPACE), Triple("dispatchCharacter", 6, 'c'.code),
                Triple("dispatchCharacter", 0, 'c'.code)),
            calls,
        )
    }

    @Test fun oversizedArmedCommitDoesNotSendOrConsumeAnyPart() {
        val calls=mutableListOf<Triple<String,Int,Int>>()
        val terminal=fakeTerminal(calls)
        val handler=KeyboardHandler(terminal,modifierManager(terminal))
        assertEquals(false,handler.onCommittedText("x".repeat(16*1024+1)))
        assertEquals(emptyList<Triple<String,Int,Int>>(),calls)
        assertEquals(true,handler.onCommittedText("c"))
        assertEquals(listOf(Triple("dispatchCharacter",6,'c'.code)),calls)
    }

    private fun modifierManager(terminal:TerminalKeySink) = object:TransactionalModifierManager {
        var armed = true
        override fun isCtrlActive() = armed
        override fun isAltActive() = armed
        override fun isShiftActive() = false
        override fun clearTransients() { error("must use acknowledged input") }
        override fun submitModifiedInput(encode:(TerminalKeySink)->Unit):Boolean {
            encode(terminal)
            armed = false
            return true
        }
    }

    private fun fakeTerminal(calls:MutableList<Triple<String,Int,Int>>):TerminalKeySink =
        object : TerminalKeySink {
            override fun dispatchCharacter(modifiers:Int,codepoint:Int) {
                calls += Triple("dispatchCharacter",modifiers,codepoint)
            }
            override fun dispatchKey(modifiers:Int,key:Int) {
                calls += Triple("dispatchKey",modifiers,key)
            }
        }
}
