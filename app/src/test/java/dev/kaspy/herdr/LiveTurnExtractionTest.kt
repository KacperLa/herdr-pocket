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

    @Test fun unreadableResponseDoesNotBecomeAResult()=runBlocking {
        val extractor=LiveTurnExtraction { _,_-> null }
        assertEquals(LiveTurnExtraction.Kind.UNKNOWN,extractor.inspect("before","after","prompt",true).kind)
    }
}
