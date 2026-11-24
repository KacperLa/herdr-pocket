package dev.kaspy.herdr

internal data class TerminalCell(val column:Int,val row:Int)

internal fun terminalCellAt(x:Float,y:Float,cellWidth:Float,cellHeight:Float,columns:Int,rows:Int):TerminalCell? {
    if(!x.isFinite() || !y.isFinite() || !cellWidth.isFinite() || !cellHeight.isFinite() ||
        x<0 || y<0 || cellWidth<=0 || cellHeight<=0 || columns<=0 || rows<=0) return null
    val column=(x/cellWidth).toInt()
    val row=(y/cellHeight).toInt()
    return if(column<columns && row<rows) TerminalCell(column+1,row+1) else null
}

/** The renderer floors the measured span by cell size before notifying the PTY. */
internal fun terminalGridMatches(width:Int,height:Int,cellWidth:Float,cellHeight:Float,columns:Int,rows:Int):Boolean =
    width>0 && height>0 && cellWidth.isFinite() && cellHeight.isFinite() && cellWidth>0 && cellHeight>0 &&
        (width/cellWidth).toInt().coerceAtLeast(1)==columns && (height/cellHeight).toInt().coerceAtLeast(1)==rows

internal class RemoteClickTarget(val pty:Any,val modeRevision:Long) {
    fun matches(current:RemoteClickTarget?) = current!=null && pty===current.pty && modeRevision==current.modeRevision
}

/** Compose button index zero is primary. A chord or local shortcut stays local. */
internal fun isExclusiveTerminalPrimaryButton(primary:Boolean,lastPressedIndex:Int,modified:Boolean):Boolean =
    primary && lastPressedIndex==0 && !modified

/** An excursion, timeout, pinch or canceled gesture cannot later become a click. */
internal class TerminalTouchTap(
    private val startX:Float,private val startY:Float,private val startMillis:Long,
    private val slop:Float,private val longPressMillis:Long,
    private val physicalMouse:Boolean=false,
) {
    private var canceled=false
    fun cancel() {canceled=true}
    fun move(x:Float,y:Float) {
        val dx=x-startX
        val dy=y-startY
        if(!x.isFinite() || !y.isFinite() || dx*dx+dy*dy>=slop*slop) cancel()
    }
    fun observeMouseButtons(pointerStillPressed:Boolean,exclusivePrimary:Boolean,anyButtonPressed:Boolean,modified:Boolean) {
        if(physicalMouse && (modified || if(pointerStillPressed) !exclusivePrimary else anyButtonPressed)) cancel()
    }
    fun canRelease(now:Long):Boolean = !canceled && now>=startMillis && now-startMillis<longPressMillis
    fun releaseOnce(now:Long):Boolean {
        val eligible=canRelease(now)
        cancel()
        return eligible
    }
}
