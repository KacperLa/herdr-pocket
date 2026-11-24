package dev.kaspy.herdr

import org.json.JSONObject

data class TuiLocation(val terminalId:String,val paneId:String,val tabId:String,val workspaceId:String) {
    internal companion object {
        private val safeId=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")

        fun from(value:JSONObject,expectedTerminal:String?=null):TuiLocation? {
            fun field(key:String)=(value.opt(key) as? String)?.takeIf(safeId::matches)
            val terminal=field("terminal_id") ?: return null
            if(expectedTerminal!=null && terminal!=expectedTerminal) return null
            return TuiLocation(terminal,field("pane_id") ?: return null,field("tab_id") ?: return null,
                field("workspace_id") ?: return null)
        }

        fun requireSafe(value:String):String = value.also {require(safeId.matches(it))}
    }
}

/** Only structured Herdr operations. Display text and terminal identity are never used as shell syntax. */
internal object TuiMutations {
    enum class Runner(val cli:String,val label:String) {
        CODEX("codex","Codex"), CLAUDE("claude","Claude Code"),
        OPENCODE("opencode","OpenCode"), GEMINI("gemini","Gemini")
    }

    private val slug=Regex("[a-z][a-z0-9_-]{0,31}")
    private val version=Regex("0\\.([0-9]{1,2})\\.([0-9]{1,2})(?:[-+][A-Za-z0-9.-]+)?")
    private val startVersionCheck="""
        pocket_version=${'$'}("${'$'}herdr_bin" --version 2>/dev/null) || exit 78
        case "${'$'}{pocket_version%%[-+]*}" in
            'herdr 0.7.'[5-9]|'herdr 0.7.'[1-9][0-9]|'herdr 0.'[89].[0-9]|'herdr 0.'[89].[1-9][0-9]) ;;
            *) printf '%s\n' 'HERDR_POCKET_AGENT_START_UNSUPPORTED' >&2; exit 78 ;;
        esac
    """.trimIndent()

    fun canStart(name:String)=slug.matches(name)
    fun canRename(label:String)=label.length in 1..100 && label==label.trim() &&
        !label.startsWith('-') && label.none(Char::isISOControl)

    fun canCreateFromRoster(json:String):Boolean = runCatching {
        val snapshot=result(json,"session_snapshot")?.optJSONObject("snapshot")
        val tag=snapshot?.opt("version") as? String ?: return false
        val match=version.matchEntire(tag) ?: return false
        val minor=match.groupValues[1].toInt()
        val patch=match.groupValues[2].toInt()
        minor in 8..9 || minor==7 && patch>=5
    }.getOrDefault(false)

    fun inspectPane(location:TuiLocation,session:String?) =
        TuiAgents.resolve("pane get "+quotedId(location.paneId),session)

    fun renamePane(location:TuiLocation,label:String,session:String?):String {
        require(canRename(label))
        return TuiAgents.resolve("pane rename "+quotedId(location.paneId)+" "+TuiAgents.quote(label),session)
    }

    fun confirmsPane(json:String,location:TuiLocation,expectedLabel:String?=null):Boolean = runCatching {
        val pane=result(json,"pane_info")?.optJSONObject("pane") ?: return false
        TuiLocation.from(pane,location.terminalId)==location &&
            (expectedLabel==null || pane.opt("label")==expectedLabel)
    }.getOrDefault(false)

    fun createTab(workspaceId:String,name:String,session:String?):String {
        require(canStart(name))
        return TuiAgents.resolve("tab create --workspace "+quotedId(workspaceId)+
            " --label "+TuiAgents.quote(name)+" --no-focus",session,startVersionCheck)
    }

    fun createShellTab(workspaceId:String,session:String?):String =
        TuiAgents.resolve("tab create --workspace "+quotedId(workspaceId)+" --no-focus",session,startVersionCheck)

    fun newPane(json:String,workspaceId:String):TuiLocation? = runCatching {
        val created=result(json,"tab_created") ?: return null
        val tab=created.optJSONObject("tab") ?: return null
        val pane=created.optJSONObject("root_pane") ?: return null
        val location=TuiLocation.from(pane) ?: return null
        location.takeIf {it.workspaceId==workspaceId && tab.opt("workspace_id")==workspaceId &&
            tab.opt("tab_id")==it.tabId }
    }.getOrNull()

    fun startAgent(name:String,runner:Runner,pane:TuiLocation,session:String?):String {
        require(canStart(name))
        return TuiAgents.resolve("agent start "+TuiAgents.quote(name)+" --kind "+runner.cli+
            " --pane "+quotedId(pane.paneId)+" --timeout 18000",session)
    }

    fun confirmsStart(json:String,pane:TuiLocation,name:String):Boolean = runCatching {
        val agent=result(json,"agent_started")?.optJSONObject("agent") ?: return false
        TuiLocation.from(agent,pane.terminalId)==pane && agent.opt("name")==name
    }.getOrDefault(false)

    private fun quotedId(id:String)=TuiAgents.quote(TuiLocation.requireSafe(id))
    private fun result(json:String,type:String) = JSONObject(json).optJSONObject("result")?.takeIf {it.opt("type")==type}
}
