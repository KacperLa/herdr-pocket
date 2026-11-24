package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal enum class AgentWatchStatus { DONE, BLOCKED }
internal enum class AgentWatchState { CONNECTING, WATCHING, RETRYING, UNSUPPORTED, FORWARDING_UNAVAILABLE, AUTH_FAILED, PIN_FAILED }
internal data class AgentWatchAlert(val terminalId:String,val name:String,val status:AgentWatchStatus)
internal class WatchApiError(val code:String,val unsupported:Boolean=false) : IOException()
internal data class WatchRoster(val panes:Set<String>,val agents:List<CoreSession>)

internal object AgentWatchProtocol {
    private val globalEvents=setOf("pane.created","pane.closed","pane.exited","pane.agent_detected")
    private val allowedEvents=globalEvents+"pane.agent_status_changed"
    private val paneId=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    const val MAX_PANES=512
    const val LIST_ID="pocket-watch-snapshot"
    const val SUB_ID="pocket-watch-subscribe"

    fun requestSnapshot()=JSONObject().put("id",LIST_ID).put("method","session.snapshot")
        .put("params",JSONObject()).toString()+"\n"

    fun subscribe(panes:Set<String>):String {
        require(panes.size<=MAX_PANES && panes.all(paneId::matches))
        val entries=JSONArray()
        globalEvents.forEach {entries.put(JSONObject().put("type",it))}
        panes.sorted().forEach {entries.put(JSONObject().put("type","pane.agent_status_changed").put("pane_id",it))}
        return JSONObject().put("id",SUB_ID).put("method","events.subscribe")
            .put("params",JSONObject().put("subscriptions",entries)).toString()+"\n"
    }

    fun socket(status:String,namedSession:String?):String? {
        val root=JSONObject(status)
        if(root.opt("running")!=true) return null
        val advertised=root.opt("session")
        if(advertised!=null && advertised!=JSONObject.NULL && advertised !is String) throw IOException("Invalid Herdr session")
        fun canonical(value:String?)=value?.takeUnless {it=="default"}
        if(canonical(advertised as? String)!=canonical(namedSession)) throw IOException("Herdr session mismatch")
        val value=root.opt("socket") as? String ?: throw IOException("Herdr socket path missing")
        if(!value.startsWith("/") || value.toByteArray().size !in 2..107 ||
            value.any {it.isISOControl()} || value.split('/').drop(1).any { it.isEmpty() || it=="." || it==".." })
            throw IOException("Invalid Herdr socket path")
        return value
    }

    private fun checkedResponse(root:JSONObject,id:String):JSONObject {
        if(root.opt("id")!=id) throw IOException("Unexpected Herdr response")
        root.optJSONObject("error")?.let {error ->
            val code=error.optString("code").take(80)
            val message=(error.opt("message") as? String)?.take(512).orEmpty()
            // Herdr may serialize an unsupported API enum as invalid_request.
            // Do not treat unrelated invalid requests or races as upgrade signals.
            val unknown=Regex("unknown variant `([a-z._]+)`").find(message)?.groupValues?.get(1)
            val missingApi=code in setOf("unknown_method","method_not_found") ||
                code=="invalid_request" && (unknown in allowedEvents || unknown in setOf("events.subscribe","session.snapshot"))
            throw WatchApiError(code,missingApi)
        }
        return root.optJSONObject("result") ?: throw IOException("Missing Herdr result")
    }

    fun acknowledged(line:String) {
        if(checkedResponse(JSONObject(line),SUB_ID).opt("type")!="subscription_started")
            throw IOException("Unexpected subscription result")
    }

    fun invalidates(line:String):Boolean {
        val root=JSONObject(line)
        if(root.has("error")) checkedResponse(root,SUB_ID)
        return (root.opt("event") in allowedEvents) && root.optJSONObject("data")!=null
    }

    fun snapshot(line:String):WatchRoster {
        val result=checkedResponse(JSONObject(line),LIST_ID)
        if(result.opt("type")!="session_snapshot") throw IOException("Unexpected snapshot result")
        val objectSnapshot=result.optJSONObject("snapshot") ?: throw IOException("Missing session snapshot")
        val agents=TuiAgents.parse(line).filter {it.isAgent && it.state=="live"}
        val ids=agents.mapNotNull {it.tuiLocation?.paneId}.toSet()
        if(ids.size>MAX_PANES || ids.any {!paneId.matches(it)}) throw WatchApiError("watch_pane_limit")
        // Read pane IDs from the validated agent placement only; detection events
        // invalidate snapshots when a previously plain shell starts an agent.
        if(objectSnapshot.optJSONArray("agents")==null) throw IOException("Missing agents")
        return WatchRoster(ids,agents)
    }

    fun boundedLine(input:InputStream,limit:Int):String {
        val bytes=ByteArrayOutputStream()
        while(true) {
            val byte=input.read()
            if(byte<0) throw EOFException()
            if(byte==10) break
            if(bytes.size()>=limit) throw IOException("Herdr message exceeds limit")
            bytes.write(byte)
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

internal class AgentWatchReducer {
    private data class State(val status:String?,val sequence:ULong?)
    private var previous:Map<String,State>?=null
    fun accept(roster:List<CoreSession>):List<AgentWatchAlert> {
        val old=previous
        val next=roster.associate {it.address to State(it.status,it.attentionRecency?.takeIf { seq->seq>0uL })}
        previous=next
        if(old==null) return emptyList()
        return roster.mapNotNull { row ->
            val current=next.getValue(row.address)
            val last=old[row.address]
            if(last!=null && (last==current || last.status==current.status && current.sequence==null)) return@mapNotNull null
            val status=when(current.status) {
                "done"->AgentWatchStatus.DONE
                "blocked"->AgentWatchStatus.BLOCKED
                else->return@mapNotNull null
            }
            AgentWatchAlert(row.address,row.name.filterNot {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}.take(120),status)
        }
    }
}
