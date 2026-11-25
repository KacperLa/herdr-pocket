package dev.kaspy.herdr

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs

private val DockBlack = Color.Black
internal val DockTargetSize = 48.dp
internal val DockGlyphSize = 24.dp
internal val DockGlyphStroke = 1.9.dp
private val DockActiveFill = Color(0xFF343B42)
private val DisabledWhite = Color.White.copy(alpha = .38f)

@Composable
internal fun TerminalDock(
    m:PocketModel,
    keyboardShownOrRequested:Boolean,
    inputEnabled:Boolean,
    opening:Boolean=false,
    onToggleKeyboard:()->Unit,
    ctrlSelected:Boolean,
    altSelected:Boolean,
    onToggleCtrl:()->Unit,
    onToggleAlt:()->Unit,
    onKey:(Int)->Unit,
    onSlash:()->Unit,
    onPaste:()->Unit,
) {
    val terminalIdentity=m.speechTerminalIdentity()
    val active=inputEnabled && terminalIdentity!=null
    // Expansion is intentionally not saved: never reopen an old PTY's menu after recreation.
    var directionsExpanded by remember(terminalIdentity,active) { mutableStateOf(false) }
    fun canSend()=active && m.speechTerminalIdentity()===terminalIdentity
    BackHandler(enabled=directionsExpanded) { directionsExpanded=false }
    TerminalDictationControls(enabled=inputEnabled && m.dictationEnabled,terminalIdentity=m::speechTerminalIdentity,send=m::sendDictation,pressEnter=m.dictationPressEnter,
        onExclusiveDock={directionsExpanded=false},buttonEnabled=!opening) { mic ->
        Surface(
            modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp),
            shape=CircleShape,
            color=DockBlack,
            contentColor=Color.White,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier=Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),
                    verticalAlignment=Alignment.CenterVertically,
                ) {
                    // Keep mic and keyboard in place; all other keys scroll on a narrow cover display.
                    Row(
                        modifier=Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(5.dp),
                        verticalAlignment=Alignment.CenterVertically,
                    ) {
                        TerminalDockIconKey(R.drawable.ic_terminal_tab,R.string.terminal_key_tab,active) {
                            if(canSend())onKey(KeyEvent.KEYCODE_TAB)
                        }
                        TerminalDockIconKey(R.drawable.ic_terminal_escape,R.string.terminal_key_escape,active) {
                            if(canSend())onKey(KeyEvent.KEYCODE_ESCAPE)
                        }
                        TerminalModifierKey(R.drawable.ic_terminal_control,R.string.terminal_key_control,active,ctrlSelected) {
                            if(canSend())onToggleCtrl()
                        }
                        TerminalModifierKey(R.drawable.ic_terminal_alt,R.string.terminal_key_alt,active,altSelected) {
                            if(canSend())onToggleAlt()
                        }
                        TerminalDockIconKey(R.drawable.ic_terminal_slash,R.string.terminal_key_slash,active) {
                            if(canSend())onSlash()
                        }
                        TerminalDockIconKey(R.drawable.ic_terminal_clipboard,R.string.terminal_paste_clipboard,active) {
                            directionsExpanded=false
                            if(canSend())onPaste()
                        }
                        DirectionKeys(
                            active=active,
                            expanded=directionsExpanded,
                            onExpandedChange={directionsExpanded=it && canSend()},
                            onKey={if(canSend())onKey(it)},
                            terminalIdentity=terminalIdentity,
                        )
                    }
                    mic()
                    TerminalDockIconKey(
                        R.drawable.ic_terminal_keyboard,
                        if(keyboardShownOrRequested) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard,
                        enabled=inputEnabled && !m.demo,
                    ) {directionsExpanded=false;onToggleKeyboard()}
                }
            }
        }
    }
}

@Composable
private fun TerminalDockIconKey(icon:Int,labelRes:Int,enabled:Boolean,onClick:()->Unit) {
    val label=stringResource(labelRes)
    Surface(
        onClick=onClick,enabled=enabled,
        modifier=Modifier.size(DockTargetSize).semantics {
            contentDescription=label
            role=Role.Button
        },
        color=DockBlack,
        contentColor=if(enabled) Color.White else DisabledWhite,
        shape=CircleShape,
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(DockGlyphSize))
        }
    }
}

private object DirectionPopupPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds:IntRect,
        windowSize:IntSize,
        layoutDirection:LayoutDirection,
        popupContentSize:IntSize,
    ):IntOffset {
        val x=(anchorBounds.left+(anchorBounds.width-popupContentSize.width)/2)
            .coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0))
        val above=anchorBounds.top-popupContentSize.height
        val y=(if(above>=0) above else anchorBounds.bottom)
            .coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x,y)
    }
}

@Composable
private fun DirectionKeys(
    active:Boolean,
    expanded:Boolean,
    onExpandedChange:(Boolean)->Unit,
    onKey:(Int)->Unit,
    terminalIdentity:Any?,
) {
    var buttonDown by remember(terminalIdentity,active) { mutableLongStateOf(-1000L) }
    var outsideDismissed by remember(terminalIdentity,active) { mutableLongStateOf(-2000L) }
    val directionLabel=stringResource(R.string.terminal_directional_keys)
    val expansionLabel=stringResource(if(expanded) R.string.terminal_directional_expanded else R.string.terminal_directional_collapsed)
    Box {
        Surface(
            onClick={
                // The popup can dismiss on this tap's down event before the button's up event.
                val sameTap=abs(buttonDown-outsideDismissed)<=40
                buttonDown=-1000L
                onExpandedChange(!expanded && !sameTap)
            },
            enabled=active,
            modifier=Modifier.size(DockTargetSize).pointerInput(terminalIdentity,active) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    buttonDown=SystemClock.uptimeMillis()
                }
            }.semantics {
                contentDescription=directionLabel
                role=Role.Button
                stateDescription=expansionLabel
            },
            color=if(expanded) DockActiveFill else DockBlack,
            border=if(expanded) BorderStroke(2.dp,Color.White) else null,
            contentColor=if(active) Color.White else DisabledWhite,
            shape=CircleShape,
        ) {
            Box(contentAlignment=Alignment.Center) {
                DpadIcon(if(active) Color.White else DisabledWhite)
            }
        }
        if(expanded && active) {
            Popup(
                popupPositionProvider=DirectionPopupPosition,
                onDismissRequest={outsideDismissed=SystemClock.uptimeMillis();onExpandedChange(false)},
                properties=PopupProperties(focusable=false,dismissOnClickOutside=true),
            ) {
                Surface(shape=RoundedCornerShape(18.dp),color=DockBlack,contentColor=Color.White,
                    border=BorderStroke(1.dp,Color(0xFF686868))) {
                    Row(
                        modifier=Modifier.padding(4.dp),
                        horizontalArrangement=Arrangement.spacedBy(4.dp),
                    ) {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            DpadShortcutButton(DpadShortcut.HOME,active,onKey)
                            DpadShortcutButton(DpadShortcut.LEFT,active,onKey)
                            DpadShortcutButton(DpadShortcut.END,active,onKey)
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            DpadShortcutButton(DpadShortcut.UP,active,onKey)
                            DpadShortcutButton(DpadShortcut.ENTER,active,onKey)
                            DpadShortcutButton(DpadShortcut.DOWN,active,onKey)
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            DpadShortcutButton(DpadShortcut.PAGE_UP,active,onKey)
                            DpadShortcutButton(DpadShortcut.RIGHT,active,onKey)
                            DpadShortcutButton(DpadShortcut.PAGE_DOWN,active,onKey)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DpadIcon(color:Color) {
    Canvas(Modifier.size(DockGlyphSize)) {
        val unit=size.width/24f
        val lobe=Path().apply {
            moveTo(8.5f*unit,1.5f*unit)
            lineTo(15.5f*unit,1.5f*unit)
            lineTo(15.5f*unit,7f*unit)
            lineTo(12f*unit,10.5f*unit)
            lineTo(8.5f*unit,7f*unit)
            close()
        }
        repeat(4) { quarter ->
            rotate(quarter*90f) {
                drawPath(lobe,color,style=Stroke(width=DockGlyphStroke.toPx(),join=StrokeJoin.Round))
            }
        }
    }
}

private enum class DpadShortcut(val keyCode:Int,val label:Int) {
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT,R.string.terminal_key_left),
    UP(KeyEvent.KEYCODE_DPAD_UP,R.string.terminal_key_up),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN,R.string.terminal_key_down),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT,R.string.terminal_key_right),
    ENTER(KeyEvent.KEYCODE_ENTER,R.string.terminal_key_enter),
    HOME(KeyEvent.KEYCODE_MOVE_HOME,R.string.terminal_key_home),
    END(KeyEvent.KEYCODE_MOVE_END,R.string.terminal_key_end),
    PAGE_UP(KeyEvent.KEYCODE_PAGE_UP,R.string.terminal_key_page_up),
    PAGE_DOWN(KeyEvent.KEYCODE_PAGE_DOWN,R.string.terminal_key_page_down),
}

@Composable
private fun DpadShortcutButton(shortcut:DpadShortcut,enabled:Boolean,onKey:(Int)->Unit) {
    val label=stringResource(shortcut.label)
    val ink=if(enabled) Color.White else DisabledWhite
    Surface(
        onClick={onKey(shortcut.keyCode)},enabled=enabled,
        modifier=Modifier.size(DockTargetSize).semantics {contentDescription=label;role=Role.Button},
        shape=CircleShape,color=DockBlack,contentColor=ink,
    ) {
        Box(contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(DockGlyphSize)) {
                val unit=size.width/24f
                fun line(x1:Float,y1:Float,x2:Float,y2:Float) {
                    drawLine(ink,Offset(x1*unit,y1*unit),Offset(x2*unit,y2*unit),DockGlyphStroke.toPx(),StrokeCap.Round)
                }
                when(shortcut) {
                    DpadShortcut.LEFT,DpadShortcut.UP,DpadShortcut.RIGHT,DpadShortcut.DOWN -> {
                        val turn=when(shortcut) {
                            DpadShortcut.UP -> 90f
                            DpadShortcut.RIGHT -> 180f
                            DpadShortcut.DOWN -> 270f
                            else -> 0f
                        }
                        rotate(turn) {
                            line(4f,12f,20f,12f)
                            line(4f,12f,10f,6f)
                            line(4f,12f,10f,18f)
                        }
                    }
                    DpadShortcut.ENTER -> {
                        line(19f,5f,19f,14f)
                        line(19f,14f,5f,14f)
                        line(5f,14f,10f,9f)
                        line(5f,14f,10f,19f)
                    }
                    DpadShortcut.HOME,DpadShortcut.END -> {
                        // A horizontal arrow toward the end stop, not an ordinary arrow.
                        val turn=if(shortcut==DpadShortcut.HOME) 0f else 180f
                        rotate(turn) {
                            line(3.5f,5f,3.5f,19f)
                            line(8f,12f,20f,12f)
                            line(8f,12f,13f,7f)
                            line(8f,12f,13f,17f)
                        }
                    }
                    DpadShortcut.PAGE_UP,DpadShortcut.PAGE_DOWN -> {
                        // Double vertical chevrons distinguish paging from the single arrows.
                        val turn=if(shortcut==DpadShortcut.PAGE_UP) 0f else 180f
                        rotate(turn) {
                            for(y in listOf(6f,13f)) {
                                line(5f,y+5f,12f,y)
                                line(12f,y,19f,y+5f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalModifierKey(
    icon:Int,labelRes:Int,enabled:Boolean,armed:Boolean,onToggle:()->Unit,
) {
    val description=stringResource(labelRes)
    val armedLabel=stringResource(if(armed) R.string.terminal_modifier_armed else R.string.terminal_modifier_off)
    Surface(
        modifier=Modifier.size(DockTargetSize)
            .toggleable(value=armed,enabled=enabled,role=Role.Checkbox,onValueChange={onToggle()})
            .semantics {
                contentDescription=description
                stateDescription=armedLabel
            },
        color=if(armed) DockActiveFill else DockBlack,
        contentColor=if(enabled) Color.White else DisabledWhite,
        border=if(armed) BorderStroke(2.dp,Color.White) else null,
        shape=CircleShape,
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(DockGlyphSize))
        }
    }
}
