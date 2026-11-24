package dev.kaspy.herdr

/** vterm key modifier bits, not Android META_* bits. */
internal data class TerminalDockLatch(val ctrl:Boolean=false,val alt:Boolean=false) {
    val mask:Int get()=(if(ctrl) 4 else 0) or (if(alt) 2 else 0)
    fun toggleCtrl()=copy(ctrl=!ctrl)
    fun toggleAlt()=copy(alt=!alt)
}

/** A renderer can adopt one PTY, never route a delayed callback to its successor. */
internal class TerminalOutputBinding(initial:Any?=null) {
    var owner:Any?=initial
        private set
    fun bind(pty:Any):Boolean {
        if(owner==null) owner=pty
        return owner===pty
    }
}

internal data class DockModifierSlot(
    val selected:Boolean=false,
    val revision:Long=0L,
    val reserved:Boolean=false,
) {
    val available:Boolean get()=selected && !reserved

    fun toggle()=DockModifierSlot(!selected,revision+1)

    fun finish(expected:Long?,accepted:Boolean):DockModifierSlot =
        if(expected==revision && reserved) copy(selected=!accepted,reserved=false) else this
}

internal data class DockModifierReservation(val ctrl:Long?,val alt:Long?) {
    val mask:Int get()=TerminalDockLatch(ctrl!=null,alt!=null).mask
}

/** One UI-thread latch, with a per-bit reservation while a packet awaits enqueueing. */
internal data class DockModifierState(
    val ctrl:DockModifierSlot=DockModifierSlot(),
    val alt:DockModifierSlot=DockModifierSlot(),
) {
    val mask:Int get()=TerminalDockLatch(ctrl.available,alt.available).mask

    fun reserve():Pair<DockModifierState,DockModifierReservation>? {
        val reservation=DockModifierReservation(
            ctrl.revision.takeIf {ctrl.available},
            alt.revision.takeIf {alt.available},
        )
        if(reservation.mask==0) return null
        return copy(
            ctrl=if(ctrl.available) ctrl.copy(reserved=true) else ctrl,
            alt=if(alt.available) alt.copy(reserved=true) else alt,
        ) to reservation
    }

    fun finish(reservation:DockModifierReservation,accepted:Boolean)=copy(
        ctrl=ctrl.finish(reservation.ctrl,accepted),
        alt=alt.finish(reservation.alt,accepted),
    )
}
