package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TuiMutationsTest {
    private val loc=TuiLocation("term_one","w1:p2","w1:t3","w1")
    private val fields=""""terminal_id":"term_one","pane_id":"w1:p2","tab_id":"w1:t3","workspace_id":"w1""""
    private fun response(type:String,key:String,value:String) =
        """{"result":{"type":"$type","$key":$value}}"""

    @Test fun paneRenameRequiresExactAgentLocationAndServerConfirmation() {
        val renamed=response("pane_info","pane","""{$fields,"label":"Jetson AI"}""")
        assertTrue(TuiMutations.confirmsPane(renamed,loc,"Jetson AI"))
        assertFalse(TuiMutations.confirmsPane(renamed,loc.copy(terminalId="term_other")))
        assertFalse(TuiMutations.confirmsPane(renamed,loc.copy(tabId="w1:t4")))
        assertFalse(TuiMutations.confirmsPane(renamed,loc,"different label"))
        assertFalse(TuiMutations.confirmsPane("""{"error":{"code":"pane_not_found"}}""",loc))
        assertFalse(TuiMutations.confirmsPane("not JSON",loc))
    }

    @Test fun tabRenameChecksThePaneThenConfirmsOnlyTheSameTabsHostLabel() {
        val target=TuiMutations.RenameTarget.TAB
        val before=response("pane_info","pane","""{$fields}""")
        assertTrue(TuiMutations.confirmsTarget(before,loc,target))
        assertFalse(TuiMutations.confirmsTarget(before,loc.copy(tabId="w1:t4"),target))
        val tab=response("tab_info","tab","""{"tab_id":"w1:t3","workspace_id":"w1","label":"New tab name"}""")
        assertTrue(TuiMutations.confirmsTarget(tab,loc,target,"New tab name"))
        assertFalse(TuiMutations.confirmsTarget(tab,loc.copy(workspaceId="w9"),target,"New tab name"))
        assertFalse(TuiMutations.confirmsTarget(tab,loc.copy(tabId="w1:t4"),target,"New tab name"))
        assertFalse(TuiMutations.confirmsTarget(tab,loc,target,"Old name"))
        assertFalse(TuiMutations.confirmsTarget(before,loc,target,"New tab name"))
        assertFalse(TuiMutations.confirmsTarget(tab,loc,TuiMutations.RenameTarget.AGENT,"New tab name"))
        assertTrue(TuiMutations.canRenameTarget("New tab name",target))
        assertFalse(TuiMutations.canRenameTarget("New tab name",TuiMutations.RenameTarget.AGENT))
    }

    @Test fun agentRenameRequiresTheExactAgentNameFieldAndRoute() {
        val renamed=response("agent_info","agent","""{$fields,"name":"release-reviewer","agent":"codex"}""")
        assertTrue(TuiMutations.confirmsRenameTarget(renamed,loc,true))
        assertTrue(TuiMutations.confirmsRenameTarget(renamed,loc,true,"release-reviewer"))
        assertFalse(TuiMutations.confirmsRenameTarget(renamed,loc,true,"old-name"))
        for(wrong in listOf(loc.copy(terminalId="different"),loc.copy(paneId="w1:p3"),
            loc.copy(tabId="w1:t8"),loc.copy(workspaceId="w8"))) {
            assertFalse(TuiMutations.confirmsRenameTarget(renamed,wrong,true,"release-reviewer"))
        }
        val pane=response("pane_info","pane","""{$fields,"label":"release-reviewer"}""")
        assertFalse(TuiMutations.confirmsRenameTarget(pane,loc,true,"release-reviewer"))
        assertTrue(TuiMutations.confirmsRenameTarget(pane,loc,false,"release-reviewer"))
        assertFalse(TuiMutations.confirmsRenameTarget(renamed.replaceFirst("name","label"),loc,true,"release-reviewer"))
        assertFalse(TuiMutations.confirmsRenameTarget(renamed.replaceFirst("agent\":\"codex", "launch_pending\":true,\"agent\":\"codex"),loc,true))
        assertEquals("That agent name is already in use in Herdr.",
            TuiMutations.renameRejection("", """{"error":{"code":"agent_name_taken","message":"details"}}"""))
        assertNull(TuiMutations.renameRejection("no matching error"))
        assertTrue(TuiMutations.canRenameSelection("release-reviewer_2",true))
        assertFalse(TuiMutations.canRenameSelection("Release Reviewer",true))
        assertTrue(TuiMutations.canRenameSelection("Release Reviewer",false))
        for(bad in listOf("", "1agent", "--clear", "Upper", "x;id", "a".repeat(33))) {
            assertFalse(TuiMutations.canRenameSelection(bad,true))
            assertTrue(runCatching {TuiMutations.renameSelection(loc,true,bad,null)}.isFailure)
        }
    }

    @Test fun creationUsesExactReturnedNewPaneAndAgentAndOnlySupportedVersion() {
        val created="""{"result":{"type":"workspace_created","workspace":{"workspace_id":"w1"},"tab":{"tab_id":"w1:t3","workspace_id":"w1"},"root_pane":{$fields}}}"""
        assertEquals(loc,TuiMutations.newShellPane(created,null))
        assertNull(TuiMutations.newShellPane(created.replaceFirst("workspace_created","tab_created"),null))
        assertNull(TuiMutations.newShellPane(created.replaceFirst("w1","w2"),null))
        assertNull(TuiMutations.newShellPane(created.replaceFirst("w1:t3","w1:t4"),null))
        assertNull(TuiMutations.newShellPane(created.replace("root_pane","other_pane"),null))
        val tab=created.replaceFirst("workspace_created","tab_created")
        assertEquals(loc,TuiMutations.newShellPane(tab,"w1"))
        assertNull(TuiMutations.newShellPane(tab,"w2"))
        assertNull(TuiMutations.newShellPane(created,"w1"))
        assertFalse(TuiMutations.confirmsPane(response("pane_info","pane","""{$fields}"""),
            loc.copy(workspaceId="w2")))
        val started=response("agent_started","agent","""{$fields,"name":"jetson"}""")
        assertTrue(TuiMutations.confirmsStart(started,loc,"jetson"))
        assertFalse(TuiMutations.confirmsStart(started,loc,"another"))
        assertFalse(TuiMutations.confirmsStart(started,loc.copy(terminalId="term_other"),"jetson"))
        fun version(tag:String)="""{"result":{"type":"session_snapshot","snapshot":{"version":"$tag"}}}"""
        for(tag in listOf("0.7.5","0.8.2","0.9.5","0.9.5-preview.2")) assertTrue(TuiMutations.canCreateFromRoster(version(tag)))
        for(tag in listOf("0.6.6","0.7.4","1.0.0","unknown")) assertFalse(TuiMutations.canCreateFromRoster(version(tag)))
        assertFalse(TuiMutations.canCreateFromRoster("""{"result":{"type":"agent_list","agents":[]}}"""))
    }

    @Test fun chooserUsesSnapshotWorkspaceIdsNotLabelsOrTheActivePane() {
        val snapshot=response("session_snapshot","snapshot","""{"workspaces":[
            {"workspace_id":"w2","label":"Same project"},
            {"workspace_id":"w1","label":"Same project"},
            {"workspace_id":"w3","label":"   "},
            {"workspace_id":"w4","label":"duplicate"},{"workspace_id":"w4","label":"ambiguous"},
            {"workspace_id":"--bad","label":"Invalid"}
        ]}""")
        assertEquals(listOf(TuiWorkspaceChoice("w2","Same project"),TuiWorkspaceChoice("w1","Same project"),
            TuiWorkspaceChoice("w3","w3")), TuiMutations.workspaceChoices(snapshot))
        assertTrue(TuiMutations.workspaceChoices("not a snapshot").isEmpty())
    }

    @Test fun workspaceCreationKeepsNamedSessionAndDoesNotSpawnAnAgentOrStealFocus() {
        val root=Files.createTempDirectory("herdr friendly name").toFile()
        try {
            val cli=root.resolve(".local/bin/herdr")
            checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nif [ \"\$1\" = --version ]; then printf 'herdr %s\\n' \"\$FIXTURE\"; else printf '%s\\n' \"\$@\"; fi\n")
            assertTrue(cli.setExecutable(true))
            fun run(cmd:String,tag:String="0.9.5",exit:Int=0):List<String> {
                val p=ProcessBuilder("/bin/sh","-c",cmd).redirectErrorStream(true)
                p.environment()["HOME"]=root.path
                p.environment()["PATH"]=root.resolve("no-path").path
                p.environment()["FIXTURE"]=tag
                val proc=p.start()
                assertTrue(proc.waitFor(3,TimeUnit.SECONDS))
                assertEquals(exit,proc.exitValue())
                return proc.inputStream.bufferedReader().readLines()
            }
            assertEquals(listOf("--session","work.local","workspace","create","--no-focus"),
                run(TuiMutations.createShellWorkspace("work.local")))
            assertEquals(listOf("workspace","create","--no-focus"),
                run(TuiMutations.createShellWorkspace(null),"0.7.5"))
            assertEquals(listOf("HERDR_POCKET_AGENT_START_UNSUPPORTED"),
                run(TuiMutations.createShellWorkspace(null),"0.7.4",78))
            assertEquals(listOf("--session","work.local","tab","create","--workspace","w2","--no-focus"),
                run(TuiMutations.createShellTab("w2","work.local")))
            assertEquals(listOf("--session","work.local","terminal","attach","term_one"),
                run(AgentBackend.TUI.switchCommand(loc.terminalId,"work.local")))
            val label="Jetson's AI; \$(printf EXTRA)"
            val scope=listOf("--session","work.local")
            assertEquals(scope+listOf("agent","get","w1:p2"),
                run(TuiMutations.inspectRenameTarget(loc,true,"work.local")))
            assertEquals(scope+listOf("agent","rename","w1:p2","release-reviewer"),
                run(TuiMutations.renameSelection(loc,true,"release-reviewer","work.local")))
            assertEquals(scope+listOf("pane","rename","w1:p2",label),
                run(TuiMutations.renameSelection(loc,false,label,"work.local")))
            assertEquals(scope+listOf("pane","get","w1:p2"),run(TuiMutations.inspectPane(loc,"work.local")))
            assertEquals(scope+listOf("pane","rename","w1:p2",label),run(TuiMutations.renamePane(loc,label,"work.local")))
            assertEquals(scope+listOf("tab","create","--workspace","w1","--label","jetson","--no-focus"),
                run(TuiMutations.createTab("w1","jetson","work.local")))
            assertEquals(listOf("HERDR_POCKET_AGENT_START_UNSUPPORTED"),
                run(TuiMutations.createTab("w1","jetson",null),"0.7.4",78))
            assertEquals(scope+listOf("agent","start","jetson","--kind","codex","--pane","w1:p2","--timeout","18000"),
                run(TuiMutations.startAgent("jetson",TuiMutations.Runner.CODEX,loc,"work.local")))
            for(unsafe in listOf("--clear","--session=other","bad\nname","", "   ")) {
                assertFalse(TuiMutations.canRename(unsafe))
            }
            assertTrue(runCatching {TuiMutations.renamePane(loc.copy(paneId="other;format"),"Normal",null)}.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
