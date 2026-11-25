package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentFilesRoot(val path: String, val workspaceFallback: Boolean)

internal object AgentFiles {
    private val stable = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:\\+[a-zA-Z0-9.-]+)?")

    fun root(json: String, terminalId: String): AgentFilesRoot? {
        val reply = JSONObject(json)
        val result = reply.optJSONObject("result")
        val snapshot = result?.takeIf { it.opt("type") == "session_snapshot" }?.optJSONObject("snapshot")
        val legacy=reply.takeIf {it.optBoolean("pocket_legacy",false)}
        val agents=snapshot?.optJSONArray("agents") ?: typedRows(legacy,"agent_list","agents")
        val panes=snapshot?.optJSONArray("panes") ?: typedRows(legacy,"pane_list","panes")
        val agentMatches=terminalRows(agents,terminalId,1000) ?: return null
        val paneMatches=terminalRows(panes,terminalId,4096) ?: return null
        if(agentMatches.size>1 || paneMatches.size>1) return null
        val agent=agentMatches.singleOrNull()
        val pane=paneMatches.singleOrNull()
        if(agent==null && pane==null || agent?.optBoolean("launch_pending")==true ||
            pane?.optBoolean("launch_pending")==true) return null
        if(agent!=null && pane!=null && listOf("workspace_id","tab_id","pane_id").any {field ->
            val agentId=agent.opt(field) as? String
            val paneId=pane.opt(field) as? String
            agentId!=null && paneId!=null && agentId!=paneId
        }) return null

        val version=stable.matchEntire(snapshot?.optString("version").orEmpty())
        val reliable=version?.let {it.groupValues[1].toInt()>=1 || it.groupValues[2].toInt()>=9}==true
        // A plain TUI shell only appears as a pane, not an agent. Use fresh metadata
        // for that exact terminal, never the currently focused pane or SSH's default cwd.
        val rows=listOfNotNull(pane,agent)
        val foreground=if(reliable) rows.firstNotNullOfOrNull {absolute(it.opt("foreground_cwd"))} else null
        if(foreground!=null) return AgentFilesRoot(foreground,false)
        val fallback=rows.firstNotNullOfOrNull {absolute(it.opt("cwd"))} ?: return null
        return AgentFilesRoot(fallback,true)
    }

    private fun typedRows(legacy:JSONObject?,type:String,field:String):JSONArray? =
        legacy?.optJSONObject(type)?.optJSONObject("result")?.takeIf {it.opt("type")==type}?.optJSONArray(field)

    private fun terminalRows(rows:JSONArray?,id:String,limit:Int):List<JSONObject>? {
        if(rows==null) return emptyList()
        if(rows.length()>limit) return null
        return (0 until rows.length()).mapNotNull(rows::optJSONObject).filter {it.opt("terminal_id")==id}
    }

    fun absolute(value: Any?): String? = (value as? String)?.takeIf { path ->
        path.length in 1..4096 && path.startsWith('/') && !path.startsWith("//") &&
            path.none(Char::isISOControl) && path.split('/').none { it == "." || it == ".." }
    }?.let { if (it == "/") it else it.trimEnd('/') }

    fun entryName(name: String): Boolean = name.length in 1..1024 && name != "." && name != ".." &&
        '/' !in name && name.none(Char::isISOControl) &&
        name.none { Character.getType(it) == Character.FORMAT.toInt() }

    fun newFolderName(value: String): Boolean = value.isNotBlank() && entryName(value)

    fun child(dir: String, name: String): String {
        require(entryName(name))
        return if (dir == "/") "/$name" else "$dir/$name"
    }

    fun clipboardPath(folder: String?, name: String? = null): String? {
        val parent = absolute(folder) ?: return null
        return if (name == null) parent else if (entryName(name)) child(parent, name) else null
    }

    fun contains(root: String, path: String): Boolean =
        root == "/" && path.startsWith('/') || path == root || path.startsWith("$root/")

    // JSch's get, ls and lstat accept glob patterns. Quote literal remote names.
    fun literal(path: String): String = buildString {
        for (c in path) {
            if (c == '\\' || c == '*' || c == '?') append('\\')
            append(c)
        }
    }
}

internal enum class FilesBackAction { MENU, CANCEL_READ, FOLDER_CONTENTS, PARENT }

internal fun filesBackAction(
    hasFolder: Boolean, hasParent: Boolean, loading: Boolean, previewOrError: Boolean,
): FilesBackAction = when {
    !hasFolder -> FilesBackAction.MENU
    loading -> FilesBackAction.CANCEL_READ
    previewOrError -> FilesBackAction.FOLDER_CONTENTS
    hasParent -> FilesBackAction.PARENT
    else -> FilesBackAction.MENU
}

/** Commit and cancel may race between the document I/O worker and the UI thread. */
internal class FileSaveGate {
    @Volatile private var state=0 // pending, canceled, saved
    val canWrite get()=state==0
    val committed get()=state==2

    @Synchronized fun cancel():Boolean {
        if(state!=0) return false
        state=1
        return true
    }

    @Synchronized fun commit():Boolean {
        if(state!=0) return false
        state=2
        return true
    }
}

/** Anchor a picker/transfer to the directory that was open when Upload was tapped. */
internal data class FilesUploadAnchor(val root:String,val parts:List<String>,val folder:String) {
    fun matches(currentRoot:String?,currentParts:List<String>,currentFolder:String?):Boolean =
        root==currentRoot && parts==currentParts && folder==currentFolder
}

internal enum class FilesUploadInterruption {
    CONNECTION_LOST_BEFORE_TRANSFER, CONNECTION_LOST_DURING_TRANSFER, PICKER_ROUTE_EXPIRED;

    companion object {
        fun onConnectionLoss(active:Boolean, transportRequested:Boolean):FilesUploadInterruption? = when {
            !active -> null
            transportRequested -> CONNECTION_LOST_DURING_TRANSFER
            else -> CONNECTION_LOST_BEFORE_TRANSFER
        }

        fun onPickerWithoutRoute(hasDocument:Boolean):FilesUploadInterruption? =
            PICKER_ROUTE_EXPIRED.takeIf {hasDocument}
    }
}
