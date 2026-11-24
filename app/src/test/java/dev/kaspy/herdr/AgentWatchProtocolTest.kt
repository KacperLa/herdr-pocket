package dev.kaspy.herdr

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class AgentWatchProtocolTest {
    @Test fun scopedStatusAndGlobalLifecycleSubscriptionsOnly() {
        val request=JSONObject(AgentWatchProtocol.subscribe(setOf("w:p2","w:p1")))
        assertEquals("events.subscribe",request.getString("method"))
        val entries=request.getJSONObject("params").getJSONArray("subscriptions")
        val values=(0 until entries.length()).map(entries::getJSONObject)
        assertTrue(values.any {it.getString("type")=="pane.agent_detected" && !it.has("pane_id")})
        assertEquals(listOf("w:p1","w:p2"),values.filter {it.getString("type")=="pane.agent_status_changed"}.map {it.getString("pane_id")})
    }

    @Test fun loopbackWireHandlesAckInvalidationAndLostHistory() {
        val input=PipedInputStream()
        PipedOutputStream(input).use { peer ->
            peer.write(("""{"id":"pocket-watch-subscribe","result":{"type":"subscription_started"}}
                |{"event":"pane.agent_detected","data":{"pane_id":"w:p2"}}
                |{"event":"pane.agent_status_changed","data":{"pane_id":"w:p1","agent_status":"blocked"}}
                |{"id":"pocket-watch-subscribe","error":{"code":"events_lost"}}
                |""".trimMargin()).toByteArray())
        }
        AgentWatchProtocol.acknowledged(AgentWatchProtocol.boundedLine(input,512))
        assertTrue(AgentWatchProtocol.invalidates(AgentWatchProtocol.boundedLine(input,512)))
        assertTrue(AgentWatchProtocol.invalidates(AgentWatchProtocol.boundedLine(input,512)))
        assertEquals("events_lost",assertThrows(WatchApiError::class.java) {
            AgentWatchProtocol.invalidates(AgentWatchProtocol.boundedLine(input,512))
        }.code)
    }

    @Test fun rejectsLeadingRemoteOrOversizedSocketAndInvalidWire() {
        fun discovery(path:String)=JSONObject().put("running",true).put("session","office").put("socket",path).toString()
        assertEquals("/tmp/herdr office.sock",AgentWatchProtocol.socket(discovery("/tmp/herdr office.sock"),"office"))
        for (bad in listOf("../socket","ssh:host", "/tmp/../sock","/tmp/\nsock","/${"x".repeat(107)}")) {
            assertThrows(IOException::class.java) {AgentWatchProtocol.socket(discovery(bad),"office")}
        }
        assertThrows(IOException::class.java) {AgentWatchProtocol.socket(discovery("/tmp/api.sock"),null)}
        assertThrows(IOException::class.java) {
            AgentWatchProtocol.boundedLine(ByteArrayInputStream("abc\n".toByteArray()),2)
        }
        assertThrows(IOException::class.java) {
            AgentWatchProtocol.boundedLine(ByteArrayInputStream(byteArrayOf((-1).toByte(),10)),2)
        }
    }

    @Test fun acceptsCanonicalDefaultAndOnlyRecognizesPositiveApiIncompatibility() {
        val path="/tmp/herdr.sock"
        val response=JSONObject().put("running",true).put("session",JSONObject.NULL).put("socket",path).toString()
        assertEquals(path,AgentWatchProtocol.socket(response,"default"))
        assertEquals(path,AgentWatchProtocol.socket(response,null))
        assertThrows(IOException::class.java) {AgentWatchProtocol.socket(response,"Default")}
        fun unsupported(message:String):Boolean {
            val error=JSONObject().put("code","invalid_request").put("message",message)
            val envelope=JSONObject().put("id",AgentWatchProtocol.SUB_ID).put("error",error)
            return assertThrows(WatchApiError::class.java) {AgentWatchProtocol.acknowledged(envelope.toString())}.unsupported
        }
        assertTrue(unsupported("invalid request: unknown variant `events.subscribe`, expected one of …"))
        assertFalse(unsupported("unknown variant `unrelated`"))
        assertFalse(unsupported("bad pane ID"))
    }

    @Test fun initialSnapshotSilentThenScopeChangesAndAuthoritativeTransitionsDedup() {
        fun snapshot(vararg states:Triple<String,String,Int>):WatchRoster {
            val rows=states.joinToString(",") { (id,status,seq) ->
                """{"terminal_id":"term_$id","pane_id":"w:p$id","tab_id":"w:t","workspace_id":"w",
                   "name":"Agent $id","agent":"codex","agent_status":"$status","state_change_seq":$seq}"""
            }
            return AgentWatchProtocol.snapshot("""{"id":"pocket-watch-snapshot","result":{"type":"session_snapshot","snapshot":{"agents":[$rows]}}}""")
        }
        val reducer=AgentWatchReducer()
        val a=Triple("a","done",1)
        assertTrue(reducer.accept(snapshot(a).agents).isEmpty())
        val b=Triple("b","blocked",2)
        val next=snapshot(a,b)
        assertEquals(setOf("w:pa","w:pb"),next.panes)
        assertEquals(listOf(AgentWatchAlert("term_b","Agent b",AgentWatchStatus.BLOCKED)),reducer.accept(next.agents))
        assertTrue(reducer.accept(next.agents).isEmpty())
        assertTrue(reducer.accept(snapshot(Triple("a","working",3),b).agents).isEmpty())
        assertEquals(listOf(AgentWatchAlert("term_a","Agent a",AgentWatchStatus.DONE)),
            reducer.accept(snapshot(a.copy(third=4),b).agents))
    }
}
