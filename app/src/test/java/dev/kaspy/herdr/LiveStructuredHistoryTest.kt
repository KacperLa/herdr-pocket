package dev.kaspy.herdr

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveStructuredHistoryTest {
    @get:Rule val home=TemporaryFolder()
    private val claude=LiveStructuredHistory.Binding(LiveStructuredHistory.Provider.CLAUDE,"12345678-1234-4abc-9234-123456789123")
    private val openCode=LiveStructuredHistory.Binding(LiveStructuredHistory.Provider.OPENCODE,"ses_abcdef123")
    private val pane=TuiLocation("term_example","w1:p1","w1:t1","w1")
    private val prompt="What changed?"
    private val answer="The test passed. Want more detail?"

    @Test fun onlyOfficialHerdrReferencesAreAccepted() {
        for((kind,binding) in listOf("claude" to claude,"opencode" to openCode)) {
            fun instance(source:String,id:String,type:String="id")=LiveAgentHerdr.Instance(pane,kind,
                JSONArray(listOf(source,kind,type,id)).toString())
            assertEquals(binding,LiveStructuredHistory.binding(instance("herdr:$kind",binding.id)))
            assertNull(LiveStructuredHistory.binding(instance("custom:$kind",binding.id)))
            assertNull(LiveStructuredHistory.binding(instance("herdr:$kind","../../private")))
            assertNull(LiveStructuredHistory.binding(instance("herdr:$kind",binding.id,"path")))
        }
    }

    @Test fun claudeReadsOnlyTheBoundFileAndRequiresACompletedLinkedAnswer() {
        assumeTrue(File("/bin/sh").canExecute())
        val project=home.newFolder("projects","example")
        val file=File(project,claude.id+".jsonl")
        val historic=claudeRow("user","old",null,"Earlier prompt")+"\n"+
            claudeRow("assistant","old-a","old","Earlier answer","end_turn")+"\n"
        file.writeText(historic)
        val before=read(claude)
        val pending=claudeRow("user","new","old-a",prompt)+"\n"+
            claudeRow("assistant","new-tool","new","", "tool_use","tool_use")+"\n"+
            claudeRow("user","result","new-tool","private tool data",part="tool_result")+"\n"
        file.appendText(pending)
        assertEquals(LiveCodexHistory.Match.Waiting,LiveStructuredHistory.match(before,read(claude),prompt))
        file.appendText(claudeRow("assistant","branch","old","wrong branch","end_turn")+"\n")
        assertEquals(LiveCodexHistory.Match.Waiting,LiveStructuredHistory.match(before,read(claude),prompt))
        file.writeText(historic+pending+claudeRow("assistant","new-a","result",answer,"end_turn")+"\n")
        assertEquals(LiveCodexHistory.Match.Completed(answer),LiveStructuredHistory.match(before,read(claude),prompt))
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveStructuredHistory.match(before,read(claude),"Wrong prompt"))
        file.appendText(claudeRow("user","second","new-a",prompt)+"\n")
        assertEquals(LiveCodexHistory.Match.Uncertain,LiveStructuredHistory.match(before,read(claude),prompt))
        val duplicate=home.newFolder("projects","other")
        File(duplicate,file.name).writeText(historic)
        assertNotEquals(0,command(claude).first)
    }

    @Test fun openCodeUsesProviderExportAndDoesNotSpeakProgressOrWrongSession() {
        assumeTrue(File("/bin/sh").canExecute())
        val bin=home.newFolder("bin")
        val source=File(home.root,"export.json")
        File(bin,"opencode").apply {
            writeText("""#!/bin/sh
                |[ "$#" = 3 ] && [ "$1" = session ] && [ "$2" = export ] && [ "$3" = '${openCode.id}' ] || exit 2
                |exec cat "${'$'}HOME/export.json"
            """.trimMargin())
            setExecutable(true)
        }
        val old=openRow("user","msg_1",null,"old")
        val u=openRow("user","msg_2",null,prompt)
        val tool=openRow("assistant","msg_3","msg_2","private tool data","tool-calls","tool")
        val final=openRow("assistant","msg_4","msg_2",answer,"stop")
        fun save(vararg messages:JSONObject,id:String=openCode.id) = source.writeText(JSONObject()
            .put("info",JSONObject().put("id",id)).put("messages",JSONArray(messages.toList())).toString())
        save(old)
        val before=read(openCode)
        save(old,u,tool)
        assertEquals(LiveCodexHistory.Match.Waiting,LiveStructuredHistory.match(before,read(openCode),prompt))
        save(old,u,tool,final)
        val (exit,wire)=command(openCode)
        assertEquals(0,exit)
        assertFalse(wire.contains("private tool data"))
        assertEquals(LiveCodexHistory.Match.Completed(answer),LiveStructuredHistory.match(before,read(openCode),prompt))
        save(old,u,openRow("assistant","msg_4","msg_1",answer,"stop"))
        assertNull(LiveStructuredHistory.parse(openCode,command(openCode).second))
        save(old,u,final,id="ses_wrong")
        assertNotEquals(0,command(openCode).first)
    }

    private fun read(binding:LiveStructuredHistory.Binding):LiveStructuredHistory.Page {
        val (exit,output)=command(binding)
        assertEquals(output,0,exit)
        return checkNotNull(LiveStructuredHistory.parse(binding,output))
    }

    private fun command(binding:LiveStructuredHistory.Binding):Pair<Int,String> {
        val p=ProcessBuilder("/bin/sh","-c",LiveStructuredHistory.command(binding)).apply {
            environment()["CLAUDE_CONFIG_DIR"]=home.root.absolutePath
            environment()["HOME"]=home.root.absolutePath
            environment()["PATH"]=File(home.root,"bin").absolutePath+":"+(environment()["PATH"] ?: "/usr/bin:/bin")
        }.redirectErrorStream(true).start()
        try {
            assertTrue(p.waitFor(12,TimeUnit.SECONDS))
            return p.exitValue() to p.inputStream.bufferedReader().readText()
        } finally {p.destroyForcibly()}
    }

    private fun claudeRow(role:String,id:String,parent:String?,text:String,stop:String?=null,part:String="text") =
        JSONObject().put("uuid",id).put("type",role).put("parentUuid",parent ?: JSONObject.NULL).put("sessionId",claude.id)
            .put("message",JSONObject().put("role",role).put("stop_reason",stop ?: JSONObject.NULL)
                .put("content",JSONArray().put(JSONObject().put("type",part).put("text",text)))).toString()

    private fun openRow(role:String,id:String,parent:String?,text:String,finish:String?=null,part:String="text") =
        JSONObject().put("info",JSONObject().put("role",role).put("id",id).put("sessionID",openCode.id)
            .put("parentID",parent ?: JSONObject.NULL).put("finish",finish ?: JSONObject.NULL)
            .put("time",JSONObject().put("completed",10)))
            .put("parts",JSONArray().put(JSONObject().put("type",part).put("messageID",id)
                .put("sessionID",openCode.id).put("text",text)))
}
