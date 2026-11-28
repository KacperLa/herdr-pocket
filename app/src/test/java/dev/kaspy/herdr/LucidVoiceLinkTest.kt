package dev.kaspy.herdr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LucidVoiceLinkTest {
    private fun frame(type:String,vararg fields:Pair<String,Any>)=
        JSONObject().put("type",type).also {row -> fields.forEach {(key,value)->row.put(key,value)}}

    @Test fun onlyTheCorrelatedHostedTurnCanBeReadBack() = runBlocking {
        val link=LucidVoiceLink()
        var id=""
        val notices=mutableListOf<String>()
        link.bind("one") {request,_ -> id=request;true}
        val result=async(start=CoroutineStart.UNDISPATCHED) {link.submit("Review this",notices::add)}
        link.event(frame("ack","id" to id,"ok" to true))
        link.event(frame("user","id" to id,"text" to "Review this"))
        link.event(frame("progress","title" to "Checking files"))
        link.event(frame("permission"))
        link.event(frame("text","text" to "Verified "))
        link.event(frame("text","text" to "reply"))
        link.event(frame("done","reason" to "end_turn"))
        val completed=checkNotNull(result.await())
        assertEquals("Verified reply",completed.text)
        assertEquals(listOf("Checking files","The agent is waiting for approval in Lucid chat."),notices)
        assertTrue(link.isCurrent(completed))
        link.event(frame("user","text" to "Unrelated TUI turn"))
        assertFalse(link.isCurrent(completed))
    }

    @Test fun overlapAndDisconnectNeverShareUnverifiedText() = runBlocking {
        val link=LucidVoiceLink()
        var id=""
        fun bind()=link.bind("instance"){request,_ -> id=request;true}
        bind()
        val first=async(start=CoroutineStart.UNDISPATCHED) {link.submit("First") {}}
        link.event(frame("ack","id" to id,"ok" to true))
        link.event(frame("user","id" to "other","text" to "First"))
        link.event(frame("text","text" to "Not ours"))
        link.event(frame("done","reason" to "end_turn"))
        assertNull(first.await())
        bind()
        val second=async(start=CoroutineStart.UNDISPATCHED) {link.submit("Second") {}}
        link.unbind()
        assertNull(second.await())
    }

    @Test fun dictationPreservesTheDraftAndDoesNotSendBlankSpeech() {
        assertEquals("typed first after",lucidDictationDraft("typed first","  after "))
        assertEquals("typed first after",lucidDictationDraft("typed first ","after"))
        assertNull(lucidDictationDraft("typed","  "))
    }
}
