package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentFilesTest {
    @Test fun uploadPickerIsBoundToTheExactDirectory() {
        val saved = FilesUploadAnchor("/work", listOf("one"), "/work/one")
        assertTrue(saved.matches("/work", listOf("one"), "/work/one"))
        assertFalse(saved.matches("/work", listOf("two"), "/work/two"))
        assertFalse(saved.matches("/other", listOf("one"), "/other/one"))
        assertFalse(saved.matches("/work", listOf("one"), null))
        assertFalse(saved.matches("/work", listOf("one"), "/work/One"))
    }

    @Test fun reportsAnInterruptedPickerDifferentlyFromAnUnconfirmedTransfer() {
        assertEquals(FilesUploadInterruption.CONNECTION_LOST_BEFORE_TRANSFER,
            FilesUploadInterruption.onConnectionLoss(active=true, transportRequested=false))
        assertEquals(FilesUploadInterruption.CONNECTION_LOST_DURING_TRANSFER,
            FilesUploadInterruption.onConnectionLoss(active=true, transportRequested=true))
        assertNull(FilesUploadInterruption.onConnectionLoss(active=false, transportRequested=false))
        assertEquals(FilesUploadInterruption.PICKER_ROUTE_EXPIRED,
            FilesUploadInterruption.onPickerWithoutRoute(hasDocument=true))
        assertNull(FilesUploadInterruption.onPickerWithoutRoute(hasDocument=false))
    }

    @Test fun newFolderRequiresOneLiteralNonblankName() {
        for (name in listOf("Work files", "literal [1]*?", " trailing space ", "📁")) {
            assertTrue(name, AgentFiles.newFolderName(name))
        }
        for (name in listOf("", "   ", ".", "..", "nested/folder", "line\nbreak", "x".repeat(1025))) {
            assertFalse(name, AgentFiles.newFolderName(name))
        }
    }

    private fun snapshot(version: String, rows: String, panes: String = "") = """
        {"result":{"type":"session_snapshot","snapshot":{"version":"$version","agents":[$rows],"panes":[$panes]}}}
    """.trimIndent()

    private val shell = """{"terminal_id":"term_shell","workspace_id":"w1","tab_id":"t1","pane_id":"p1",
        "cwd":"/project","foreground_cwd":"/project/after cd","focused":false}"""

    private val first = """{"terminal_id":"term_a","cwd":"/workspace","foreground_cwd":"/workspace/actual project "}"""
    private val second = """{"terminal_id":"term_b","cwd":"/other","foreground_cwd":"/other"}"""

    @Test fun choosesExactAgentAndRawLivePath() {
        val json = snapshot("0.9.5", "$first,$second")
        assertEquals(AgentFilesRoot("/workspace/actual project ", false), AgentFiles.root(json, "term_a"))
        assertEquals(AgentFilesRoot("/other", false), AgentFiles.root(json, "term_b"))
        assertNull(AgentFiles.root(json, "missing"))
        assertNull(AgentFiles.root(snapshot("0.9.5", "$first,$first"), "term_a"))
    }

    @Test fun labelsOlderAndMissingLivePathAsWorkspaceFallback() {
        assertEquals(AgentFilesRoot("/workspace", true), AgentFiles.root(snapshot("0.8.2", first), "term_a"))
        assertEquals(AgentFilesRoot("/workspace", true), AgentFiles.root(snapshot("0.9.0-rc1", first), "term_a"))
        assertEquals(AgentFilesRoot("/workspace", true),
            AgentFiles.root(snapshot("0.9.5", """{"terminal_id":"term_a","cwd":"/workspace"}"""), "term_a"))
        val legacy = """{"pocket_legacy":true,"agent_list":{"result":{"type":"agent_list","agents":[$first]}}}"""
        assertEquals(AgentFilesRoot("/workspace", true), AgentFiles.root(legacy, "term_a"))
        assertNull(AgentFiles.root(snapshot("0.9.5", """{"terminal_id":"term_a","cwd":"relative"}"""), "term_a"))
    }

    @Test fun resolvesAgentlessShellFromTheExactPaneAfterCd() {
        val otherFocused="""{"terminal_id":"term_other","cwd":"/other","foreground_cwd":"/other/live","focused":true}"""
        val current=snapshot("0.9.5",first,"$otherFocused,$shell")
        assertEquals(AgentFilesRoot("/project/after cd",false),AgentFiles.root(current,"term_shell"))
        assertEquals(AgentFilesRoot("/workspace/actual project ",false),AgentFiles.root(current,"term_a"))
        assertNull(AgentFiles.root(current,"term_unknown"))
        assertNull(AgentFiles.root(snapshot("0.9.5",first,"$shell,$shell"),"term_shell"))
        assertNull(AgentFiles.root(snapshot("0.9.5","",shell.replace("/project","relative")),"term_shell"))
    }

    @Test fun olderOrUnversionedShellUsesExplicitPaneFolderNeverUnverifiedForeground() {
        assertEquals(AgentFilesRoot("/project",true),AgentFiles.root(snapshot("0.8.2","",shell),"term_shell"))
        val legacy="""{"pocket_legacy":true,
            "agent_list":{"result":{"type":"agent_list","agents":[]}},
            "pane_list":{"result":{"type":"pane_list","panes":[$shell]}}}"""
        assertEquals(AgentFilesRoot("/project",true),AgentFiles.root(legacy,"term_shell"))
        assertNull(AgentFiles.root(legacy.replace("pane_list\",\"panes", "agent_list\",\"panes"),"term_shell"))
    }

    @Test fun onlyReconcilesAgentAndPaneRowsThatAgreeOnTheirRoutingIdentity() {
        val matching="""{"terminal_id":"term_shell","workspace_id":"w1","tab_id":"t1","pane_id":"p1",
            "cwd":"/old","foreground_cwd":"/old/path"}"""
        assertEquals(AgentFilesRoot("/project/after cd",false),AgentFiles.root(snapshot("0.9.5",matching,shell),"term_shell"))
        assertNull(AgentFiles.root(snapshot("0.9.5",matching.replace("p1","other"),shell),"term_shell"))
        assertNull(AgentFiles.root(snapshot("0.9.5",matching.replace("\"cwd\"", "\"launch_pending\":true,\"cwd\""),shell),"term_shell"))
    }

    @Test fun rejectsTraversalAndQuotesRemoteGlobs() {
        for (name in listOf("", ".", "..", "a/b", "\u0000", "\u202e")) assertFalse(AgentFiles.entryName(name))
        assertNull(AgentFiles.absolute("/workspace/../other"))
        assertFalse(AgentFiles.contains("/root", "/roots/x"))
        assertTrue(AgentFiles.contains("/root", "/root/x"))
        assertEquals("/root/a\\*b\\?c\\\\d", AgentFiles.literal("/root/a*b?c\\d"))
    }

    @Test fun routeReplacementCancelsAnUncommittedSaveButNotAnAlreadyFinishedSave() {
        val stale=FileSaveGate()
        assertTrue(stale.canWrite)
        assertTrue(stale.cancel())
        assertFalse(stale.canWrite)
        assertFalse(stale.commit())
        assertFalse(stale.committed)
        val prior=FileSaveGate()
        assertTrue(prior.commit())
        assertFalse(prior.cancel())
        assertFalse(prior.canWrite)
        assertTrue(prior.committed)
    }

    @Test fun backReturnsOneLayerAtATime() {
        assertEquals(FilesBackAction.MENU, filesBackAction(false, false, true, false))
        assertEquals(FilesBackAction.MENU, filesBackAction(true, false, false, false))
        assertEquals(FilesBackAction.PARENT, filesBackAction(true, true, false, false))
        assertEquals(FilesBackAction.FOLDER_CONTENTS, filesBackAction(true, false, false, true))
        assertEquals(FilesBackAction.FOLDER_CONTENTS, filesBackAction(true, true, false, true))
        assertEquals(FilesBackAction.CANCEL_READ, filesBackAction(true, true, true, false))
    }
}
