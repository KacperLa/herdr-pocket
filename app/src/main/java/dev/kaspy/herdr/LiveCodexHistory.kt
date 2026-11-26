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

    fun match(baseline: Page, after: Page, reviewedPrompt: String): Match {
        val oldHead = baseline.turns.firstOrNull()?.id
        val boundary = if (oldHead == null) {
            if (baseline.hasOlder || after.hasOlder) return Match.Uncertain
            after.turns.size
        } else after.turns.indexOfFirst { it.id == oldHead }.takeIf { it >= 0 } ?: return Match.Uncertain
        if (boundary == 0) return Match.Waiting
        // One new persisted turn only. We cannot identify who sent two identical prompts.
        if (boundary != 1) return Match.Uncertain
        val candidate = after.turns.first()
        // The reader may call another process's unfinished turn interrupted until it is committed.
        if (candidate.status != "completed") return Match.Waiting
        val items = (0 until candidate.rawItems.length()).map { candidate.rawItems.optJSONObject(it) ?: return Match.Uncertain }
        val user = items.filter { it.opt("type") == "userMessage" }.singleOrNull() ?: return Match.Uncertain
        val parts = user.optJSONArray("content") ?: return Match.Uncertain
        val part = parts.optJSONObject(0) ?: return Match.Uncertain
        if (parts.length() != 1 || part.opt("type") != "text" || part.opt("text") != reviewedPrompt)
            return Match.Uncertain
        val last = items.lastOrNull { it.opt("type") == "agentMessage" } ?: return Match.Uncertain
        if (last.opt("phase") != "final_answer") return Match.Uncertain
        val reply = (last.opt("text") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return Match.Uncertain
        if (reply.any { it.isISOControl() && it !in "\n\t" }) return Match.Uncertain
        return if (LiveReadback.validText(reply)) Match.Completed(reply) else Match.Uncertain
    }
}
