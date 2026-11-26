package dev.kaspy.herdr

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveNobsHistoryTest {
    @get:Rule val temp=TemporaryFolder()
    private val uid="nobs-123-92abcd"
    private val prompt="Check the synthetic fixture"
    private val provider=LiveStructuredHistory.Provider.NOBS
    private val location=TuiLocation("term_nobs","w1:p2","w1:t2","w1")
    private val cwd by lazy { temp.newFolder("work").canonicalPath }
    private val binding by lazy { LiveStructuredHistory.Binding(provider,uid,cwd) }
    private val db by lazy { File(temp.root,".local/state/nobs/nobs.db") }

    @Test fun acceptsOnlyTheExactCustomReporterAndAbsoluteWorkspace() {
        fun instance(source:String,kind:String="id",id:String=uid,workspace:String?=cwd)=
            LiveAgentHerdr.Instance(location,"nobs",JSONArray(listOf(source,"nobs",kind,id)).toString(),workspace)
        assertEquals(binding,LiveStructuredHistory.binding(instance("custom:nobs")))
        for(bad in listOf(instance("herdr:nobs"),instance("custom:other"),instance("custom:nobs",kind="path"),
            instance("custom:nobs",id="-x"),instance("custom:nobs",id="../../file"),
            instance("custom:nobs",workspace=null),instance("custom:nobs",workspace="relative"))) {
            assertNull(LiveStructuredHistory.binding(bad))
        }
    }

    @Test fun actualReaderGuardsFreshTurnAndSourceDatabaseWithoutLeakingOtherRows() {
        val old=listOf(row("user","earlier-private-user"),row("assistant","earlier-private-answer",stop=true))
        save(db,old)
        val before=read()
        assertTrue(before.sourceKey!!.startsWith("g:"))
        save(db,old+listOf(row("user",prompt),row("assistant","SYNTHETIC TOOL",stop=true,kind="tool_call")))
        val pending=read(before.sourceKey)
        assertEquals(LiveCodexHistory.Match.Waiting,LiveStructuredHistory.match(before,pending,prompt))
        val longAnswer="Named item and route. ".repeat(230).trim()
        save(db,old+listOf(row("user",prompt),row("assistant",longAnswer,stop=true)))
        val (rc,wire)=command(before.sourceKey)
        assertEquals(0,rc)
        assertFalse(wire.contains("earlier-private"))
        assertFalse(wire.contains("FOREIGN-TURN"))
        assertEquals(LiveCodexHistory.Match.Completed(longAnswer),LiveStructuredHistory.match(
            before,checkNotNull(LiveStructuredHistory.parse(binding,wire)),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveStructuredHistory.match(before,read(before.sourceKey),"wrong prompt"))

        val replacement=File(db.parentFile,"moved.db")
        assertTrue(db.renameTo(replacement))
        save(db,old)
        assertNotEquals(0,command(before.sourceKey).first)
        val workspace=File(cwd,".nobs/nobs.db")
        save(workspace,old)
        assertNotEquals(0,command().first)
        assertTrue(db.delete())
        assertTrue(read().sourceKey!!.startsWith("w:"))
        save(workspace,old,repoDir=temp.root.canonicalPath)
        assertNotEquals(0,command().first)
    }

    @Test fun routeUsesHerdrSessionGuardOnlyForTheExactNobsBinding()= runBlocking {
        val shot="Fresh nobs prompt"
        val calls=mutableListOf<String>()
        var source="custom:nobs"
        fun json(type:String):String {
            val agent=JSONObject().put("pane_id",location.paneId).put("terminal_id",location.terminalId)
                .put("workspace_id",location.workspaceId).put("tab_id",location.tabId)
                .put("agent","nobs").put("agent_status","idle").put("state_change_seq",1)
                .put("foreground_cwd",cwd).put("agent_session",JSONObject().put("source",source)
                    .put("agent","nobs").put("kind","id").put("value",uid))
            return JSONObject().put("result",JSONObject().put("type",type).put("agent",agent)).toString()
        }
        val route=LiveAgentHerdr.Route(location,"fixture") { command->
            calls.add(command)
            val text=when {
                "agent get" in command -> json("agent_info")
                "agent read" in command -> shot
                "agent prompt" in command -> json("agent_prompted")
                else -> error("Unexpected test command")
            }
            LiveAgentHerdr.Reply(text,true)
        }
        val expected=checkNotNull(route.current()).let {LiveAgentHerdr.Capture(it,shot)}
        assertTrue(route.submit(expected,prompt)==true)
        assertTrue(calls.single { "agent prompt" in it }.contains("--expect-session="+uid))
        val before=calls.count {"agent prompt" in it}
        source="custom:other"
        assertFalse(route.submit(expected,prompt)==true)
        assertEquals(before,calls.count {"agent prompt" in it})
        assertFalse(LiveAgentHerdr.prompt(location,"fixture",prompt).contains("--expect-session"))
    }

    private fun read(expected:String?=null):LiveStructuredHistory.Page {
        val (exit,wire)=command(expected)
        assertEquals(wire,0,exit)
        return checkNotNull(LiveStructuredHistory.parse(binding,wire))
    }

    private fun command(expected:String?=null):Pair<Int,String> {
        val process=ProcessBuilder("/bin/sh","-c",LiveStructuredHistory.command(binding,expected))
            .redirectErrorStream(true).apply { environment()["HOME"]=temp.root.canonicalPath }.start()
        val text=process.inputStream.bufferedReader().use {it.readText()}
        assertTrue(process.waitFor(6,TimeUnit.SECONDS))
        return process.exitValue() to text
    }

    private fun row(role:String,text:String,stop:Boolean=false,kind:String="text")=
        JSONObject().put("role",role).put("parts",JSONArray().put(JSONObject().put("type",kind).put("text",text)))
            .apply {if(stop) put("finish_reason","stop")}

    private fun save(file:File,input:List<JSONObject>,repoDir:String=cwd) {
        assumeTrue(File("/bin/sh").canExecute())
        file.parentFile.mkdirs()
        val args=listOf("python3","-c","""
            import json, sqlite3, sys
            path,uid,cwd = sys.argv[1:]
            rows = json.load(sys.stdin)
            with sqlite3.connect(path) as c:
                c.executescript('CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,session_id TEXT,message_count INTEGER,repo_dir TEXT);'
                    'CREATE TABLE IF NOT EXISTS session_messages(session_id TEXT,position INTEGER,message_json TEXT,PRIMARY KEY(session_id,position));'
                    'DELETE FROM sessions; DELETE FROM session_messages;')
                c.executemany('INSERT INTO sessions VALUES(?,?,?,?)',[(uid,uid,len(rows),cwd),('foreign','foreign',1,cwd)])
                c.executemany('INSERT INTO session_messages VALUES(?,?,?)', [(uid,i,json.dumps(row)) for i,row in enumerate(rows)])
                c.execute('INSERT INTO session_messages VALUES(?,?,?)',('foreign',0,json.dumps({'role':'user','parts':[{'type':'text','text':'FOREIGN-TURN'}]})))
        """.trimIndent(),file.absolutePath,uid,repoDir)
        val process=ProcessBuilder(args).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use {it.write(JSONArray(input).toString())}
        val result=process.inputStream.bufferedReader().use {it.readText()}
        assertTrue(process.waitFor(6,TimeUnit.SECONDS))
        assertEquals(result,0,process.exitValue())
    }
}
