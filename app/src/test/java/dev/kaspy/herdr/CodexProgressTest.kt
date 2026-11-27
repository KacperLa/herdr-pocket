package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexProgressTest {
    private fun event(type:String,phase:String,text:String)=JSONObject()
        .put("type",type).put("phase",phase).put("text",text)
    private fun turn(id:String,vararg items:JSONObject)=LiveCodexHistory.Turn(id,"interrupted",JSONArray(items.toList()))

    @Test fun keepsOnlyExplicitCommentaryFromNewestSavedTurn() {
        val latest=turn("new",event("agentMessage","commentary","Checking tests"),
            event("commandExecution","commentary","private command output"),
            event("userMessage","commentary","private prompt"),
            event("reasoning","commentary","private reasoning"),
            event("agentMessage","final_answer","Final reply"),
            event("agentMessage","partial_answer","Partial answer"),
            event("agentMessage","","Missing phase"),
            event("agentMessage","commentary","Checking tests"),
            event("agentMessage","commentary","Done checking"))
        val older=turn("old",event("agentMessage","commentary","Prior turn"))
        assertEquals(listOf("Checking tests","Done checking"),
            CodexProgress.from(LiveCodexHistory.Page(listOf(latest,older),true)).messages)
    }

    @Test fun noFallbackToOlderTurnAndBoundedText() {
        val older=turn("old",event("agentMessage","commentary","Prior turn"))
        assertEquals(emptyList<String>(),CodexProgress.from(LiveCodexHistory.Page(listOf(turn("new"),older),false)).messages)
        val truncated="a".repeat(1199)+"😀tail"
        val messages=(0..9).map {event("agentMessage","commentary","Step $it")}+
            event("agentMessage","commentary",truncated)
        val result=CodexProgress.from(LiveCodexHistory.Page(listOf(turn("new",*messages.toTypedArray())),false))
        assertEquals(8,result.messages.size)
        assertEquals("Step 3",result.messages.first())
        assertEquals("a".repeat(1199)+"…",result.messages.last())
    }
}
