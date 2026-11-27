package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only stored history. Never sends Codex turn/start, resume, or approval RPCs. */
internal object LiveCodexHistory {
    const val LIMIT = 4
    private const val REPLY_ID = 2
    val command = "/bin/sh -c " + TuiAgents.quote("""
        if command -v codex >/dev/null 2>&1; then
            codex_bin=codex
        else
            for candidate in "${'$'}HOME/.local/bin/codex" "${'$'}HOME/.npm-global/bin/codex" "${'$'}HOME/.nix-profile/bin/codex" /run/current-system/sw/bin/codex; do
                if [ -x "${'$'}candidate" ]; then codex_bin="${'$'}candidate"; break; fi
            done
        fi
        [ -n "${'$'}{codex_bin:-}" ] || exit 127
        exec "${'$'}codex_bin" app-server --listen stdio://
    """.trimIndent())

    fun threadId(instance: LiveAgentHerdr.Instance): String? = runCatching {
        if (!instance.kind.equals("codex", true)) return null
        val session = JSONArray(instance.providerSession ?: return null)
        if (session.length() != 4 || session.opt(0) != "herdr:codex" ||
            session.opt(1) != "codex" || session.opt(2) != "id") return null
        val id = session.opt(3) as? String ?: return null
        id.takeIf { UUID.fromString(it).toString() == it }
    }.getOrNull()

    fun request(id: String): ByteArray {
        require(UUID.fromString(id).toString() == id)
        val initialize = JSONObject().put("id", 1).put("method", "initialize").put("params",
            JSONObject().put("clientInfo", JSONObject().put("name", "herdr-pocket").put("version", "0.93")))
        val initialized = JSONObject().put("method", "initialized")
        val list = JSONObject().put("id", REPLY_ID).put("method", "thread/turns/list").put("params",
            JSONObject().put("threadId", id).put("limit", LIMIT).put("sortDirection", "desc").put("itemsView", "full"))
        return "$initialize\n$initialized\n$list\n".toByteArray(Charsets.UTF_8)
    }

    private fun envelopes(output: String): List<JSONObject> =
        output.substringBeforeLast('\n', "").lineSequence().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }.toList()

    fun responseSeen(output: String): Boolean = envelopes(output).any { it.opt("id") == REPLY_ID }

    data class Turn(val id: String, val status: String, val rawItems: JSONArray)
    data class Page(val turns: List<Turn>, val hasOlder: Boolean)

    fun parse(output: String): Page? = runCatching {
        val envelopes = envelopes(output)
        if (envelopes.singleOrNull { it.opt("id") == 1 }?.optJSONObject("result") == null) return null
        val result = envelopes.singleOrNull { it.opt("id") == REPLY_ID }?.optJSONObject("result") ?: return null
        val data = result.getJSONArray("data")
        if (data.length() > LIMIT) return null
        val turns = (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            val id = obj.opt("id") as? String ?: return null
            if (id.isBlank() || id.length > 160) return null
            val status = obj.opt("status") as? String ?: return null
            val items = obj.getJSONArray("items")
            Turn(id,status,items)
        }
        if (turns.map { it.id }.toSet().size != turns.size) return null
        if (!result.has("nextCursor")) return null
        Page(turns, !result.isNull("nextCursor"))
    }.getOrNull()

    sealed interface Match {
        data object Waiting : Match
        data object Uncertain : Match
        data class Completed(val draft: String): Match
    }

    private sealed interface Candidate {
        data object Waiting:Candidate
        data object Uncertain:Candidate
        data class Found(val turn:Turn,val items:List<JSONObject>):Candidate
    }

    private fun candidate(baseline:Page,after:Page,reviewedPrompt:String,completedOnly:Boolean):Candidate {
        val oldHead=baseline.turns.firstOrNull()?.id
        val boundary=if(oldHead==null) {
            if(baseline.hasOlder || after.hasOlder) return Candidate.Uncertain
            after.turns.size
        } else after.turns.indexOfFirst {it.id==oldHead}.takeIf {it>=0} ?: return Candidate.Uncertain
        if(boundary==0) return Candidate.Waiting
        if(boundary!=1) return Candidate.Uncertain
        val turn=after.turns.first()
        // A separate app-server may label another process's in-flight turn interrupted.
        if(completedOnly && turn.status!="completed") return Candidate.Waiting
        val items=(0 until turn.rawItems.length()).map {turn.rawItems.optJSONObject(it) ?: return Candidate.Uncertain}
        // No published user item yet: wait. A different or ambiguous prompt is not ours.
        val users=items.filter {it.opt("type")=="userMessage"}
        if(!completedOnly && users.isEmpty()) return Candidate.Waiting
        val user=users.singleOrNull() ?: return Candidate.Uncertain
        val parts=user.optJSONArray("content") ?: return Candidate.Uncertain
        val part=parts.optJSONObject(0) ?: return Candidate.Uncertain
        if(parts.length()!=1 || part.opt("type")!="text" || part.opt("text")!=reviewedPrompt)
            return Candidate.Uncertain
        return Candidate.Found(turn,items)
    }

    /** One feed per acknowledged delegation. Ambiguous history stops progress, not final readback. */
    class Progress(private val baseline:Page,private val prompt:String) {
        private var turnId:String?=null
        private var stopped=false
        private val sent=HashSet<String>()

        fun next(page:Page):String? {
            if(stopped || sent.size>=20) return null
            return when(val match=candidate(baseline,page,prompt,completedOnly=false)) {
                Candidate.Waiting -> null
                Candidate.Uncertain -> {stopped=true;null}
                is Candidate.Found -> {
                    val turn=match.turn
                    if(turnId!=null && turn.id!=turnId) {stopped=true;return null}
                    turnId=turn.id
                    if(turn.status !in setOf("inProgress","interrupted") ||
                        match.items.any {it.opt("type")=="agentMessage" && it.opt("phase")=="final_answer"}) return null
                    val latest=CodexProgress.from(page).messages.lastOrNull() ?: return null
                    latest.takeIf {LiveReadback.validText(it) && sent.add(it)}
                }
            }
        }
    }

    fun match(baseline:Page,after:Page,reviewedPrompt:String):Match {
        val items=when(val match=candidate(baseline,after,reviewedPrompt,completedOnly=true)) {
            Candidate.Waiting -> return Match.Waiting
            Candidate.Uncertain -> return Match.Uncertain
            is Candidate.Found -> match.items
        }
        val last = items.lastOrNull { it.opt("type") == "agentMessage" } ?: return Match.Uncertain
        if (last.opt("phase") != "final_answer") return Match.Uncertain
        val reply = (last.opt("text") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return Match.Uncertain
        if (reply.any { it.isISOControl() && it !in "\n\t" }) return Match.Uncertain
        return if (LiveReadback.validText(reply)) Match.Completed(reply) else Match.Uncertain
    }
}
