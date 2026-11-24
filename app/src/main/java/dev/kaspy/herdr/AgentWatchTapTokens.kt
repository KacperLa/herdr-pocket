package dev.kaspy.herdr

/** Bounded process-local capabilities. Status intents never carry a host, pane ID, or credential. */
internal class AgentWatchTapTokens<T>(private val capacity:Int=32,private val lifetimeMs:Long=86_400_000L) {
    private data class Entry<T>(val target:T,val inserted:Long)
    private val entries=LinkedHashMap<String,Entry<T>>()

    fun put(token:String,target:T,now:Long) {
        entries.entries.removeAll {now-it.value.inserted>=lifetimeMs}
        entries[token]=Entry(target,now)
        if(entries.size>capacity) {
            val first=entries.entries.iterator()
            first.next()
            first.remove()
        }
    }

    fun take(token:String,now:Long):T? =
        entries.remove(token)?.takeIf {now-it.inserted in 0 until lifetimeMs}?.target

    fun remove(token:String) {entries.remove(token)}
}
