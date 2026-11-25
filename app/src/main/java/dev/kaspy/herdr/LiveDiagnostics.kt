package dev.kaspy.herdr

import java.util.Locale
import org.json.JSONObject

/**
 * Copyable, process-local diagnostics. Callers cannot add free-form strings:
 * the buffer only receives fixed enum values, flags and bounded counts.
 */
internal class LiveDiagnostics(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    enum class Event {
        SETTINGS_TEST_START, SETTINGS_TEST_PASSED, SETTINGS_TEST_FAILED, KEY_UNLOCK_FAILED,
        CALL_START, CALL_READY, CALL_END, LIVE_ERROR, HERDR_AGENT_MISSING, HERDR_STATUS_UNAVAILABLE,
        DELEGATION, DELEGATION_BUSY, HERDR_CAPTURE_UNAVAILABLE, PREFLIGHT,
        PROMPT_SKIPPED, PROMPT_INVALID, PROMPT_STALE, PROMPT_RECEIPT_UNKNOWN, PROMPT_SENT,
        OUTCOME_WITH_DRAFT, OUTCOME_WITHOUT_DRAFT, RESULT_SHARED, RESULT_NOT_SHARED,
        HERDR_TIMEOUT, HERDR_OPERATION_FAILED, CODEX_SESSION_MISSING, CODEX_HISTORY_UNAVAILABLE,
        CODEX_HISTORY_READY, CODEX_REPLY_VERIFIED, CODEX_REPLY_UNVERIFIED,
    }
    enum class Origin { SETTINGS_TEST, LIVE_CALL }
    enum class Stage { READINESS, DETECT, QUESTION, COMPLETION, OTHER }
    enum class AgentState { IDLE, DONE, WORKING, BLOCKED, UNKNOWN }
    enum class Finish { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, MISSING, OTHER }
    enum class Shape { INVALID_ENVELOPE, NO_CHOICE, MULTIPLE_CHOICES, MISSING_MESSAGE, CHOICE }
    enum class Content { MISSING, EMPTY, TEXT, NON_TEXT }
    data class ReplyStats(
        val shape:Shape,val finish:Finish=Finish.MISSING,val content:Content=Content.MISSING,
        val contentChars:Int?=null,val reasoningChars:Int?=null,val thinkMarkup:Boolean=false,
        val promptTokens:Int?=null,val completionTokens:Int?=null,val reasoningTokens:Int?=null,
    )
    private val started=clock()
    private val entries=ArrayDeque<String>()

    @Synchronized fun event(event:Event,problem:LiveExtractionClient.Problem?=null) =
        append(event.label(),problem?.let {"problem="+it.label()})

    @Synchronized fun call(profile:LiveVoiceProfile,disableThinking:Boolean,ignoreBottomLines:Int=0)=
        append(Event.CALL_START.label(),"profile="+profile.label(),"thinking_off_requested=$disableThinking",
            "ignore_bottom_lines="+LiveExtractionWindow.normalize(ignoreBottomLines))

    @Synchronized fun preflight(
        state:AgentState,kind:LiveTurnExtraction.Kind,menu:Boolean,problem:LiveExtractionClient.Problem?,
        knownComposer:Boolean=false, codexHistory:Boolean=false,
    )=append(Event.PREFLIGHT.label(),"herdr="+state.label(),"classification="+kind.label(),"menu=$menu",
            "source="+when {codexHistory -> "herdr";knownComposer -> "codex_idle_composer";else -> "extractor"},
            problem?.let {"problem="+it.label()})

    @Synchronized fun extraction(
        origin:Origin,stage:Stage,disableThinking:Boolean,status:Int?,bytes:Int?,durationMs:Long,
        reply:ReplyStats?,problem:LiveExtractionClient.Problem?,
    )=append("extraction","origin="+origin.label(),"stage="+stage.label(),"thinking_off_requested=$disableThinking",
        field("http",status?.takeIf {it in 100..599}),field("response_bytes",bytes),
        "duration_ms="+durationMs.coerceIn(0,3_600_000),
        reply?.let {"shape="+it.shape.label()},reply?.let {"finish="+it.finish.label()},
        reply?.let {"content="+it.content.label()},field("content_chars",reply?.contentChars),
        field("reasoning_chars",reply?.reasoningChars),reply?.let {"think_markup="+it.thinkMarkup},
        field("prompt_tokens",reply?.promptTokens),field("completion_tokens",reply?.completionTokens),
        field("reasoning_tokens",reply?.reasoningTokens),problem?.let {"problem="+it.label()})

    @Synchronized fun text():String=if(entries.isEmpty()) "" else
        entries.joinToString("\n",prefix="Pocket Live Voice diagnostics v1\n")

    private fun append(vararg parts:String?) {
        if(entries.size==96) entries.removeFirst()
        entries.addLast("+"+(clock()-started).coerceAtLeast(0)+"ms "+parts.filterNotNull().joinToString(" "))
    }

    private fun field(name:String,value:Int?)=value?.let {"$name="+it.coerceIn(0,10_000_000)}
    private fun Enum<*>.label()=name.lowercase(Locale.ROOT)

    companion object {
        fun agentState(value:String)=when(value) {
            "idle" -> AgentState.IDLE
            "done" -> AgentState.DONE
            "working" -> AgentState.WORKING
            "blocked" -> AgentState.BLOCKED
            else -> AgentState.UNKNOWN
        }

        fun summarize(raw:String):ReplyStats { return try {
            val envelope=JSONObject(raw)
            val choices=envelope.optJSONArray("choices") ?: return ReplyStats(Shape.INVALID_ENVELOPE)
            if(choices.length()==0) return ReplyStats(Shape.NO_CHOICE)
            if(choices.length()!=1) return ReplyStats(Shape.MULTIPLE_CHOICES)
            val choice=choices.optJSONObject(0) ?: return ReplyStats(Shape.INVALID_ENVELOPE)
            val finish=when(choice.opt("finish_reason")) {
                "stop" -> Finish.STOP
                "length" -> Finish.LENGTH
                "tool_calls","function_call" -> Finish.TOOL_CALLS
                "content_filter" -> Finish.CONTENT_FILTER
                null,JSONObject.NULL -> Finish.MISSING
                else -> Finish.OTHER
            }
            val message=choice.optJSONObject("message") ?: return ReplyStats(Shape.MISSING_MESSAGE,finish)
            val content=message.opt("content")
            val plain=content as? String
            val contentKind=when {
                plain!=null -> if(plain.isBlank()) Content.EMPTY else Content.TEXT
                content==null || content==JSONObject.NULL -> Content.MISSING
                else -> Content.NON_TEXT
            }
            val reasoning=(message.opt("reasoning_content") as? String) ?: (message.opt("reasoning") as? String)
            val usage=envelope.optJSONObject("usage")
            ReplyStats(
                Shape.CHOICE,finish,contentKind,plain?.length,reasoning?.length,
                plain?.let {"<think>" in it || "</think>" in it}==true,
                count(usage,"prompt_tokens"),count(usage,"completion_tokens"),
                count(usage?.optJSONObject("completion_tokens_details"),"reasoning_tokens")
                    ?: count(usage,"reasoning_tokens"),
            )
        } catch(_:org.json.JSONException) {ReplyStats(Shape.INVALID_ENVELOPE)} }

        private fun count(obj:JSONObject?,key:String):Int? =
            (obj?.opt(key) as? Number)?.toLong()?.takeIf {it in 0..10_000_000}?.toInt()
    }
}
