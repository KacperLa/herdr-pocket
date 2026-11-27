package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LiveCodexHistoryTest {
    private val id = "be368f85-ff58-4dc6-ad8f-071a690ae787"
    private val location = TuiLocation("term_example","w1:p1","w1:t1","w1")
    private val instance = LiveAgentHerdr.Instance(location,"codex", JSONArray(listOf("herdr:codex","codex","id",id)).toString())
    private val prompt = "What is 2 + 2?"
    private val old = turn("old")
    private val baseline = page(old)

    private fun user(text:String) = JSONObject().put("type","userMessage").put("content",JSONArray()
        .put(JSONObject().put("type","text").put("text",text)))
    private fun answer(text:String, phase:String="final_answer") =
        JSONObject().put("type","agentMessage").put("phase",phase).put("text",text)
    private fun turn(id:String, status:String="completed", vararg items:JSONObject) =
        LiveCodexHistory.Turn(id,status,JSONArray(items.toList()))
    private fun page(vararg turns:LiveCodexHistory.Turn) = LiveCodexHistory.Page(turns.toList(),false)

    @Test fun exactNativeBindingAndReadOnlyWire() {
        assertEquals(id,LiveCodexHistory.threadId(instance))
        assertNull(LiveCodexHistory.threadId(instance.copy(providerSession=instance.providerSession!!.replace("herdr:codex","custom:codex"))))
        assertNull(LiveCodexHistory.threadId(instance.copy(providerSession=null)))
        assertNull(LiveCodexHistory.threadId(instance.copy(kind="claude")))
        val calls = LiveCodexHistory.request(id).decodeToString().trim().lines().map(::JSONObject)
        assertEquals(listOf("initialize","initialized","thread/turns/list"),calls.map {it.getString("method")})
        assertEquals(id,calls.last().getJSONObject("params").getString("threadId"))
        assertTrue(LiveCodexHistory.command.contains("--listen stdio://"))
        assertFalse(LiveCodexHistory.command.contains(id))
    }

    @Test fun partialWireAndErrorNeverBecomeUsableBaseline() {
        val header = """{"id":1,"result":{}}""" + "\n"
        val valid = """{"id":2,"result":{"data":[],"nextCursor":null}}"""
        assertFalse(LiveCodexHistory.responseSeen(header + valid))
        assertTrue(LiveCodexHistory.responseSeen(header + valid + "\n"))
        assertEquals(page(),LiveCodexHistory.parse(header + valid + "\n"))
        assertNull(LiveCodexHistory.parse(header + """{"id":2,"error":{"code":-32601}}""" + "\n"))
        assertNull(LiveCodexHistory.parse(header + valid + "\n" + valid + "\n"))
    }

    @Test fun onlyNewCompletedMatchingTurnCanReadBackIncludingFinalQuestion() {
        val fresh = turn("new","completed",user(prompt),answer("let me check","commentary"),
            answer("Four. Want another example?"))
        assertEquals(LiveCodexHistory.Match.Completed("Four. Want another example?"),
            LiveCodexHistory.match(baseline,page(fresh,old),prompt))
        assertEquals(LiveCodexHistory.Match.Waiting,LiveCodexHistory.match(baseline,page(old),prompt))
        assertEquals(LiveCodexHistory.Match.Waiting,LiveCodexHistory.match(baseline,page(fresh.copy(status="interrupted"),old),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveCodexHistory.match(baseline,page(fresh),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveCodexHistory.match(baseline,page(fresh,turn("other"),old),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveCodexHistory.match(baseline,
            page(turn("new","completed",user(prompt),user(prompt),answer("wrong")),old),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveCodexHistory.match(baseline,
            page(turn("new","completed",user("other prompt"),answer("wrong")),old),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveCodexHistory.match(baseline,
            page(turn("new","completed",user(prompt),answer("not final","partial_answer")),old),prompt))
    }

    @Test fun progressIsBoundToOneNewTurnAndDoesNotConsumeFinal() {
        val feed=LiveCodexHistory.Progress(baseline,prompt)
        val working=turn("new","interrupted",user(prompt),answer("Checking", "commentary"),
            JSONObject().put("type","commandExecution").put("text","private"))
        assertNull(feed.next(page(old)))
        assertEquals("Checking",feed.next(page(working,old)))
        assertNull(feed.next(page(working,old)))
        val later=turn("new","inProgress",user(prompt),answer("Testing", "commentary"))
        assertEquals("Testing",feed.next(page(later,old)))
        assertNull(feed.next(page(working,old)))
        val final=turn("new","completed",user(prompt),answer("Testing","commentary"),answer("Four"))
        assertNull(feed.next(page(final,old)))
        assertEquals(LiveCodexHistory.Match.Completed("Four"),LiveCodexHistory.match(baseline,page(final,old),prompt))
    }

    @Test fun mismatchedOrReplacedTurnStopsProgress() {
        val valid=turn("new","inProgress",user(prompt),answer("Checking","commentary"))
        val feed=LiveCodexHistory.Progress(baseline,prompt)
        assertNull(feed.next(page(turn("new","inProgress",user("other"),answer("Not ours","commentary")),old)))
        assertNull(feed.next(page(valid,old)))
        val current=LiveCodexHistory.Progress(baseline,prompt)
        assertEquals("Checking",current.next(page(valid,old)))
        assertNull(current.next(page(valid.copy(id="replacement"),old)))
        assertNull(current.next(page(valid.copy(rawItems=JSONArray().put(user(prompt)).put(answer("Late","commentary"))),old)))
    }

    @Test fun progressFrameUsesPendingTaskContextNotResultSpeech() {
        val wire=JSONObject(LiveVoiceProtocol.progress("delegation-7","Checking","pocket-progress-7")!!)
        assertEquals("session.thinking.append",wire.getString("type"))
        assertEquals("delegation-7",wire.getString("delegation_id"))
        assertTrue(wire.getString("content").contains("task still running"))
        assertEquals("pocket-progress-7",wire.getString("event_id"))
        assertNull(LiveVoiceProtocol.progress("","Checking","pocket-progress-7"))
        assertNull(LiveVoiceProtocol.progress("delegation-7","a".repeat(2000),"pocket-progress-7"))
        assertNull(LiveVoiceProtocol.progress("delegation-7","\u001b","pocket-progress-7"))
        val final=JSONObject(LiveVoiceProtocol.readback(LiveVoiceProfile.FOSS_LIVE,"delegation-7","Done","result-7")!!)
        assertEquals("session.commentary.append",final.getString("type"))
    }

    @Test fun workingProgressAndCompletionTakeSeparatePaths() = runBlocking {
        fun reply(state:String,seq:Int)=LiveAgentHerdr.Reply("""{"result":{"type":"agent_info",
          "agent":{"terminal_id":"term_example","pane_id":"w1:p1","tab_id":"w1:t1","workspace_id":"w1",
          "agent":"codex","agent_status":"$state","state_change_seq":$seq,
          "agent_session":{"source":"herdr:codex","agent":"codex","kind":"id","value":"$id"}}}}""",true)
        val done=reply("done",12)
        val replies=ArrayDeque(listOf(reply("working",11),reply("working",11),done,done,done))
        val route=LiveAgentHerdr.Route(location,null) {replies.removeFirst()}
        val feed=LiveCodexHistory.Progress(baseline,prompt)
        val interim=page(turn("new","interrupted",user(prompt),answer("Checking","commentary")),old)
        val final=page(turn("new","completed",user(prompt),answer("Four")),old)
        val wire=mutableListOf<String>()
        val expected=LiveAgentHerdr.Status(instance,"idle",10u,null)
        val completed=route.finishStructured(expected,{LiveCodexHistory.match(baseline,final,prompt)},
            attempts=4,pause={},onWorking={
                feed.next(interim)?.let {text ->
                    wire.add(JSONObject(LiveVoiceProtocol.progress("task-1",text,"pocket-progress-1")!!).getString("type"))
                }
            })
        assertEquals(listOf("session.thinking.append"),wire)
        assertEquals("Four",completed?.draft)
        assertTrue(replies.isEmpty())
    }

    @Test fun waitsForSettledHerdrThenRetriesStorageAndDoesNotReadBlockedPane() = runBlocking {
        fun reply(state:String,seq:Int)=LiveAgentHerdr.Reply("""{"result":{"type":"agent_info",
          "agent":{"terminal_id":"term_example","pane_id":"w1:p1","tab_id":"w1:t1","workspace_id":"w1",
          "agent":"codex","agent_status":"$state","state_change_seq":$seq,
          "agent_session":{"source":"herdr:codex","agent":"codex","kind":"id","value":"$id"}}}}""",true)
        val before=LiveAgentHerdr.Status(instance,"idle",10u,null)
        val done=reply("done",12)
        val replies=ArrayDeque(listOf(reply("working",11),done,done,done,done,done))
        val route=LiveAgentHerdr.Route(location,null) {command ->
            assertTrue("agent get" in command); replies.removeFirst()
        }
        var readCount=0
        val fresh=turn("new","completed",user(prompt),answer("Four"))
        val stored=route.finishCodex(before,prompt,baseline,{
            page(if(++readCount==1) fresh.copy(status="interrupted") else fresh,old)
        },attempts=4,pause={})
        assertEquals("Four",stored?.draft)
        assertEquals(2,readCount)
        val blocked=LiveAgentHerdr.Route(location,null) {reply("blocked",11)}
        assertNull(blocked.finishCodex(before,prompt,baseline,{error("Blocked must not read")},attempts=1,pause={}))
    }
}
