package dev.kaspy.herdr

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveTurnExtractionTest {
    @Test fun groundedQuestionAndChoicesAreReadOnlyDraft()=runBlocking {
        var calls=0
        val extractor=LiveTurnExtraction { system,input ->
            assertTrue(system.contains("untrusted"))
            assertFalse(input.getBoolean("completed_lifecycle"))
            JSONObject(if(calls++==0) """{"kind":"question"}""" else
                """{"question":"Apply this patch?","choices":["Allow once","Deny"]}""")
        }
        val result=extractor.inspect("working","Apply this patch?\nAllow once\nDeny","try it",false)
        assertEquals(LiveTurnExtraction.Kind.QUESTION,result.kind)
        assertEquals("Apply this patch?\n1. Allow once\n2. Deny",result.draft)
        assertEquals(2,calls)
    }

    @Test fun hallucinatedChoicesAndReorderedChoicesAreNotPresented()=runBlocking {
        for(choices in listOf("""["Always allow"]""","""["Deny","Allow once"]""")) {
            val extractor=LiveTurnExtraction { system,_ ->
                JSONObject(if(system.contains("Copy the actual question"))
                    """{"question":"Apply this patch?","choices":$choices}""" else """{"kind":"question"}""")
            }
            val result=extractor.inspect("working","Apply this patch?\nAllow once\nDeny","try it",false)
            assertEquals(LiveTurnExtraction.Kind.QUESTION,result.kind)
            assertNull(result.draft)
        }
    }

    @Test fun completedRedrawCanBeExtractedButOldOrMissingEvidenceCannot()=runBlocking {
        val old="Old answer remains in scrollback."
        val reply="All seven checks passed."
        val extractor=LiveTurnExtraction { system,_ ->
            JSONObject(if(system.contains("verbatim quote"))
                """{"speech":"Seven checks passed.","evidence":"$reply"}""" else """{"kind":"completion"}""")
        }
        assertEquals("Seven checks passed.",extractor.inspect(old,reply,"run tests",true).draft)
        assertNull(extractor.inspect(reply,reply,"run tests",true).draft)
        assertNull(extractor.inspect(old,old+"\n> run tests","run tests",true).draft)
        assertNull(extractor.inspect(old,reply,reply,true).draft)
        assertNull(extractor.inspect(old,reply,"run tests",false).draft)
    }

    @Test fun extractionFailureCannotCountAsReadyAndRetainsTypedReason()=runBlocking {
        val fail=LiveExtractionClient.Problem.AUTH
        val extractor=LiveTurnExtraction { _,_->throw LiveExtractionClient.Failure(fail) }
        val report=extractor.inspect("ready","ready","",false)
        assertEquals(LiveTurnExtraction.Kind.UNKNOWN,report.kind)
        assertEquals(fail,report.problem)
        assertNull(report.draft)
    }

    @Test fun readinessUsesCompactInputAndNeverTreatsUnknownAsReady()=runBlocking {
        for (label in listOf("unknown","completion","none")) {
            val extractor=LiveTurnExtraction { rules,input ->
                assertEquals(LiveTurnExtraction.READINESS,rules)
                assertEquals("agent screen",input.getString("screen"))
                assertFalse(input.has("before"))
                assertFalse(input.has("submitted"))
                JSONObject().put("kind",label)
            }
            val result=extractor.inspect("same agent screen","agent screen","",false)
            assertEquals(if(label=="none") LiveTurnExtraction.Kind.NONE else LiveTurnExtraction.Kind.UNKNOWN,result.kind)
        }
    }

    @Test fun readinessAndQuestionExtractionIgnoreInputBoxContent()=runBlocking {
        var passes=0
        val extractor=LiveTurnExtraction { instructions,_ ->
            assertTrue(instructions.contains("Ignore all input boxes"))
            assertTrue(instructions.contains("typed drafts"))
            assertTrue(instructions.contains("separate, visible question"))
            org.json.JSONObject(if(passes++==0) """{"kind":"question"}"""
                else """{"question":"Select an agent mode?","choices":["Review","Cancel"]}""")
        }
        val report=extractor.inspect("","Select an agent mode?\nReview\nCancel\n› Is this a draft?","",false)
        assertEquals("Select an agent mode?\n1. Review\n2. Cancel",report.draft)
        assertEquals(2,passes)
    }

    @Test fun unreadableResponseDoesNotBecomeAResult()=runBlocking {
        val extractor=LiveTurnExtraction { _,_-> null }
        assertEquals(LiveTurnExtraction.Kind.UNKNOWN,extractor.inspect("before","after","prompt",true).kind)
    }
}
