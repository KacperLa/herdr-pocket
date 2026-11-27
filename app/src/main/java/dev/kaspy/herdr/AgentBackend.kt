package dev.kaspy.herdr

import org.json.JSONObject

internal enum class AgentBackend(val label:String) {
    CORE("Herdr Core"), TUI("Herdr TUI");

    fun listCommand(session:String?=null) = if(this==CORE) CoreSessions.listCommand() else TuiAgents.listCommand(session)
    fun attachCommand(address:String,session:String?=null) = if(this==CORE) CoreSessions.attachCommand(address) else TuiAgents.attachCommand(address,session)
    fun switchCommand(address:String,session:String?=null) = if(this==CORE) CoreSessions.attachCommand(address) else TuiAgents.attachCommand(address,session,staged=true)
    fun parse(json:String) = if(this==CORE) CoreSessions.parse(json) else TuiAgents.parse(json)
    fun rememberedKey(endpoint:HostEndpoint,user:String,session:String?=null) = CoreSessions.rememberedSessionKey(endpoint,user).let {
        if(this==CORE) it else "tui:${session ?: ""}:$it"
    }
    fun listFailure(exit:Int?, stderr:ByteArray):String {
        if(this==CORE) return if(exit==127) "Could not run hc on this host. Check PATH or ~/.local/bin/hc for this SSH user."
            else "Core list command failed (exit ${exit ?: "unknown"}). Check hc installation."
        val details=stderr.toString(Charsets.UTF_8)
        return when {
            exit==127 -> "Herdr TUI was not found. Use the SSH account that runs herdr."
            "server_not_running" in details || "no running herdr server" in details.lowercase() ->
                "No Herdr TUI server is running for this SSH account. Open herdr there first."
            "protocol_mismatch" in details -> "The Herdr TUI CLI and its running server use different versions."
            else -> "Could not list Herdr TUI agents (exit ${exit ?: "unknown"}). Make sure herdr agent list works over SSH."
        }
    }
}

/** Read-only official Herdr JSON roster. Neither listing route starts a server or agent. */
internal object TuiAgents {
    private val terminalId=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    private val resolver = """
        if command -v herdr >/dev/null 2>&1; then
            herdr_bin=herdr
        elif [ -n "${'$'}{HOME:-}" ] && [ -x "${'$'}HOME/.local/bin/herdr" ]; then
            herdr_bin="${'$'}HOME/.local/bin/herdr"
        elif [ -n "${'$'}{HOME:-}" ] && [ -x "${'$'}HOME/.cargo/bin/herdr" ]; then
            herdr_bin="${'$'}HOME/.cargo/bin/herdr"
        elif [ -n "${'$'}{HOME:-}" ] && [ -x "${'$'}HOME/.nix-profile/bin/herdr" ]; then
            herdr_bin="${'$'}HOME/.nix-profile/bin/herdr"
        elif [ -x /run/current-system/sw/bin/herdr ]; then
            herdr_bin=/run/current-system/sw/bin/herdr
        else
            printf '%s\n' 'Herdr TUI not found for this SSH account.' >&2
            exit 127
        fi
    """.trimIndent()

    // The matching server learns that a direct client is pending before
    // rendering from v0.6.6 onward. A shell frame on older releases could
    // otherwise be mistaken for an accepted agent attachment.
    private val verifiedDirectAttach = """
        herdr_version=${'$'}("${'$'}herdr_bin" --version 2>/dev/null) || herdr_version=
        herdr_base=${'$'}{herdr_version%%[-+]*}
        case "${'$'}herdr_base" in
            'herdr 0.6.'[6-9]|'herdr 0.6.'[1-9][0-9]|'herdr 0.'[7-9].[0-9]|'herdr 0.'[7-9].[1-9][0-9]) ;;
            *)
                printf '%s\n' 'HERDR_POCKET_UNVERIFIED_DIRECT_ATTACH' >&2
                exit 78
                ;;
        esac
    """.trimIndent()

    fun parseSession(value:String):String? = value.trim().takeIf {
        it.matches(Regex("[A-Za-z0-9._-]{1,64}")) && it!="." && it!=".." && !it.startsWith('-')
    }

    fun listCommand(session:String?=null):String {
        val select=sessionSelector(session)
        val run="\"\$herdr_bin\" ${select}"
        val roster="""
            if pocket_snapshot=${'$'}($run api snapshot 2>/dev/null) && [ -n "${'$'}pocket_snapshot" ]; then
                printf '%s\n' "${'$'}pocket_snapshot"
                exit 0
            fi
            pocket_agents=${'$'}($run agent list) || exit ${'$'}?
            pocket_panes=${'$'}($run pane list 2>/dev/null) || pocket_panes=null
            pocket_tabs=${'$'}($run tab list 2>/dev/null) || pocket_tabs=null
            pocket_workspaces=${'$'}($run workspace list 2>/dev/null) || pocket_workspaces=null
            printf '{"pocket_legacy":true,"agent_list":%s,"pane_list":%s,"tab_list":%s,"workspace_list":%s}\n' \
                "${'$'}pocket_agents" "${'$'}{pocket_panes:-null}" "${'$'}{pocket_tabs:-null}" "${'$'}{pocket_workspaces:-null}"
        """.trimIndent()
        return "/bin/sh -c " + quote(resolver+"\n"+roster)
    }

    fun attachCommand(address:String,session:String?=null,staged:Boolean=false):String {
        require(terminalId.matches(address))
        // Stable CLI takes the non-option terminal ID as its first operand; it rejects "--".
        // No --takeover: the existing viewer must retain ownership.
        return resolve("terminal attach " + quote(address),session,if(staged) verifiedDirectAttach else null)
    }

    fun parse(json:String):List<CoreSession> {
        val root=JSONObject(json)
        require(!root.has("error")) {"Herdr TUI list failed"}
        val snapshot=typedResult(root,"session_snapshot")?.optJSONObject("snapshot")
        val legacy=root.takeIf {it.optBoolean("pocket_legacy",false)}
        val agents = when {
            snapshot!=null -> snapshot.getJSONArray("agents")
            legacy!=null -> checkNotNull(typedResult(legacy.optJSONObject("agent_list"),"agent_list")).getJSONArray("agents")
            else -> checkNotNull(typedResult(root,"agent_list")).getJSONArray("agents")
        }
        val panes=snapshot?.optJSONArray("panes")
            ?: typedResult(legacy?.optJSONObject("pane_list"),"pane_list")?.optJSONArray("panes")
        val labels=RosterLabels(
            panes,
            snapshot?.optJSONArray("tabs") ?: typedResult(legacy?.optJSONObject("tab_list"),"tab_list")?.optJSONArray("tabs"),
            snapshot?.optJSONArray("workspaces") ?: typedResult(legacy?.optJSONObject("workspace_list"),"workspace_list")?.optJSONArray("workspaces"),
        )
        require(agents.length()<=1000 && (panes?.length() ?: 0)<=4096) {"Too many Herdr TUI terminals"}
        val registeredIds=HashSet<String>()
        val roster=(0 until agents.length()).mapNotNull { i ->
            val item=agents.getJSONObject(i)
            val address=item.get("terminal_id") as? String ?: error("Invalid Herdr terminal ID")
            require(terminalId.matches(address)) {"Invalid Herdr terminal ID"}
            registeredIds.add(address)
            if(item.optBoolean("launch_pending",false)) return@mapNotNull null
            fun display(key:String,limit:Int=120):String? = displayText(item.opt(key),limit)
            val provider=display("agent")
            val runner=runnerName(provider)
            // A custom tab label wins. On an unnamed tab, combine the agent's
            // name or runner with Herdr's tab position, never a pane/workspace title.
            val location=TuiLocation.from(item,address)
            val registeredName=display("name")
            val placement=labels.placement(location)
            val name=labels.agentTitle(location,registeredName ?: runner ?: "Agent")
            val descriptions=listOf(
                display("title",480),
                display("terminal_title_stripped",480),
                display("terminal_title",480),
                displayText(item.optJSONObject("tokens")?.opt("summary"),480),
                display("display_agent",480),
            ).filterNotNull().filter { value ->
                !value.equals(name,ignoreCase=true) && !value.equals(provider,ignoreCase=true) &&
                    !value.equals(runner,ignoreCase=true)
            }
            val description=descriptions.firstOrNull()
            val compactDescription=descriptions.firstNotNullOfOrNull { value ->
                agentCardDescription(value,placement?.workspaceLabel,name)?.takeUnless {
                    it.equals(provider,ignoreCase=true) || it.equals(runner,ignoreCase=true)
                }
            }
            val path=display("cwd",1024) ?: display("foreground_cwd",1024)
            CoreSession(address,name,"live",runner.orEmpty(),display("agent_status"),description,path,location,isAgent=true,
                rosterPlacement=placement,attentionRecency=reportedSequence(item.opt("state_change_seq")),
                spacesName=name,
                spacesPath=labels.foregroundPath(location) ?: display("foreground_cwd",1024) ?: path,
                compactDescription=compactDescription,tuiAgentName=registeredName)
        }
        val terminals=(0 until (panes?.length() ?: 0)).mapNotNull { i ->
            val pane=panes?.optJSONObject(i) ?: return@mapNotNull null
            val address=(pane.opt("terminal_id") as? String)?.takeIf(terminalId::matches) ?: return@mapNotNull null
            if(address in registeredIds || pane.optBoolean("launch_pending")) return@mapNotNull null
            plainTerminal(pane, address, labels)
        }
        return (roster + terminals).distinctBy {it.address}
    }

    fun plainTerminal(location:TuiLocation):CoreSession =
        CoreSession(location.terminalId,"Terminal","live","",null,tuiLocation=location,isAgent=false)

    private fun plainTerminal(pane:JSONObject,address:String,labels:RosterLabels):CoreSession {
        val explicitName=displayText(pane.opt("label"),120)
        val label=explicitName ?: labels.parentLabel(pane) ?: "Terminal"
        val title=displayText(pane.opt("terminal_title_stripped"),480)
            ?: displayText(pane.opt("terminal_title"),480) ?: displayText(pane.opt("title"),480)
        val path=displayText(pane.opt("cwd"),1024) ?: displayText(pane.opt("foreground_cwd"),1024)
        val location=TuiLocation.from(pane,address)
        return CoreSession(address,label,"live","",null,title?.takeUnless {it.equals(label,true)},
            path,location,isAgent=false,rosterPlacement=labels.placement(location),
            spacesName=labels.spacesName(location,explicitName,null,null),
            spacesPath=labels.foregroundPath(location) ?: path)
    }

    private fun typedResult(root:JSONObject?,type:String):JSONObject? =
        root?.optJSONObject("result")?.takeIf {it.opt("type")==type}

    private class RosterLabels(
        panes:org.json.JSONArray?=null,tabs:org.json.JSONArray?=null,workspaces:org.json.JSONArray?=null,
    ) {
        private val paneRows=records(panes)
        private val paneById=paneRows.uniqueBy("pane_id")
        private val tabRows=records(tabs)
        private val tabById=tabRows.uniqueBy("tab_id")
        private val workspaceRows=records(workspaces)
        private val workspaceById=workspaceRows.uniqueBy("workspace_id")

        private val workspaceOrder=workspaceRows.mapIndexedNotNull { index,row ->
            id(row,"workspace_id")?.let {it to index}
        }.toMap()
        private val paneLocations=paneRows.mapNotNull {TuiLocation.from(it)}
        // Older roster responses may omit tab.list; pane.list still walks tabs in display order.
        private val explicitTabs=tabRows.groupBy {id(it,"workspace_id")}
            .mapValues {(_,rows) -> rows.mapNotNull {id(it,"tab_id")}}
        private val inferredTabs=paneLocations.groupBy {it.workspaceId}
            .mapValues {(_,rows) -> rows.map {it.tabId}}
        private val tabOrder=(explicitTabs.keys.filterNotNull()+inferredTabs.keys).distinct().associateWith {workspace ->
            (explicitTabs[workspace].orEmpty()+inferredTabs[workspace].orEmpty()).distinct()
                .withIndex().associate {it.value to it.index}
        }
        private val paneOrder=paneLocations
            .groupBy {it.workspaceId to it.tabId}.mapValues { (_,rows) ->
                rows.distinct().mapIndexed { index,row -> row to index }.toMap()
            }

        fun placement(location:TuiLocation?):RosterPlacement? {
            location ?: return null
            return RosterPlacement(
                location.workspaceId, displayText(workspaceById[location.workspaceId]?.opt("label"),120),
                workspaceOrder[location.workspaceId], tabOrder[location.workspaceId]?.get(location.tabId),
                paneOrder[location.workspaceId to location.tabId]?.get(location),
                tabLabel(location),
                paneOrdinal(location),
                displayText(workspaceById[location.workspaceId]?.opt("branch"),120),
            )
        }

        private fun tab(location:TuiLocation):JSONObject? =
            tabById[location.tabId]?.takeIf {id(it,"workspace_id")==location.workspaceId}

        private fun reportedTabLabel(location:TuiLocation):String? = displayText(tab(location)?.opt("label"),120)

        private fun tabLabel(location:TuiLocation):String? = reportedTabLabel(location)
            ?: tabOrder[location.workspaceId]?.get(location.tabId)?.let {(it+1).toString()}

        fun agentTitle(location:TuiLocation?,agentName:String):String {
            location ?: return agentName
            val record=tab(location)
            val label=reportedTabLabel(location)
            val position=tabOrder[location.workspaceId]?.get(location.tabId)?.plus(1)?.toString()
            val custom=record?.opt("custom_label") as? Boolean
            // Stable Herdr exposes the display label and tab order, but not always
            // the custom flag. A label unequal to the positional default is custom.
            if(label!=null && (custom==true ||
                    custom==null && (label.toULongOrNull()==null || position!=null && label!=position)))
                return label
            val number=label?.takeIf {it.toULongOrNull()?.let {n -> n>0uL}==true} ?: position
            return if(number==null) agentName else "$agentName · $number"
        }

        private fun paneOrdinal(location:TuiLocation):Int? =
            paneOrder[location.workspaceId to location.tabId]?.takeIf {it.size>1}?.get(location)?.plus(1)

        private fun exactPane(location:TuiLocation):JSONObject? =
            paneById[location.paneId]?.takeIf {TuiLocation.from(it)==location}

        fun foregroundPath(location:TuiLocation?):String? =
            location?.let(::exactPane)?.let {displayText(it.opt("foreground_cwd"),1024) ?: displayText(it.opt("cwd"),1024)}

        /** v0.9.5 Go to builds presentation labels separately from agent and terminal identities. */
        fun spacesName(location:TuiLocation?,name:String?,title:String?,provider:String?):String? {
            location ?: return null
            val tabLabel=tabLabel(location)
            val namedTab=tabLabel?.takeIf {tab(location)?.optBoolean("custom_label")==true || it.toULongOrNull()==null}
            val ordinal=paneOrdinal(location)
            if(ordinal!=null) {
                val paneName=name ?: title ?: provider ?: "Terminal"
                return listOfNotNull(namedTab?.takeIf {it!=paneName},paneName,ordinal.toString()).joinToString(" · ")
            }
            return name ?: namedTab ?: title ?: tabLabel?.let { "${provider ?: "Terminal"} · $it" }
        }

        // The public TabInfo.number is an identity, not its one-based position.
        // The stable server emits tabs in layout order, and defaults to position.
        private val tabDefaultLabel=tabRows.groupBy {id(it,"workspace_id")}.values.flatMap {group ->
            group.mapIndexedNotNull {i,tab -> id(tab,"tab_id")?.let {it to (i+1).toString()}}
        }.toMap()
        private val workspaceAutomaticLabels=paneById.values.groupBy {id(it,"workspace_id")}.mapValues {(_,items) ->
            items.asSequence().flatMap {sequenceOf(it.opt("cwd"),it.opt("foreground_cwd"))}
                .mapNotNull {displayText(it,1024)}.flatMap(::pathLabels).toSet()
        }

        fun parentLabel(agent:JSONObject):String? {
            val tabId=id(agent,"tab_id")
            val workspaceId=id(agent,"workspace_id")
            if(tabId!=null && workspaceId!=null) {
                tabById[tabId]?.takeIf {id(it,"workspace_id")==workspaceId}?.let {tab ->
                    val label=displayText(tab.opt("label"),120)
                    if(label!=null && tab.opt("custom_label")!=false &&
                        (tab.opt("custom_label")==true || label!=tabDefaultLabel[tabId])) return label
                }
            }
            if(workspaceId!=null) {
                val workspace=workspaceById[workspaceId] ?: return null
                val label=displayText(workspace.opt("label"),120) ?: return null
                if(workspace.opt("custom_label")==true) return label
                if(workspace.opt("custom_label")==false || label in setOf("~","/","workspace")) return null
                // Public WorkspaceInfo doesn't distinguish a custom label from
                // the real TUI display label derived from its directory/repo.
                // Exclude recognizable auto labels; never use an agent title.
                val worktree=workspace.optJSONObject("worktree")
                val workspacePaths=sequenceOf(worktree?.opt("repo_root"),worktree?.opt("checkout_path"))
                    .mapNotNull {displayText(it,1024)}.flatMap(::pathLabels)
                if(label==displayText(worktree?.opt("repo_name"),120) ||
                    label in workspaceAutomaticLabels[workspaceId].orEmpty() || workspacePaths.any {it==label}) return null
                return label
            }
            return null
        }

        private companion object {
            fun id(value:JSONObject,key:String) = (value.opt(key) as? String)?.takeIf {it.isNotBlank()}
            fun records(array:org.json.JSONArray?):List<JSONObject> = if(array!=null && array.length()<=4096)
                (0 until array.length()).mapNotNull(array::optJSONObject) else emptyList()
            fun List<JSONObject>.uniqueBy(key:String):Map<String,JSONObject> =
                mapNotNull {row -> id(row,key)?.let {it to row}}.groupBy({it.first},{it.second})
                    .mapNotNull { (id,rows) -> rows.singleOrNull()?.let {id to it} }.toMap()
            fun pathLabels(path:String) = sequenceOf(path)+path.split('/').asSequence().filter {it.isNotEmpty()}
        }
    }

    private fun displayText(value:Any?,limit:Int) = (value as? String)?.filterNot(Char::isISOControl)
        ?.trim()?.take(limit)?.takeIf {it.isNotBlank()}

    private fun runnerName(value:String?):String? = when(value?.lowercase()) {
        null,"agent","unknown" -> null
        "codex" -> "Codex"
        "claude" -> "Claude Code"
        "opencode" -> "OpenCode"
        "copilot" -> "GitHub Copilot"
        "agy" -> "Antigravity"
        "omp" -> "OMP"
        else -> value.replaceFirstChar(Char::titlecase)
    }

    fun nameForTerminal(agents:List<CoreSession>,terminalId:String?):String? =
        agents.firstOrNull {it.address==terminalId}?.name

    fun runnerAndPath(agent:CoreSession):String? = listOfNotNull(
        agent.agent.takeIf {it.isNotBlank()},
        agent.workingDirectory?.takeIf {it.isNotBlank()},
    ).joinToString(" · ").ifEmpty {null}

    enum class Status { WORKING, BLOCKED, IDLE, DONE, UNKNOWN }

    fun status(value:String?):Status = when(value?.lowercase()) {
        "working" -> Status.WORKING
        "blocked" -> Status.BLOCKED
        "idle" -> Status.IDLE
        "done" -> Status.DONE
        else -> Status.UNKNOWN
    }

    fun attachFailure(stderr:ByteArray):String? {
        val details=stderr.toString(Charsets.UTF_8).lowercase()
        return when {
            "herdr_pocket_unverified_direct_attach" in details ->
                "This Herdr version does not support verified fast switching. You can use the full TUI via Advanced options instead."
            "already has an attached client" in details || "already has a controller" in details ->
                "Another direct client controls this Herdr terminal. Disconnect it there, then retry. No takeover was attempted."
            "server_not_running" in details || "no running herdr server" in details ->
                "The Herdr TUI server is no longer running for this SSH account."
            "protocol_mismatch" in details ->
                "The Herdr TUI CLI and server versions do not match."
            "terminal attach failed:" in details && "not found" in details ->
                "That Herdr terminal is no longer available. Refresh the agent list."
            else -> null
        }
    }

    private fun sessionSelector(session:String?) =
        session?.let { require(parseSession(it)==it); "--session " + quote(it) + " " }.orEmpty()

    internal fun resolve(args:String,session:String?,check:String?=null):String {
        val select=sessionSelector(session)
        val prelude=check?.let { "$it\n" }.orEmpty()
        return "/bin/sh -c " + quote(resolver + "\n" + prelude + "exec \"\$herdr_bin\" " + select + args)
    }
    internal fun quote(value:String):String = "'" + value.replace("'", "'\\''") + "'"
}
