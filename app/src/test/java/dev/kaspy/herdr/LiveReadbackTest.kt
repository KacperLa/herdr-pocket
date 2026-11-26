package dev.kaspy.herdr

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveReadbackTest {
    private val foss=LiveVoiceProfile.FOSS_LIVE
    private val gpt=LiveVoiceProfile.GPT_LIVE

    @Test fun fossUsesScalarAndUtf8LimitsWithoutChangingUnicodeOrOrder() {
        val prose="First item. Next résumé and 路線. ".repeat(200).trim()
        assertTrue(prose.toByteArray().size>350)
        val chunks=requireNotNull(LiveReadback.plan(foss,prose))
        assertTrue(chunks.size>1)
        assertTrue(chunks.all(LiveReadback::validFossAppend))
        assertEquals(prose,chunks.joinToString(""))
        assertEquals(1,LiveReadback.plan(foss,"🙂".repeat(2000))?.size)
        assertEquals(2,LiveReadback.plan(foss,"🙂".repeat(3999))?.size)
        assertNull(LiveReadback.plan(foss,"🙂".repeat(4000))) // Two frames + required separator.
        assertNull(LiveReadback.plan(foss,"a".repeat(16001)))
        assertNull(LiveReadback.plan(foss,"broken \ud800"))
    }

    @Test fun fossDoesNotTurnFencedCodeIntoSpokenProseAcrossFrames() {
        val fenced="```\n"+ "code ".repeat(130) +"\n```"
        val reply="word ".repeat(370)+fenced+" after."+" more".repeat(350)
        val parts=requireNotNull(LiveReadback.plan(foss,reply))
        assertEquals(reply,parts.joinToString(""))
        assertTrue(parts.any {fenced in it})
        assertNull(LiveReadback.plan(foss,"prose ```"+ "c".repeat(2100) +"``` more"))
    }

    @Test fun gptLeavesTokenEnforcementToSingleProviderAppend() {
        val text="Unclipped. ".repeat(300).trim()
        assertEquals(listOf(text),LiveReadback.plan(gpt,text))
        val event=JSONObject(requireNotNull(LiveVoiceProtocol.readback(gpt,"id",text,"evt")))
        assertEquals(text,event.getString("content"))
        assertEquals("evt",event.getString("event_id"))
        assertEquals(LiveVoiceProtocol.Event.CommentaryAck("evt"),LiveVoiceProtocol.parse(
            """{"type":"session.commentary.appended","client_event_id":"evt"}"""))
        assertEquals(LiveVoiceProtocol.Event.CommandError("evt"),LiveVoiceProtocol.parse(
            """{"type":"error","error":{"client_event_id":"evt","code":"invalid_content"}}"""))
    }

    @Test fun submitRequiresFreshRouteForEachPartAndNeverResendsUnknownReceipt() = runBlocking {
        val submitted=mutableListOf<String>()
        val parts=listOf("first","second","third")
        val interrupted=LiveReadback.submit(parts,{submitted.isEmpty()}) { part ->
            submitted += part
            LiveReadback.Delivery.ACCEPTED
        }
        assertEquals(listOf("first"),submitted)
        assertEquals(LiveReadback.Outcome(LiveReadback.Delivery.INTERRUPTED,1,3),interrupted)
        submitted.clear()
        val unknown=LiveReadback.submit(parts,{true}) { part ->
            submitted += part
            if(part=="first") LiveReadback.Delivery.ACCEPTED else LiveReadback.Delivery.UNCONFIRMED
        }
        assertEquals(listOf("first","second"),submitted)
        assertEquals(LiveReadback.Outcome(LiveReadback.Delivery.UNCONFIRMED,1,3),unknown)
        submitted.clear()
        assertEquals(LiveReadback.Outcome(LiveReadback.Delivery.ACCEPTED,3,3),
            LiveReadback.submit(parts,{true}) {part->submitted+=part;LiveReadback.Delivery.ACCEPTED})
        assertEquals(parts,submitted)
    }

    @Test fun optInStillRequiresFreshCallerInputAndVerifiedStoredReply() {
        val prompt="Check the tests"
        assertFalse(LiveReviewPolicy.canSendPrompt(true,true,prompt))
        assertFalse(LiveReviewPolicy.canSendPrompt(false,false,prompt))
        assertTrue(LiveReviewPolicy.canSendPrompt(false,true,prompt))
        for(answer in listOf("Yes","approve this","2","Go\nnow","-h")) {
            assertFalse(answer,LiveReviewPolicy.canSendPrompt(false,true,answer))
        }
        val reply="Verified text. ".repeat(150).trim()
        val replyKind=PocketLiveReview.Kind.REPLY
        assertTrue(LiveReviewPolicy.canReadBack(false,replyKind,true,reply,foss))
        assertFalse(LiveReviewPolicy.canReadBack(true,replyKind,true,reply,foss))
        assertFalse(LiveReviewPolicy.canReadBack(false,replyKind,false,reply,foss))
        assertFalse(LiveReviewPolicy.canReadBack(false,PocketLiveReview.Kind.QUESTION,true,reply,foss))
    }

    @Test fun fossAudioCommitIsFreshOneUseAndCannotBeReplayed() {
        val event=LiveVoiceProtocol.parse(
            """{"type":"session.input_transcript.delta","event_id":"evt001","delta":"[Audio input — native understanding; no transcript]","native_audio":true,"end_ms":200}""")
        assertEquals(LiveVoiceProtocol.Event.InputCommit("evt001",200),event)
        val commits=LiveVoiceCallerInput()
        commits.record(event as LiveVoiceProtocol.Event.InputCommit)
        assertFalse(commits.claimAt(null))
        assertFalse(commits.claimAt(199))
        assertFalse(commits.claimAt(45_201))
        assertTrue(commits.claimAt(201))
        assertFalse(commits.claimAt(202))
        commits.record(event)
        assertFalse(commits.claimAt(203))
    }
}
