package dev.kaspy.herdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/** Main-thread bridge between the native chat writer and the active voice delegation. */
internal class LucidVoiceLink {
    private class Turn(val id:String,val prompt:String,val epoch:Long,val progress:(String)->Unit) {
        var ack=false
        var user=false
        val reply=StringBuilder()
        val done=CompletableDeferred<String?>()
    }

    data class Completion(val text:String,val epoch:Long)
    private var instance:String?=null
    private var sender:((String,String)->Boolean)?=null
    private var turn:Turn?=null
    private var epoch=0L
    var available by mutableStateOf(false)
        private set

    fun bind(instance:String,send:(String,String)->Boolean) {
        unbind()
        if(instance.isBlank()) return
        this.instance=instance
        sender=send
        available=true
    }

    fun unbind() {
        available=false
        instance=null
        sender=null
        invalidate()
    }

    private fun invalidate() {
        epoch++
        turn?.done?.complete(null)
        turn=null
    }

    fun isCurrent(completion:Completion)=available && completion.epoch==epoch

    suspend fun submit(prompt:String,onProgress:(String)->Unit):Completion? {
        val send=sender ?: return null
        if(turn!=null || !LiveAgentHerdr.validPrompt(prompt)) return null
        val active=Turn(UUID.randomUUID().toString(),prompt,++epoch,onProgress)
        turn=active
        try {
            if(!send(active.id,prompt)) return null
            val reply=withTimeoutOrNull(180_000) {active.done.await()} ?: return null
            return Completion(reply,active.epoch).takeIf(::isCurrent)
        } finally {
            if(turn===active) turn=null
        }
    }

    fun event(row:JSONObject) {
        if(!available) return
        val active=turn
        when(row.optString("type")) {
            "ack" -> if(active!=null && row.optString("id")==active.id) {
                if(row.optBoolean("ok")) active.ack=true else invalidate()
            }
            "user" -> {
                if(active!=null && active.ack && !active.user &&
                    row.optString("id")==active.id && row.optString("text")==active.prompt) active.user=true
                else invalidate()
            }
            "text" -> if(active?.user==true) {
                val text=lucidSafeDisplay(row.optString("text"),48000)
                active.reply.append(text)
                if(active.reply.length>48000) invalidate()
            }
            "progress" -> if(active?.user==true) {
                val text=lucidSafeDisplay(row.optString("title"),400).trim()
                if(text.isNotEmpty()) active.progress(text)
            }
            "plan" -> if(active?.user==true) {
                val entries=row.optJSONArray("entries")
                val text=(0 until (entries?.length() ?: 0).coerceAtMost(6)).mapNotNull {index ->
                    entries?.optJSONObject(index)?.optString("text")?.let {lucidSafeDisplay(it,180).trim()}?.takeIf(String::isNotEmpty)
                }.joinToString("; ")
                if(text.isNotEmpty()) active.progress(text)
            }
            "permission" -> if(active?.user==true) active.progress("The agent is waiting for approval in Lucid chat.")
            "done" -> if(active!=null) {
                if(active.ack && active.user && row.optString("reason")=="end_turn" && active.reply.isNotBlank())
                    active.done.complete(active.reply.toString())
                else invalidate()
            }
        }
    }
}
