package dev.kaspy.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveAgentBridgeTest {
    private val selected=TuiLocation("term_chosen","w1:p1","w1:t1","w1")
    private val fields=""""terminal_id":"term_chosen","pane_id":"w1:p1","tab_id":"w1:t1","workspace_id":"w1","agent":"codex""""
    private val original="old answer\nprevious context\nready"
    private val completed=original+"\nCheck things\nThe answer is seven."
    private fun status(state:String,seq:Int,type:String="agent_info",extra:String="")=
        LiveAgentHerdr.Reply("""{"result":{"type":"$type","agent":{$fields,"agent_status":"$state","state_change_seq":$seq$extra}}}""",true)
    private fun text(screen:String)=LiveAgentHerdr.Reply(screen,true)

    private fun fixture(vararg replies:LiveAgentHerdr.Reply):Pair<LiveAgentHerdr.Route,MutableList<String>> {
        val captured=mutableListOf<String>()
        val queue=ArrayDeque(replies.toList())
        return LiveAgentHerdr.Route(selected,"chosen-session") {
            captured.add(it)
            queue.removeFirst()
        } to captured
    }

    @Test fun verifiesExactPaneBeforeOnePromptAndSharesOnlyCandidateFromNewSettledWork() = runBlocking {
        val idle=status("idle",10)
        val working=status("working",11)
        val done=status("done",12)
        val (route,commands)=fixture(idle, text(original), idle,
            idle,text(original),idle,status("idle",10,"agent_prompted"),
            working,
            done,text(original+"\nCheck things\nPartial"),done,
            done,text(completed),done,done,text(completed),done,done,text(completed),done,
            done,text(completed),done,done,text(completed),done)
        val identity=LiveAgentHerdr.status(idle,selected)!!.instance
        val before=route.ready(identity)!!
        assertTrue(route.submit(before,"Check things")==true)
        var time=0L
        val result=route.finish(before,"Check things",attempts=7,pause={time+=620},nowMs={time})
        assertEquals(4340L,time)
        assertEquals("The answer is seven.",result.review)
        assertEquals(1,commands.count { "agent prompt " in it })
        assertTrue(commands.all {"chosen-session" in it && "w1:p1" in it})
        assertTrue(commands.any {"--source visible --lines 30 --format text" in it})
        assertFalse(commands.any {"send-keys" in it})
    }

    @Test fun redrawAndMissingReceiptNeverTriggerRetriesOrStaleOutput() = runBlocking {
        val idle=status("idle",10)
        val identity=LiveAgentHerdr.status(idle,selected)!!.instance
        val (blocked,c1)=fixture(idle,text(original+"\nAllow once"),idle)
        assertNull(blocked.ready(identity))
        assertFalse(c1.any {"agent prompt" in it})
        val before=LiveAgentHerdr.Capture(LiveAgentHerdr.status(idle,selected)!!,original)
        val (wrong,c2)=fixture(idle,text(original+" changed"),idle)
        assertEquals(false,wrong.submit(before,"Check things"))
        assertFalse(c2.any {"agent prompt" in it})
        val (uncertain,c3)=fixture(idle,text(original),idle,LiveAgentHerdr.Reply("",false))
        assertNull(uncertain.submit(before,"Check things"))
        assertEquals(1,c3.count {"agent prompt" in it})
        assertNull(LiveAgentHerdr.freshExcerpt(original,"completely redrawn","Check things"))
        assertNull(LiveAgentHerdr.freshExcerpt(original,original,"Check things"))
    }

    @Test fun staleDoneIsNotNewWorkAndBlockedNeverSubmitsAKey() = runBlocking {
        val none=status("done",10)
        val before=LiveAgentHerdr.Capture(LiveAgentHerdr.status(none,selected)!!,original)
        val (noWork,c1)=fixture(none,none)
        assertNull(noWork.finish(before,"question",2,{}).review)
        val (blocked,c2)=fixture(status("working",11),status("blocked",12))
        val stop=blocked.finish(before,"question",2,{})
        assertTrue("waiting for input" in stop.message)
        assertNull(stop.review)
        assertFalse((c1+c2).any {"send-keys" in it || "agent prompt" in it})
        assertNull(LiveAgentHerdr.status(status("idle",10),selected.copy(terminalId="different")))
    }

    @Test fun settledBlockedCaptureExtractsAQuestionWithoutInput() = runBlocking {
        val idle=status("idle",10)
        val blocked=status("blocked",12)
        val screen="Apply the patch?\nAllow once\nDeny"
        val (route,commands)=fixture(status("working",11),
            blocked,text(screen),blocked,blocked,text(screen),blocked,text(screen),blocked)
        val capture=LiveAgentHerdr.Capture(LiveAgentHerdr.status(idle,selected)!!,original)
        val extraction=LiveTurnExtraction { rules,_ -> org.json.JSONObject(
            if(rules.contains("Copy the actual question")) """{"question":"Apply the patch?","choices":["Allow once","Deny"]}"""
            else """{"kind":"question"}""")
        }
        var time=0L
        val result=route.finish(capture,"run tests",attempts=3,pause={time+=2_000},nowMs={time},extraction=extraction)
        assertTrue(result.question)
        assertEquals("Apply the patch?\n1. Allow once\n2. Deny",result.review)
        assertTrue(commands.none { "agent prompt" in it || "send-keys" in it })
    }

    @Test fun codexScrolledEmptyComposerDoesNotClassifyOlderTroubleshootingAsPending()=runBlocking {
        val snapshot="""
            API authentication was accepted.
            If this still fails, send me the exact app error.

                           ↓ Back to bottom · esc

            › Ask Codex to do anything

            GPT-6-Astra medium · ~/example · Manage local models
            Earlier messages available.  enter/esc latest             ⚠ 2 warnings · f2 to
        """.trimIndent()
        val idle=LiveAgentHerdr.status(status("idle",10),selected)!!
        val extractor=LiveTurnExtraction { _,_->error("This recognized idle layout must not be sent for classification") }
        val capture=LiveAgentHerdr.Capture(idle,snapshot)
        assertTrue(LiveAgentHerdr.codexScrolledEmptyComposer(capture))
        assertEquals(LiveTurnExtraction.Kind.NONE,LiveAgentHerdr.preflight(capture,extractor).kind)
        val (route,commands)=fixture(status("idle",10),text(snapshot),status("idle",10),
            status("idle",10,"agent_prompted"))
        assertEquals(true,route.submit(capture,"Check this example"))
        assertEquals(1,commands.count {"agent prompt" in it})
        assertTrue(commands.none {"send-keys" in it})
    }

    @Test fun activeMenuBlockedAgentAndUnknownEditorStillUseExtractor()=runBlocking {
        val snapshot="↓ Back to bottom · esc\n› Ask Codex to do anything\n" +
            "GPT-6-Astra medium · ~/example · Project\nEarlier messages available. enter/esc latest"
        val idle=LiveAgentHerdr.status(status("idle",10),selected)!!
        val nearMatches=listOf(
            LiveAgentHerdr.Capture(idle,snapshot+"\nDo you want to proceed?\nAllow once"),
            LiveAgentHerdr.Capture(idle.copy(state="blocked"),snapshot),
            LiveAgentHerdr.Capture(idle,snapshot.replace("Ask Codex to do anything","My unfinished input")),
            LiveAgentHerdr.Capture(idle,snapshot.replace("Earlier messages available. enter/esc latest","")),
            LiveAgentHerdr.Capture(idle.copy(instance=idle.instance.copy(kind="claude")),snapshot),
        )
        for(capture in nearMatches) {
            var calls=0
            val extractor=LiveTurnExtraction { _,_->calls++; org.json.JSONObject("""{"kind":"unknown"}""") }
            assertFalse(LiveAgentHerdr.codexScrolledEmptyComposer(capture))
            assertEquals(LiveTurnExtraction.Kind.UNKNOWN,LiveAgentHerdr.preflight(capture,extractor).kind)
            assertEquals(1,calls)
        }
    }

    @Test fun outputsAreUtf8BoundedAndTranscriptMetadataIsNotACommand() {
        val b=LiveVoiceTranscript()
        b.add(LiveVoiceProtocol.Event.InputTranscript("one","Check",100,200))
        b.add(LiveVoiceProtocol.Event.InputTranscript("two"," the result.",200,400))
        b.add(LiveVoiceProtocol.Event.InputTranscript("two","duplicate",200,400))
        assertEquals("Check the result.",b.draft(450))
        assertEquals("",b.draft(460))
        assertEquals(348,LiveAgentHerdr.compactExcerpt("😀".repeat(200)).toByteArray().size)
        assertFalse(LiveAgentHerdr.validPrompt("hello\nenter"))
        assertFalse(LiveAgentHerdr.validPrompt("--help"))
        assertTrue(LiveAgentHerdr.validPrompt("What is in the report?"))
        assertFalse(LiveAgentHerdr.validShare("x\u001by"))
    }
}
