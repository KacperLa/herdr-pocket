package dev.kaspy.herdr

/** A mic permission response cannot start audio for a replacement terminal. */
internal class LiveVoicePermissionGate {
    private var owner: Any?=null
    private var granted=false

    fun request(route:Any):Boolean {
        if(owner!=null) return false
        owner=route
        granted=false
        return true
    }

    /** Returns true only when this denial pertains to the still-visible route. */
    fun result(allowed:Boolean,current:Any?):Boolean {
        val relevant=owner!=null && owner===current
        if(!allowed || !relevant) clear()
        else granted=true
        return relevant
    }

    fun claim(current:Any?,resumed:Boolean):Any? {
        val route=owner ?: return null
        if(route!==current) {clear();return null}
        if(!granted || !resumed) return null
        clear()
        return route
    }

    fun removeIfChanged(current:Any?) {if(owner!==current) clear()}
    fun clear() {owner=null;granted=false}
}
