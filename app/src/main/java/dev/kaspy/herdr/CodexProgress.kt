package dev.kaspy.herdr

/** Only user-visible commentary from the newest saved root turn. */
internal object CodexProgress {
    enum class Availability { AVAILABLE, NO_SESSION, UNAVAILABLE }
    data class Snapshot(val availability:Availability,val messages:List<String> = emptyList())

    fun from(page:LiveCodexHistory.Page):Snapshot {
        val turn=page.turns.firstOrNull()
        val items=turn?.rawItems
        val messages=ArrayDeque<String>()
        if(items!=null) for(index in 0 until items.length()) {
            val item=items.optJSONObject(index) ?: continue
            if(item.opt("type")!="agentMessage" || item.opt("phase")!="commentary") continue
            val raw=item.opt("text") as? String ?: continue
            val clean=raw.replace("\r\n","\n").filter { !it.isISOControl() || it=='\n' || it=='\t' }.trim()
            if(clean.isEmpty() || messages.lastOrNull()==clean) continue
            if(messages.size==8) messages.removeFirst()
            messages.addLast(if(clean.length>1200) {
                val end=if(clean[1199].isHighSurrogate()) 1199 else 1200
                clean.take(end).trimEnd()+"…"
            } else clean)
        }
        return Snapshot(Availability.AVAILABLE,messages.toList())
    }
}
