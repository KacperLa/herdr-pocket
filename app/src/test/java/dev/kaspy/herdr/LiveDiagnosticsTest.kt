package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LiveDiagnosticsTest {
    @Test fun reportKeepsNumericAndAllowlistedFieldsNotProviderTextOrSecrets() {
        val raw="""{
            "choices":[{"finish_reason":"length","message":{
                "content":"<think>private terminal text",
                "reasoning_content":"Bearer fake-key-do-not-log"
            }}],
            "usage":{"prompt_tokens":321,"completion_tokens":1536,"completion_tokens_details":{"reasoning_tokens":1500}},
            "url":"https://private-host.example",
            "error":"raw error secret"
        }"""
        val clock=LiveDiagnostics {1}
        val stats=LiveDiagnostics.summarize(raw)
        clock.extraction(LiveDiagnostics.Origin.LIVE_CALL,LiveDiagnostics.Stage.READINESS,true,200,raw.length,237,
            stats,LiveExtractionClient.Problem.OUTPUT_LIMIT)
        val text=clock.text()
        for(field in listOf("origin=live_call","stage=readiness","thinking_off_requested=true",
            "http=200","finish=length","think_markup=true","reasoning_tokens=1500","completion_tokens=1536",
            "problem=output_limit","content_chars=","reasoning_chars=")) assertTrue(field,text.contains(field))
        for(privateValue in listOf("Bearer","fake-key-do-not-log","private terminal text",
            "private-host.example","raw error secret","<think>")) assertFalse(privateValue,text.contains(privateValue))
    }

    @Test fun unknownRepliesAndStatesDoNotLeakLabelsAndBufferIsBounded() {
        var now=0L
        val trace=LiveDiagnostics {now++}
        trace.call(LiveVoiceProfile.FOSS_LIVE,true)
        repeat(110) {trace.event(LiveDiagnostics.Event.CALL_READY)}
        val stats=LiveDiagnostics.summarize(
            """{"choices":[{"finish_reason":"echo-private-value","message":{"content":"text"}}]}""")
        trace.extraction(LiveDiagnostics.Origin.SETTINGS_TEST,LiveDiagnostics.Stage.READINESS,false,200,25,0,stats,null)
        trace.preflight(LiveDiagnostics.agentState("private custom agent"),LiveTurnExtraction.Kind.UNKNOWN,false,null)
        val text=trace.text()
        assertEquals(97,text.lineSequence().count())
        assertFalse(text.contains("call_start"))
        assertFalse(text.contains("echo-private-value"))
        assertFalse(text.contains("private custom agent"))
        assertTrue(text.contains("finish=other"))
        assertTrue(text.contains("herdr=unknown classification=unknown"))
    }
}
