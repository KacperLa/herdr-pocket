package dev.kaspy.herdr

import org.json.JSONObject

data class CoreSession(
    val address:String,
    val name:String,
    val state:String,
    val agent:String,
    val status:String?,
    val description:String?=null,
    val workingDirectory:String?=null,
    val tuiLocation:TuiLocation?=null,
    val isAgent:Boolean=agent.isNotBlank(),
    val rosterPlacement:RosterPlacement?=null,
    val attentionRecency:ULong?=null,
    val spacesName:String?=null,
    val spacesPath:String?=null,
    val compactDescription:String?=description,
    val tuiAgentName:String?=null,
)
object CoreSessions {
    private val knownAgentNames=setOf("codex","claude","claude-code","opencode","pi","gemini","copilot","aider","amp","cursor","cursor-agent")
    // Pinned to herdr-core c7517808d64f06609e4ecf0db1757005373cc3a3 CLI compact JSON.
    fun parse(json:String):List<CoreSession> {
        val root=JSONObject(json)
        require(root.has("terminals") && root.has("hidden_finished")) { "Unsupported Core list format" }
        val array=root.getJSONArray("terminals")
        require(array.length()<=1000) { "Too many terminals" }
        return (0 until array.length()).map { i ->
            val item=array.getJSONObject(i)
            val address=item.getString("address")
            require(address.length in 1..256 && address.none { it.isISOControl() }) { "Invalid terminal address" }
            val statusObject=item.optJSONObject("status")
            val status=(statusObject?.opt("state") as? String) ?: (item.opt("status") as? String)
            val provider=(statusObject?.opt("app") as? String) ?: (item.opt("agent") as? String).orEmpty()
            val agent = statusObject?.opt("app") is String && provider.isNotBlank() ||
                status?.lowercase() in setOf("working","blocked","idle","done","error") ||
                provider.lowercase() in knownAgentNames
            CoreSession(address,item.optString("name",address).takeIf {item.opt("name") is String && it.isNotBlank()}
                ?: address,item.getString("state"),provider,status,isAgent=agent,
                attentionRecency=reportedSequence(item.opt("status_ms")),
                spacesPath=displayCorePath(item.optJSONObject("foreground")?.opt("cwd")) ?: displayCorePath(item.opt("cwd")))
        }
    }
    private fun displayCorePath(value:Any?):String? =
        (value as? String)?.filterNot(Char::isISOControl)?.take(1024)?.takeIf {it.isNotBlank()}

    fun autoAttach(sessions: List<CoreSession>, rememberedAddress: String?): CoreSession? {
        val live = sessions.filter { it.state == "live" }
        return live.singleOrNull { it.address == rememberedAddress }
            ?: live.singleOrNull { it.name == "main" }
            ?: live.singleOrNull()
    }

    internal fun rememberedSessionKey(endpoint: HostEndpoint, user: String): String =
        "recent-session:${endpoint.display}:${user.trim()}"

    internal fun createdShell(sessions:List<CoreSession>,name:String):CoreSession? =
        sessions.filter {it.state=="live" && it.name==name}.singleOrNull()

    // SSH exec may not read the interactive profile that added ~/.local/bin.
    // Run POSIX syntax in /bin/sh regardless of the remote account's login shell.
    private val resolver = """
        if command -v hc >/dev/null 2>&1; then
            hc_bin=hc
        elif [ -n "${'$'}{HOME:-}" ] && [ -x "${'$'}HOME/.local/bin/hc" ]; then
            hc_bin="${'$'}HOME/.local/bin/hc"
        else
            printf '%s\n' 'Herdr Core (hc) not found on PATH or in ~/.local/bin.' >&2
            exit 127
        fi
    """.trimIndent()

    fun listCommand(): String = resolvedCommand("ls --json")
    fun attachCommand(address: String): String {
        val target = quote(address)
        // Inspect help, not session output. An older hc keeps its existing attach path.
        val history = """
            case "${'$'}("${'$'}hc_bin" attach --help 2>/dev/null)" in
                *--history*) exec "${'$'}hc_bin" attach --history 1000 -- $target ;;
            esac
        """.trimIndent()
        return resolvedCommand("attach -- " + target, history)
    }

    fun canCreate(name: String, program: String): Boolean =
        name.matches(Regex("[a-z0-9_-]{1,32}")) &&
            program.matches(Regex("[A-Za-z0-9_./+][A-Za-z0-9_./+-]{0,255}"))

    fun createCommand(name: String, program: String): String {
        require(canCreate(name, program))
        return resolvedCommand("run --bg --json --no-parent --name " + quote(name) + " -- " + quote(program))
    }

    fun createShellCommand(name:String):String {
        require(canCreate(name,"sh"))
        return resolvedCommand("run --bg --json --no-parent --name " + quote(name))
    }

    private fun resolvedCommand(arguments: String, prelude: String = ""): String =
        "/bin/sh -c " + quote(resolver + "\n" + prelude + "\nexec \"\$hc_bin\" " + arguments)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** Remove labels already shown on the compact card without rewriting ordinary prose. */
internal fun agentCardDescription(description:String?,workspace:String?,name:String?=null):String? {
    var text=description?.trim()?.takeIf {it.isNotEmpty()} ?: return null
    val label=workspace?.trim()?.takeIf {it.isNotEmpty()}
    val heading=name?.trim()?.takeIf {it.isNotEmpty()}
    if(text.equals(label,ignoreCase=true) || text.equals(heading,ignoreCase=true)) return null
    // Pipes and typographic bullets are structural even when an OSC title
    // omits spaces. Dashes need surrounding whitespace to preserve words.
    // Remove the trailing label first so "Name | Workspace" becomes an
    // exact duplicate of the heading, not a workspace-only description.
    agentCardSeparators.findAll(text).firstOrNull {
        text.substring(it.range.last+1).trim().equals(label,ignoreCase=true)
    }?.let { text=text.substring(0,it.range.first).trim() }
    agentCardSeparators.findAll(text).firstOrNull {
        text.substring(0,it.range.first).trim().equals(heading,ignoreCase=true)
    }?.let { text=text.substring(it.range.last+1).trim() }
    return text.takeUnless {it.isEmpty() || it.equals(label,ignoreCase=true) || it.equals(heading,ignoreCase=true)}
}

private val agentCardSeparators=Regex("[|·•]|\\s+[—–-]\\s+")

/** The profile is metadata for the exact attached terminal, never a name match. */
internal fun activeProfileRow(roster:List<CoreSession>,address:String):CoreSession? =
    roster.singleOrNull {it.state=="live" && it.address==address}

internal fun groupedLiveRoster(sessions:List<CoreSession>):Pair<List<CoreSession>, List<CoreSession>> =
    sessions.filter {it.state=="live"}.distinctBy {it.address}.partition {it.isAgent}

/** Stable display grouping; terminal routing still uses CoreSession.address and full TuiLocation. */
data class RosterPlacement(
    val workspaceId: String,
    val workspaceLabel: String?,
    val workspaceOrder: Int?,
    val tabOrder: Int?,
    val paneOrder: Int?,
    val tabLabel: String?=null,
    val paneOrdinal: Int?=null,
    val workspaceBranch: String?=null,
)

internal data class RosterWorkspace(val id: String, val label: String, val rows: List<CoreSession>)

internal fun reportedSequence(value:Any?):ULong? = (value as? Number)?.toString()?.toULongOrNull()

internal fun priorityAgentRoster(sessions:List<CoreSession>):List<CoreSession> =
    groupedLiveRoster(sessions).first.sortedWith(
        compareByDescending<CoreSession> { session ->
            // Herdr TUI status_priority: Done is idle work not yet seen by the server.
            when(session.status?.lowercase()) {
                "blocked" -> 4
                "done" -> 3
                "working" -> 2
                "idle" -> 1
                else -> 0
            }
        }.thenByDescending {it.attentionRecency},
    )

internal fun spacesRoster(sessions:List<CoreSession>):List<RosterWorkspace> {
    val workspaces=sessions.asSequence().filter {it.state=="live"}.distinctBy {it.address}
        .groupBy {it.rosterPlacement?.workspaceId.orEmpty()}
    return workspaces.entries.sortedWith(
        compareBy<Map.Entry<String,List<CoreSession>>> {entry ->
            entry.value.firstNotNullOfOrNull {it.rosterPlacement?.workspaceOrder} ?: Int.MAX_VALUE
        }.thenBy {it.key.isEmpty()},
    ).map { (id,sessionsInWorkspace) ->
        val label=sessionsInWorkspace.firstNotNullOfOrNull {it.rosterPlacement?.workspaceLabel}
            ?: id.ifEmpty {"Unassigned"}
        val rows=sessionsInWorkspace.sortedWith(
            compareBy<CoreSession> {it.rosterPlacement?.tabOrder ?: Int.MAX_VALUE}
                .thenBy {it.rosterPlacement?.paneOrder ?: Int.MAX_VALUE},
        )
        RosterWorkspace(id,label,rows)
    }
}

internal enum class SpaceStatusFilter(val status: String?) {
    ALL(null), BLOCKED("blocked"), WORKING("working"), IDLE("idle"), DONE("done"),
}

/** Same match-any-field, all query words per field rule as Herdr's Go to search. */
private class RosterQuery(query:String) {
    private val words=query.take(256).trim().lowercase().split(Regex("\\s+")).filter {it.isNotEmpty()}
    val isEmpty:Boolean get()=words.isEmpty()
    fun matches(value:String?):Boolean {
        if(isEmpty) return true
        val text=value?.lowercase() ?: return false
        return words.all {it in text}
    }
}

internal fun filteredAgentRoster(
    sessions:List<CoreSession>,query:String,filter:SpaceStatusFilter=SpaceStatusFilter.ALL,
    pinned:Set<String> = emptySet(),
):List<CoreSession> {
    val matcher=RosterQuery(query)
    val priority=priorityAgentRoster(sessions)
    val agents=if(pinned.isEmpty()) priority else priority.partition {it.address in pinned}.let {(top,rest)->top+rest}
    val status=filter.status
    if(matcher.isEmpty && status==null) return agents
    return agents.filter { row ->
        (status==null || row.status.equals(status,ignoreCase=true)) &&
            sequenceOf(row.name,row.agent,row.rosterPlacement?.workspaceLabel,row.compactDescription,row.status)
                .any(matcher::matches)
    }
}

internal fun filteredSpacesRoster(
    sessions: List<CoreSession>,
    query: String,
    filter: SpaceStatusFilter,
): List<RosterWorkspace> {
    val matcher=RosterQuery(query)
    val status=filter.status
    val groups=spacesRoster(sessions)
    if(matcher.isEmpty && status==null) return groups
    return groups.mapNotNull { group ->
        val workspaceMatch=matcher.matches(group.label) || group.rows.any {matcher.matches(it.rosterPlacement?.workspaceBranch)}
        val visible=group.rows.filter { row ->
            (status==null || row.isAgent && row.status.equals(status,ignoreCase=true)) &&
                (workspaceMatch || sequenceOf(row.rosterPlacement?.tabLabel,row.spacesName,row.name,row.spacesPath,
                    row.workingDirectory,row.agent,row.description,row.tuiLocation?.paneId,row.address).any(matcher::matches))
        }
        group.copy(rows=visible).takeIf {visible.isNotEmpty()}
    }
}
