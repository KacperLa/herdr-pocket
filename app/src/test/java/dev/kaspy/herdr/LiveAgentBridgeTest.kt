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
            working,done,text(completed),done,done,text(completed),done)
        val identity=LiveAgentHerdr.status(idle,selected)!!.instance
        val before=route.ready(identity)!!
        assertTrue(route.submit(before,"Check things")==true)
        val result=route.finish(before,"Check things",attempts=3,pause={})
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
