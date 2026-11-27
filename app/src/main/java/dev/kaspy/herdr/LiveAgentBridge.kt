package dev.kaspy.herdr

import kotlinx.coroutines.delay
import org.json.JSONObject

/** One selected local Herdr agent. No roster routing, key input or automatic permission handling. */
internal object LiveAgentHerdr {
    data class Instance(val location:TuiLocation,val kind:String,val providerSession:String?,val foregroundCwd:String?=null)
    data class Status(val instance:Instance,val state:String,val sequence:ULong,val completion:ULong?) {
        val ready get()=state=="idle" || state=="done"
    }
    data class Capture(val status:Status,val screen:String)
    data class Reply(val stdout:String,val ok:Boolean)
    data class Result(val message:String,val review:String?=null,val capture:Capture?=null,val question:Boolean=false,val evidence:String?=null)

    private fun pane(location:TuiLocation)=TuiAgents.quote(TuiLocation.requireSafe(location.paneId))
    fun get(location:TuiLocation,session:String?)=TuiAgents.resolve("agent get "+pane(location),session)
    fun read(location:TuiLocation,session:String?)=
        TuiAgents.resolve("agent read "+pane(location)+" --source visible --lines 30 --format text",session)
    fun validPrompt(text:String)=text.length in 1..4000 && text.trim()==text &&
        !text.startsWith("-") && text.none {it.isISOControl() || it=='\u2028' || it=='\u2029'}
    fun prompt(location:TuiLocation,session:String?,text:String,expectNobsSession:String?=null):String {
        require(validPrompt(text))
        require(expectNobsSession==null || LiveNobsHistory.validSessionId(expectNobsSession))
        val guard=expectNobsSession?.let { " "+TuiAgents.quote("--expect-session="+it) }.orEmpty()
        return TuiAgents.resolve("agent prompt "+pane(location)+" "+TuiAgents.quote(text)+guard,session)
    }

    fun status(reply:Reply,location:TuiLocation,type:String="agent_info"):Status? = runCatching {
        if(!reply.ok) return null
        val result=JSONObject(reply.stdout).getJSONObject("result")
        if(result.opt("type")!=type) return null
        val agent=result.getJSONObject("agent")
        if(TuiLocation.from(agent,location.terminalId)!=location || agent.optBoolean("launch_pending")) return null
        val kind=(agent.opt("agent") as? String)?.takeIf {it.isNotBlank() && it.length<=80} ?: return null
        val state=(agent.opt("agent_status") as? String)?.takeIf {it in setOf("idle","done","working","blocked")} ?: return null
        val seq=agent.opt("state_change_seq")?.toString()?.toULongOrNull() ?: return null
        val completion=agent.opt("completion_seq")?.toString()?.toULongOrNull()
        val provider=agent.optJSONObject("agent_session")?.let { obj ->
            listOf(obj.optString("source"),obj.optString("agent"),obj.optString("kind"),obj.optString("value"))
                .takeIf {fields -> fields.last().isNotBlank() && fields.sumOf(String::length)<=2048}
                ?.let {fields -> org.json.JSONArray(fields).toString()}
        }
        val cwd=(agent.opt("foreground_cwd") as? String)?.takeIf {
            kind.equals("nobs",true) && LiveNobsHistory.validWorkspace(it)
        }
        Status(Instance(location,kind,provider,cwd),state,seq,completion)
    }.getOrNull()

    fun screen(raw:String):String? {
        if(raw.length>64_000 || raw.any {it.isISOControl() && it !in "\r\n\t"}) return null
        return raw.lineSequence().toList().takeLast(30).joinToString("\n") {it.trimEnd()}.trimEnd()
    }

    // Early read-only guard for common permission menus even if Herdr has
    // not yet moved to blocked. Unknown screens still require user review.
    private val visibleMenu=Regex(
        """(?i)\[(?:y/n|y/N)\]|approve (?:this|once|always)|allow (?:once|always|this command)|do you want to proceed|esc to cancel|enter to (?:select|confirm)|use arrow keys""")
    fun mayHaveActiveMenu(text:String)=visibleMenu.containsMatchIn(text.lines().takeLast(12).joinToString("\n"))

    // Exact Codex scrollback/composer chrome. An unknown or partially rendered tail
    // still goes through the question detector; this never authorizes terminal input.
    private val codexFooter=Regex("""GPT-[^·]{1,100} · (?:~/|/)[^·]+(?: · .*)?""")
    fun codexScrolledEmptyComposer(capture:Capture):Boolean {
        if(capture.status.state!="idle" || !capture.status.instance.kind.equals("codex",true) ||
            mayHaveActiveMenu(capture.screen)) return false
        val tail=capture.screen.lineSequence().map(String::trim).filter(String::isNotEmpty).toList().takeLast(4)
        return tail.size==4 &&
            tail[0]=="↓ Back to bottom · esc" &&
            tail[1]=="› Ask Codex to do anything" &&
            codexFooter.matches(tail[2]) &&
            tail[3].startsWith("Earlier messages available.") &&
            tail[3].substringAfter("Earlier messages available.").trim().startsWith("enter/esc latest")
    }

    suspend fun preflight(
        capture:Capture,extractor:LiveTurnExtraction,ignoreBottomLines:Int=0,
    ):LiveTurnExtraction.Observation {
        if(ignoreBottomLines==0 && codexScrolledEmptyComposer(capture))
            return LiveTurnExtraction.Observation(LiveTurnExtraction.Kind.NONE)
        val visible=LiveExtractionWindow.crop(capture.screen,ignoreBottomLines)
        return if(ignoreBottomLines>0 && visible.isBlank())
            LiveTurnExtraction.Observation(LiveTurnExtraction.Kind.UNKNOWN)
        else extractor.inspect(visible,visible,"",false)
    }


    /**
     * Orchrd waits for new work plus a settled, current capture. We do not run its extra
     * intent-model pass: propose only a grounded new suffix, then require local review.
     * Nothing is proposed when redraws leave no unique overlap.
     */
    fun freshExcerpt(before:String,after:String,submitted:String):String? {
        if(before.isBlank() || after.isBlank() || before==after) return null
        val old=before.lines().filter {it.isNotBlank()}
        val fresh=after.lines().filter {it.isNotBlank()}
        // New render may put its prompt at the same screen coordinates, so only
        // use a unique contiguous old tail still present in the new capture.
        for(length in minOf(8,old.size) downTo 2) {
            val tail=old.takeLast(length)
            val matches=(0..fresh.size-length).filter {fresh.subList(it,it+length)==tail}
            if(matches.size!=1) continue
            val suffix=fresh.drop(matches.single()+length).filterNot { submitted in it }
            if(suffix.isEmpty() || suffix.none {it !in old}) continue
            return compactExcerpt(suffix.joinToString("\n"))
        }
        return null
    }

    fun compactExcerpt(text:String):String = buildString {
        var bytes=0
        for(codepoint in text.codePoints().toArray()) {
            val glyph=String(Character.toChars(codepoint))
            val n=glyph.toByteArray(Charsets.UTF_8).size
            if(bytes+n>350) break // Bound worst-case token count under the Live 500-token cap.
            append(glyph)
            bytes+=n
        }
    }.trim()

    fun validShare(text:String)=text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size<=350 &&
        text.none {it.isISOControl() && it !in "\n\t"}

    class Route(
        private val location:TuiLocation,private val session:String?,
        private val run:suspend (String)->Reply,
    ) {
        suspend fun current()=status(run(get(location,session)),location)
        private suspend fun capture(expected:Status):Capture? {
            val reply=run(read(location,session))
            val text=if(reply.ok) screen(reply.stdout) else null
            val after=current()
            return if(text!=null && after==expected) Capture(expected,text) else null
        }

        suspend fun observable(instance:Instance):Capture? {
            val state=current()?.takeIf {it.instance==instance && (it.ready || it.state=="blocked")} ?: return null
            return capture(state)
        }

        suspend fun ready(instance:Instance):Capture? {
            val before=current()?.takeIf {it.instance==instance && it.ready} ?: return null
            return capture(before)?.takeUnless {mayHaveActiveMenu(it.screen)}
        }

        suspend fun isCurrent(expected:Capture)=capture(expected.status)==expected

        suspend fun submit(expected:Capture,text:String):Boolean? {
            // A second full capture revalidates the screen after the user's review.
            if(ready(expected.status.instance)!=expected) return false
            val instance=expected.status.instance
            val nobs=if(LiveStructuredHistory.provider(instance.kind)==LiveStructuredHistory.Provider.NOBS)
                LiveStructuredHistory.binding(instance)?.id ?: return false
                else null
            val response=run(prompt(location,session,text,nobs))
            val acknowledgement=status(response,location,"agent_prompted")
            // No retry after an uncertain receipt: app cannot know whether Enter was sent.
            return if(acknowledgement?.instance==expected.status.instance) true else null
        }

        data class StoredReply(val status: Status, val draft: String)

        suspend fun finishCodex(
            before: Status, text: String, baseline: LiveCodexHistory.Page,
            readHistory: suspend () -> LiveCodexHistory.Page?,
            attempts: Int = 100, pause: suspend () -> Unit = { delay(1000) },
        ): StoredReply? = finishStructured(before, {
            readHistory()?.let {LiveCodexHistory.match(baseline,it,text)}
        },attempts,pause)

        suspend fun finishStructured(
            before:Status, compare:suspend ()->LiveCodexHistory.Match?,
            attempts:Int=100, pause:suspend ()->Unit={delay(1000)},
            onWorking:suspend (Status)->Unit={},
        ):StoredReply? {
            var working = false
            var reads = 0
            var stable: Status? = null
            repeat(attempts) {
                pause()
                val state = current() ?: return null
                if (state.instance != before.instance || state.sequence < before.sequence || state.state == "blocked")
                    return null
                if (state.state == "working" && state.sequence > before.sequence) {
                    working = true
                    onWorking(state)
                }
                val completion = state.completion?.let { it > maxOf(before.sequence, before.completion ?: 0uL) } == true
                if (!state.ready || state.sequence <= before.sequence || !(working || completion)) {
                    stable = null
                    return@repeat
                }
                if (stable != state) {
                    stable = state
                    return@repeat
                }
                val match = compare()
                if (current() != state) return null
                when (match) {
                    is LiveCodexHistory.Match.Completed -> return StoredReply(state, match.draft)
                    LiveCodexHistory.Match.Uncertain -> return null
                    LiveCodexHistory.Match.Waiting, null -> if (++reads >= 3) return null
                }
            }
            return null
        }

        suspend fun finish(
            before:Capture,text:String,
            attempts:Int=180,pause:suspend ()->Unit={delay(620)},
            nowMs:()->Long={System.nanoTime()/1_000_000},
            extraction:LiveTurnExtraction?=null,
            ignoreBottomLines:Int=0,
        ):Result {
            var working=false
            var candidate:Capture?=null
            var candidateSince=0L
            repeat(attempts) {
                pause()
                val state=current() ?: return Result("Pocket lost the agent. Check its terminal before sending anything again.")
                if(state.instance!=before.status.instance || state.sequence<before.status.sequence)
                    return Result("The selected agent changed. Pocket stopped tracking this request.")
                if(state.state=="blocked" && extraction==null)
                    return Result("The agent is waiting for input or approval. Review its terminal. Pocket did not answer or approve anything.")
                if(state.state=="working" && state.sequence>before.status.sequence) {
                    working=true
                    candidate=null
                }
                val newCompletion=state.completion?.let {it>maxOf(before.status.sequence,before.status.completion ?: 0uL)}==true
                val completed=state.ready && state.sequence>before.status.sequence && (working || newCompletion)
                if(completed || state.state=="blocked" && state.sequence>before.status.sequence) {
                    val settled=capture(state)
                    val now=nowMs()
                    if(settled!=candidate) {
                        candidate=settled
                        candidateSince=now
                    } else if(settled!=null && now-candidateSince>=2000) {
                        if(extraction!=null) {
                            val afterVisible=LiveExtractionWindow.crop(settled.screen,ignoreBottomLines)
                            val beforeVisible=LiveExtractionWindow.crop(before.screen,ignoreBottomLines)
                            val observed=if(ignoreBottomLines>0 && afterVisible.isBlank())
                                LiveTurnExtraction.Observation(LiveTurnExtraction.Kind.UNKNOWN)
                            else extraction.inspect(beforeVisible,afterVisible,text,completed)
                            if(!isCurrent(settled)) {
                                candidate=null
                                return@repeat
                            }
                            if(observed.kind==LiveTurnExtraction.Kind.QUESTION || state.state=="blocked" ||
                                mayHaveActiveMenu(settled.screen)) return Result(
                                "The agent is waiting for input. Pocket did not answer or approve anything. Check its terminal.",
                                observed.draft.takeIf {observed.kind==LiveTurnExtraction.Kind.QUESTION},settled,
                                question=true,evidence=observed.evidence)
                            if(observed.kind==LiveTurnExtraction.Kind.COMPLETION)
                                return Result("Herdr reported completion.",observed.draft,settled,evidence=observed.evidence)
                            return Result(observed.problem?.let {
                                "Herdr stopped working. "+it.explanation+" Check the terminal for the reply."
                            } ?: "Herdr stopped working, but Pocket could not verify a fresh reply. Check the terminal.")
                        }
                        if(mayHaveActiveMenu(settled.screen)) return Result(
                            "The terminal appears to be waiting for a choice. Review it directly; Pocket did not approve anything.")
                        return Result("Herdr reported completion.",freshExcerpt(before.screen,settled.screen,text) ?: "",settled)
                    }
                } else candidate=null
            }
            return Result("Pocket could not verify a completed answer. Check the terminal; no terminal text was shared.")
        }
    }
}

/** The server sends timed fragments, not a complete utterance or a ready-to-send task. */
internal class LiveVoiceTranscript {
    private data class Fragment(val eventId:String,val text:String,val start:Long,val end:Long)
    private val fragments=ArrayDeque<Fragment>()
    private var usedThrough=-1L

    fun add(event:LiveVoiceProtocol.Event.InputTranscript) {
        if(event.end<=usedThrough || fragments.any {it.eventId==event.id}) return
        if(fragments.size>=128) fragments.removeFirst()
        fragments.addLast(Fragment(event.id,event.text,event.start,event.end))
    }

    fun draft(offset:Long):String {
        val limit=offset+1200
        val request=fragments.filter {it.end in (usedThrough+1)..limit && it.start>=maxOf(0,offset-45_000)}
        if(request.isNotEmpty()) usedThrough=request.maxOf {it.end}
        while(fragments.firstOrNull()?.end?.let {it<=usedThrough}==true) fragments.removeFirst()
        return request.joinToString("") {it.text}
            .replace(Regex("[\\p{Cc}\\s]+")," ").trim().take(4000)
    }
}

internal data class PocketLiveReview(
    val id:String,val kind:Kind,val draft:String,val evidence:String?=null,
    val readbackProfile:LiveVoiceProfile?=null,
) {
    enum class Kind { PROMPT, QUESTION, REPLY }
}
