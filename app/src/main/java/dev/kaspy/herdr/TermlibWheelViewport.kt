// ConnectBot Termlib 0.3.15 exposes the viewport controller as JVM-public
// but Kotlin-internal. Keep this single compile-time adapter pinned to that
// release; no reflection, fabricated touch events, or remote key injection.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.kaspy.herdr

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.RenderedTerminalLink
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalKeySink
import org.connectbot.terminal.TerminalWithAccessibility
import org.connectbot.terminal.TransactionalModifierManager
import org.connectbot.terminal.postEncodedUserKey

@Composable
internal fun TermlibWheelViewport(
    terminal:TerminalEmulator,
    modifier:Modifier,
    fontSize:TextUnit,
    keyboardEnabled:Boolean,
    showKeyboard:Boolean,
    modifiers:DockModifierManager,
    onTap:()->Unit,
    onImeChanged:(Boolean)->Unit,
    onSelectionAvailable:(SelectionController)->Unit,
    onLinkTestsAvailable:((Float,Float)->RenderedTerminalLink?)->Unit,
    onLocalScrollAvailable:((Int)->Boolean)->Unit,
) {
    key(terminal) { TerminalWithAccessibility(
        terminalEmulator=terminal,modifier=modifier,typeface=Typeface.MONOSPACE,
        initialFontSize=fontSize,minFontSize=10.sp,maxFontSize=40.sp,
        keyboardEnabled=keyboardEnabled,showSoftKeyboard=showKeyboard,
        onTerminalTap=onTap,onImeVisibilityChanged=onImeChanged,modifierManager=modifiers,
        onSelectionControllerAvailable=onSelectionAvailable,
        onRenderedLinkTestsAvailable=onLinkTestsAvailable,
        onScrollControllerAvailable={ controller ->
            onLocalScrollAvailable { lines ->
                val canMove=lines>0 && controller.scrollbackPosition<controller.maxScrollback ||
                    lines<0 && controller.scrollbackPosition>0
                if(canMove) controller.scrollBy(lines)
                canMove
            }
        },
    ) }
}

/** Stable for the exact renderer + active PTY; UI state changes only on explicit toggle or accepted key. */
internal class DockModifierManager(
    private val active:()->Any?,
    private val post:((TerminalKeySink)->Unit,(ByteArray)->Unit)->Boolean,
    private val send:(Any,ByteArray)->Boolean,
) : TransactionalModifierManager {
    private var owner:Any?=null
    private var ownerGeneration=0L
    private var latch by androidx.compose.runtime.mutableStateOf(DockModifierState())

    fun bind(pty:Any?) {
        if(owner!==pty) {
            owner=pty
            ownerGeneration++
            latch=DockModifierState()
        }
    }

    private fun current(pty:Any?)=owner!=null && owner===pty && pty===active()
    fun ctrlSelected(pty:Any?)=current(pty) && latch.ctrl.selected
    fun altSelected(pty:Any?)=current(pty) && latch.alt.selected
    override fun isCtrlActive()=current(owner) && latch.ctrl.available
    override fun isAltActive()=current(owner) && latch.alt.available
    override fun isShiftActive()=false

    // The transactional path consumes only after exact-PTY acceptance. Generic
    // Termlib housekeeping cannot clear an armed or in-flight state.
    override fun clearTransients()=Unit
    fun toggleCtrl(pty:Any?) {if(current(pty)) latch=latch.copy(ctrl=latch.ctrl.toggle())}
    fun toggleAlt(pty:Any?) {if(current(pty)) latch=latch.copy(alt=latch.alt.toggle())}

    override fun submitModifiedInput(encode:(TerminalKeySink)->Unit):Boolean {
        val pty=owner ?: return false
        if(!current(pty)) return false
        val generation=ownerGeneration
        val (reserved,reservation)=latch.reserve() ?: return false
        latch=reserved
        val posted=post(encode) { bytes ->
            if(ownerGeneration==generation) {
                val accepted=bytes.isNotEmpty() && current(pty) && send(pty,bytes)
                latch=latch.finish(reservation,accepted)
            }
        }
        if(!posted && ownerGeneration==generation) latch=latch.finish(reservation,false)
        return posted
    }

    fun sendDockInput(terminal:TerminalKeySink,pty:Any,encode:(TerminalKeySink,Int)->Unit) {
        if(!current(pty)) return
        val mask=latch.mask
        if(mask!=0) submitModifiedInput { encode(it,mask) }
        else encode(terminal,0)
    }
}

internal fun dockModifierManager(
    terminal:TerminalEmulator,active:()->Any?,send:(Any,ByteArray)->Boolean,
)=DockModifierManager(active,{ encode,deliver -> terminal.postEncodedUserKey(encode,deliver) },send)

internal fun termlibDockKey(keyCode:Int):Int? = when(keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER -> org.connectbot.terminal.VTermKey.ENTER
    android.view.KeyEvent.KEYCODE_ESCAPE -> org.connectbot.terminal.VTermKey.ESCAPE
        android.view.KeyEvent.KEYCODE_TAB -> org.connectbot.terminal.VTermKey.TAB
        android.view.KeyEvent.KEYCODE_DPAD_UP -> org.connectbot.terminal.VTermKey.UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> org.connectbot.terminal.VTermKey.DOWN
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> org.connectbot.terminal.VTermKey.LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> org.connectbot.terminal.VTermKey.RIGHT
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> org.connectbot.terminal.VTermKey.HOME
        android.view.KeyEvent.KEYCODE_MOVE_END -> org.connectbot.terminal.VTermKey.END
        android.view.KeyEvent.KEYCODE_PAGE_UP -> org.connectbot.terminal.VTermKey.PAGEUP
        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> org.connectbot.terminal.VTermKey.PAGEDOWN
        else -> null
}
