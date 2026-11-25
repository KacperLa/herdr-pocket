package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TuiAgentsTest {
    @Test fun parsesStableCliEnvelopeAndSkipsPendingAgents() {
        val json="""{"id":"cli:agent:list","result":{"type":"agent_list","agents":[
          {"terminal_id":"term_abc","name":"reviewer","agent":"codex","agent_status":"working"},
          {"terminal_id":"term_bcd","title":"second","agent_status":"unknown","unknown_field":{}},
          {"terminal_id":"term_def","launch_pending":true}
        ]}}"""
        assertEquals(
            listOf(CoreSession("term_abc","reviewer","live","Codex","working",tuiAgentName="reviewer"),
                CoreSession("term_bcd","Unnamed agent","live","","unknown",description="second",isAgent=true)),
            TuiAgents.parse(json),
        )
        assertTrue(TuiAgents.parse("""{"result":{"type":"agent_list","agents":[]}}""").isEmpty())
    }

    @Test fun separatesExplicitNamesDescriptionsRunnersPathsAndRoutingIds() {
        val result=TuiAgents.parse("""{"result":{"type":"agent_list","agents":[
          {"terminal_id":"term_a","name":"release reviewer","display_agent":"CC","title":"Check the candidate","agent":"claude","cwd":"/home/casey/alpha","foreground_cwd":"/home/casey/alpha/pkg","agent_status":"blocked"},
          {"terminal_id":"term_b","name":" ","display_agent":"Refactor planner","title":"Untangle startup","agent":"codex","foreground_cwd":"/home/casey/beta","agent_status":"working"},
          {"terminal_id":"term_c","display_agent":"Codex","agent":"codex","terminal_title_stripped":"Fix the camera","terminal_title":"✦ Fix the camera","agent_status":"done"},
          {"terminal_id":"term_d","title":"Fix offline tests","agent":"claude","agent_status":"idle"},
          {"terminal_id":"term_e","agent":"pi","terminal_title":"Plan"},
          {"terminal_id":"term_f","agent":"codex","display_agent":"Refactor planner"},
          {"terminal_id":"term_g","name":"","terminal_title":"\u001b\n"},
          {"terminal_id":"term_h","display_agent":"An integration label but not a name"}
        ]}}""")
        assertEquals(listOf("release reviewer","Codex agent","Codex agent","Claude Code agent",
            "Pi agent","Codex agent","Unnamed agent","Unnamed agent"), result.map { it.name })
        assertEquals(listOf("Check the candidate","Untangle startup","Fix the camera","Fix offline tests",
            "Plan","Refactor planner",null,"An integration label but not a name"),result.map {it.description})
        assertEquals(('a'..'h').map { "term_$it" }, result.map { it.address })
        assertEquals("Claude Code",result[0].agent)
        assertEquals("Codex",result[1].agent)
        assertEquals("/home/casey/alpha",result[0].workingDirectory)
        assertEquals("/home/casey/beta",result[1].workingDirectory)
        assertNull(result[2].workingDirectory)
        assertEquals(listOf(TuiAgents.Status.BLOCKED,TuiAgents.Status.WORKING,TuiAgents.Status.DONE,
            TuiAgents.Status.IDLE),result.take(4).map {TuiAgents.status(it.status)})
        assertEquals(TuiAgents.Status.UNKNOWN,TuiAgents.status(null))
        assertEquals(TuiAgents.Status.UNKNOWN,TuiAgents.status("new-status"))
    }

    @Test fun compactDescriptionsCleanEachSourceBeforeChoosingButKeepSpacesMetadata() {
        val result=TuiAgents.parse("""{"result":{"type":"session_snapshot","snapshot":{
          "workspaces":[{"workspace_id":"w","label":"Pocket Space"}],
          "agents":[
            {"terminal_id":"term_a","pane_id":"p1","tab_id":"t","workspace_id":"w","name":"Jetson AI","agent":"codex",
             "title":"Jetson AI | Pocket Space","terminal_title_stripped":"Jetson AI | Inspect CI| pocket space"},
            {"terminal_id":"term_b","pane_id":"p2","tab_id":"t","workspace_id":"w","name":"Kit","agent":"claude",
             "title":"Kit | Pocket Space","terminal_title":"Claude Code | Pocket Space","tokens":{"summary":"Look for regressions"}},
            {"terminal_id":"term_c","pane_id":"p3","tab_id":"t","workspace_id":"w","name":"Scout","agent":"codex",
             "title":"Scout | Pocket Space","terminal_title":"Scout","display_agent":"Codex"},
            {"terminal_id":"term_d","pane_id":"p4","tab_id":"t","workspace_id":"w","name":"Review","agent":"codex",
             "title":"Help Review move to Pocket Space"}
          ]}}}""")
        assertEquals(listOf("Jetson AI","Kit","Scout","Review"),result.map {it.name})
        assertEquals(List(4) {"Pocket Space"},result.map {it.rosterPlacement?.workspaceLabel})
        assertEquals(listOf("Inspect CI","Look for regressions",null,"Help Review move to Pocket Space"),
            result.map {it.compactDescription})
        assertEquals("Jetson AI | Pocket Space",result[0].description)
    }

    @Test fun bothRostersRenderEveryFreshStatusAsReportedByHerdr() {
        for ((sequence,status) in listOf("working","done","blocked","working","done","idle").withIndex()) {
            val response="""{"result":{"type":"session_snapshot","snapshot":{
                "workspaces":[{"workspace_id":"w","label":"Work"}],
                "agents":[{"terminal_id":"term_agent","workspace_id":"w","tab_id":"t","pane_id":"p",
                    "agent":"codex","agent_status":"$status","state_change_seq":${sequence+1},
                    "completion_seq":${if(status=="done") sequence+1 else "null"}}]
            }}}"""
            val roster=AgentBackend.TUI.parse(response)
            assertEquals(status,filteredAgentRoster(roster,"").single().status)
            assertEquals(status,filteredSpacesRoster(roster,"",SpaceStatusFilter.ALL).single().rows.single().status)
        }
    }

    @Test fun refreshFindsRenamedActiveTerminalByOpaqueIdNotByOldNameOrDescription() {
        fun roster(name:String)=TuiAgents.parse("""{"result":{"type":"agent_list","agents":[
          {"terminal_id":"term_a","name":"Old name","agent":"codex"},
          {"terminal_id":"term_b","name":"$name","title":"Same description","agent":"codex"}
        ]}}""")
        assertEquals("Old name",TuiAgents.nameForTerminal(roster("Old name"),"term_b"))
        assertEquals("Renamed agent",TuiAgents.nameForTerminal(roster("Renamed agent"),"term_b"))
        assertEquals("Old name",TuiAgents.nameForTerminal(roster("Renamed agent"),"term_a"))
        assertNull(TuiAgents.nameForTerminal(roster("Renamed agent"),"term_gone"))
    }

    @Test fun waitsForTheCompleteInitialRepaintEvenWhenItIsBlankOrFragmented() {
        val start="\u001b[?2026h\u001b[?25l\u001b]8;;\u001b\\\u001b[2J"
        val end="\u001b[?2026l"
        val startup="\u001b[?1000l\u001b[?1016\$p\u001b[c\u001b[?1049h"
        val blank=start+"\u001b[1;1H\u001b[?25h"+end
        val frame=TuiAttachFrame()
        assertFalse(frame.observe((startup+"\u001b[2J"+"\u001b[?2026h\u001b[?25lno baseline"+end).toByteArray()))
        val bytes=blank.toByteArray()
        for (i in bytes.indices) {
            assertEquals(i==bytes.lastIndex,frame.observe(bytes,i,1))
        }
        assertTrue(frame.observe(byteArrayOf()))
    }

    @Test fun alternateFirstFrameOrderingFromOlderHerdr() {
        val frame=TuiAttachFrame()
        assertFalse(frame.observe("\u001b[?25l\u001b[?2026h\u001b]8;;\u001b\\".toByteArray()))
        assertTrue(frame.observe("\u001b[2J\u001b[H\u001b[?2026l".toByteArray()))
    }

    @Test fun ignoresPreAttachTerminalModesClearAndRefusalText() {
        val frame=TuiAttachFrame()
        val setup="\u001b[?1000l\u001b[?1006l\u001b[?1049h\u001b[2J\u001b[c"
        val refuse="herdr: server shut down: terminal already has an attached client"
        assertFalse(frame.observe((setup+refuse+"\u001b[?1049l").toByteArray()))
        assertFalse(frame.observe("\u001b[?2026l".toByteArray()))
    }

    @Test fun incompatibleMalformedOrUntrustedTargetsFailClosed() {
        for (payload in listOf("[]","{}", """{"error":{"code":"server_not_running"}}""",
            """{"result":{"type":"terminal_list","agents":[]}}""",
            """{"result":{"type":"agent_list","agents":[{"terminal_id":"--takeover"}]}}""")) {
            assertTrue(runCatching { TuiAgents.parse(payload) }.isFailure)
        }
        for (target in listOf("--takeover","term_123;id","term_'","term_\nabc","")) {
            assertTrue(runCatching { TuiAgents.attachCommand(target) }.isFailure)
        }
    }

    @Test fun cliUsesOneReadOnlySnapshotAndSameNamedSessionWithoutTakeover() {
        val root=Files.createTempDirectory("herdr tui ").toFile()
        try {
            val app=root.resolve(".local/bin/herdr")
            checkNotNull(app.parentFile).mkdirs()
            app.writeText("""
                #!/bin/sh
                printf '%s\n' "${'$'}*" >> "${'$'}HOME/calls"
                if [ "${'$'}1" = '--session' ]; then shift 2; fi
                case "${'$'}1 ${'$'}2" in
                    'api snapshot')
                        [ "${'$'}FIXTURE" = legacy ] && exit 1
                        printf '%s\n' '{"result":{"type":"session_snapshot","snapshot":{"agents":[]}}}' ;;
                    'agent list') printf '%s\n' '{"result":{"type":"agent_list","agents":[{"terminal_id":"term_abc","agent":"codex","pane_id":"p","workspace_id":"w","tab_id":"t"}]}}' ;;
                    'pane list') printf '%s\n' '{"result":{"type":"pane_list","panes":[{"terminal_id":"term_abc","pane_id":"p","workspace_id":"w","tab_id":"t","label":"Jetson AI"}]}}' ;;
                    'tab list') printf 'not JSON\n'; exit 1 ;;
                    'workspace list') printf '%s\n' '{"result":{"type":"workspace_list","workspaces":[]}}' ;;
                    'terminal attach') printf '%s\n' "${'$'}@" ;;
                    *) exit 2 ;;
                esac
            """.trimIndent()+"\n")
            assertTrue(app.setExecutable(true))
            fun run(command:String,fixture:String="snapshot"):Pair<String,List<String>> {
                val calls=root.resolve("calls")
                calls.delete()
                val p=ProcessBuilder("/bin/sh","-c",command)
                p.environment()["PATH"]=root.resolve("no-interactive-path").path
                p.environment()["HOME"]=root.path
                p.environment()["FIXTURE"]=fixture
                val process=p.start()
                assertTrue(process.waitFor(3,TimeUnit.SECONDS))
                assertEquals(0,process.exitValue())
                return process.inputStream.bufferedReader().readText() to calls.readLines()
            }
            run(TuiAgents.listCommand()).let { (data,calls) ->
                assertEquals(listOf("api snapshot"),calls)
                assertTrue(TuiAgents.parse(data).isEmpty())
            }
            run(TuiAgents.listCommand("coding.dev")).let { (data,calls) ->
                assertEquals(listOf("--session coding.dev api snapshot"),calls)
                assertTrue(TuiAgents.parse(data).isEmpty())
            }
            run(TuiAgents.listCommand("coding.dev"),fixture="legacy").let { (data,calls) ->
                assertEquals(listOf("api snapshot","agent list","pane list","tab list","workspace list")
                    .map {"--session coding.dev $it"},calls)
                assertEquals("Jetson AI",TuiAgents.parse(data).single().name)
            }
            assertEquals(listOf("--session coding.dev terminal attach term_abc"),
                run(TuiAgents.attachCommand("term_abc","coding.dev")).second)
            assertTrue(runCatching {TuiAgents.listCommand("--socket")}.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun snapshotUsesExactPaneTabAndWorkspaceRenameLabelsAndExcludesAutoTitles() {
        val payload="""{"id":"cli:api:snapshot","result":{"type":"session_snapshot","snapshot":{
          "version":"0.9.5","protocol":22,
          "workspaces":[
            {"workspace_id":"w1","label":"Jetson AI"},
            {"workspace_id":"w2","label":"myrepo","worktree":{"repo_name":"myrepo","repo_root":"/src/myrepo"}},
            {"workspace_id":"w3","label":"~"}
          ],
          "tabs":[
            {"tab_id":"w1:t9","workspace_id":"w1","number":9,"label":"1"},
            {"tab_id":"w1:t2","workspace_id":"w1","number":2,"label":"Release console"},
            {"tab_id":"w1:t3","workspace_id":"w1","number":3,"label":"8"},
            {"tab_id":"w2:t4","workspace_id":"w2","number":4,"label":"1"},
            {"tab_id":"w3:t1","workspace_id":"w3","number":1,"label":"1"}
          ],
          "panes":[
            {"pane_id":"p0","terminal_id":"term_0","tab_id":"w1:t2","workspace_id":"w1","label":"Pane alias"},
            {"pane_id":"p1","terminal_id":"term_1","tab_id":"w1:t2","workspace_id":"w1","label":"Research agent"},
            {"pane_id":"p2","terminal_id":"term_2","tab_id":"w1:t2","workspace_id":"w1"},
            {"pane_id":"p3","terminal_id":"term_3","tab_id":"w1:t9","workspace_id":"w1","cwd":"/home/example/src"},
            {"pane_id":"p4","terminal_id":"stale_terminal","tab_id":"w2:t4","workspace_id":"w2","label":"Wrong terminal"},
            {"pane_id":"p5","terminal_id":"term_5","tab_id":"w1:t3","workspace_id":"w1"},
            {"pane_id":"p6","terminal_id":"term_6","tab_id":"w3:t1","workspace_id":"w3"}
          ],
          "agents":[
            {"terminal_id":"term_0","pane_id":"p0","tab_id":"w1:t2","workspace_id":"w1","name":"explicit_name","agent":"codex"},
            {"terminal_id":"term_1","pane_id":"p1","tab_id":"w1:t2","workspace_id":"w1","title":"Long task","agent":"codex"},
            {"terminal_id":"term_2","pane_id":"p2","tab_id":"w1:t2","workspace_id":"w1","agent":"codex"},
            {"terminal_id":"term_3","pane_id":"p3","tab_id":"w1:t9","workspace_id":"w1","agent":"codex","cwd":"/home/example/src"},
            {"terminal_id":"term_4","pane_id":"p4","tab_id":"w2:t4","workspace_id":"w2","agent":"codex"},
            {"terminal_id":"term_5","pane_id":"p5","tab_id":"w1:t3","workspace_id":"w1","agent":"codex"},
            {"terminal_id":"term_6","pane_id":"p6","tab_id":"w3:t1","workspace_id":"w3","agent":"codex"}
          ]
        }}}"""
        val roster=TuiAgents.parse(payload)
        assertEquals(listOf("explicit_name","Research agent","Release console","Jetson AI","Codex agent","8","Codex agent"),
            roster.filter {it.isAgent}.map {it.name})
        assertEquals("explicit_name",roster.first().tuiAgentName)
        assertNull(roster[1].tuiAgentName)
        assertTrue(roster.first().spacesName.orEmpty().contains("Pane alias"))
        assertEquals("stale_terminal",roster.single {!it.isAgent}.address)
        assertEquals("Long task",roster[1].description)
        assertEquals("Codex · /home/example/src",TuiAgents.runnerAndPath(roster[3]))
        assertEquals("Codex",TuiAgents.runnerAndPath(roster[2]))
        assertEquals("/home/example/src",TuiAgents.runnerAndPath(roster[3].copy(agent="")))
        assertNull(TuiAgents.runnerAndPath(roster[2].copy(agent="")))
        assertEquals("term_3",roster[3].address)
        assertEquals("Jetson AI 2",TuiAgents.nameForTerminal(TuiAgents.parse(payload.replace("Jetson AI","Jetson AI 2")),"term_3"))
    }

    @Test fun standaloneTerminalsRemainSelectableAndMoveToAgentsByExactIdentityOnRefresh() {
        val shell="""{"terminal_id":"term_shell","pane_id":"w:p1","tab_id":"w:t1","workspace_id":"w","cwd":"/src"}"""
        val standalone="""{"terminal_id":"term_other","pane_id":"w2:p1","tab_id":"w2:t1","workspace_id":"w2","label":"Logs"}"""
        val agent="""{"terminal_id":"term_shell","pane_id":"w:p1","tab_id":"w:t1","workspace_id":"w","name":"now-coding","agent":"codex"}"""
        fun snapshot(agents:String) = """{"result":{"type":"session_snapshot","snapshot":{
            "agents":[$agents],"panes":[$shell,$standalone,$shell]}}}"""
        val original=TuiAgents.parse(snapshot(""))
        val (beforeAgents,beforeShells)=groupedLiveRoster(original)
        assertTrue(beforeAgents.isEmpty())
        assertEquals(listOf("term_shell","term_other"),beforeShells.map {it.address})
        assertEquals("Logs",beforeShells[1].name)
        val id=Any()
        assertEquals(AgentSelection.RESUME_CURRENT,agentSelection(beforeShells.first(),original,"term_shell",id,id))
        val current=TuiAgents.parse(snapshot(agent))
        val (agents,shells)=groupedLiveRoster(current)
        assertEquals(listOf("now-coding"),agents.map {it.name})
        assertEquals(listOf("term_other"),shells.map {it.address})
        assertEquals(AgentSelection.RESUME_CURRENT,agentSelection(agents.single(),current,"term_shell",id,id))
    }

    @Test fun legacyUsesPaneListWithoutRequiringRecognizedAgents() {
        val legacy="""{"pocket_legacy":true,
            "agent_list":{"result":{"type":"agent_list","agents":[]}},
            "pane_list":{"result":{"type":"pane_list","panes":[
                {"terminal_id":"term_shell","pane_id":"p","tab_id":"t","workspace_id":"w","label":"Standalone"}]}}}"""
        val shell=TuiAgents.parse(legacy).single()
        assertFalse(shell.isAgent)
        assertEquals("Standalone",shell.name)
        assertEquals(TuiLocation("term_shell","p","t","w"),shell.tuiLocation)
    }

    @Test fun spacesFollowsWorkspaceTabAndSplitPaneSnapshotOrderNotPriorityOrNumericIds() {
        val payload="""{"result":{"type":"session_snapshot","snapshot":{
          "workspaces":[{"workspace_id":"w8","label":"Projects"},{"workspace_id":"w1","label":"Projects"}],
          "tabs":[{"workspace_id":"w8","tab_id":"w8:t7","number":7},{"workspace_id":"w1","tab_id":"w1:t3","number":3},
                  {"workspace_id":"w8","tab_id":"w8:t2","number":2}],
          "panes":[
            {"workspace_id":"w1","tab_id":"w1:t3","pane_id":"w1:p1","terminal_id":"term_last"},
            {"workspace_id":"w8","tab_id":"w8:t7","pane_id":"w8:p4","terminal_id":"term_plain","label":"Shell"},
            {"workspace_id":"w8","tab_id":"w8:t2","pane_id":"w8:p1","terminal_id":"term_after_tab"},
            {"workspace_id":"w8","tab_id":"w8:t7","pane_id":"w8:p2","terminal_id":"term_agent"}
          ],
          "agents":[{"workspace_id":"w8","tab_id":"w8:t7","pane_id":"w8:p2","terminal_id":"term_agent",
                     "name":"runner","agent":"codex","agent_status":"blocked"}]
        }}}"""
        val roster=TuiAgents.parse(payload)
        val spaces=spacesRoster(roster)
        assertEquals(listOf("w8","w1"),spaces.map {it.id})
        assertEquals(listOf("Projects","Projects"),spaces.map {it.label})
        assertEquals(listOf("term_plain","term_agent","term_after_tab"),spaces[0].rows.map {it.address})
        assertEquals(listOf("term_last"),spaces[1].rows.map {it.address})
        assertEquals(listOf("term_agent"),priorityAgentRoster(roster).map {it.address})
        assertSame(roster.first(),spaces[0].rows[1])
    }

    @Test fun priorityMatchesAttentionOrderThenNewestChangesAndKeepsStableTies() {
        val namedStates=listOf(
            Triple("unknown","unknown","99"),Triple("working_older","working","5"),Triple("idle","idle","60"),
            Triple("done_old","done","44"),Triple("blocked","blocked","1"),
            Triple("done_recent","done","18446744073709551615"),Triple("working_tie","working","5"),
            Triple("working_recent","working","6"),Triple("working_legacy","working","null"),
        )
        val agents=namedStates.joinToString(",") { (name,status,seq) ->
            """{"terminal_id":"term_$name","name":"$name","agent_status":"$status","state_change_seq":$seq}"""
        }
        val order=priorityAgentRoster(TuiAgents.parse("""{"result":{"type":"agent_list","agents":[$agents]}}"""))
        assertEquals(listOf("blocked","done_recent","done_old","working_recent","working_older","working_tie",
            "working_legacy","idle","unknown"),order.map {it.name})
    }

    @Test fun spacesCanInferLegacyTabSequenceFromPanesWithoutInterleavingSplitTabs() {
        val legacy="""{"pocket_legacy":true,
            "agent_list":{"result":{"type":"agent_list","agents":[]}},
            "pane_list":{"result":{"type":"pane_list","panes":[
                {"terminal_id":"a","pane_id":"p3","tab_id":"t9","workspace_id":"w"},
                {"terminal_id":"b","pane_id":"p2","tab_id":"t9","workspace_id":"w"},
                {"terminal_id":"c","pane_id":"p1","tab_id":"t1","workspace_id":"w"}]}}}"""
        val space=spacesRoster(TuiAgents.parse(legacy)).single()
        assertEquals("w",space.id)
        assertEquals(listOf("a","b","c"),space.rows.map {it.address})
    }

    @Test fun unnamedFirstTabKeepsTheSameSpacesLabelWhenAnotherTabIsAdded() {
        fun label(withSecondTab:Boolean, firstLabel:String=""):String? {
            val first="""{"workspace_id":"w","tab_id":"t9"$firstLabel}"""
            val tabs=if(withSecondTab) """$first,{"workspace_id":"w","tab_id":"t2"}""" else first
            val payload="""{"result":{"type":"session_snapshot","snapshot":{
              "workspaces":[{"workspace_id":"w","label":"Projects"}],
              "tabs":[$tabs],
              "panes":[{"workspace_id":"w","tab_id":"t9","pane_id":"p","terminal_id":"shell"}],
              "agents":[]
            }}}"""
            return spacesRoster(TuiAgents.parse(payload)).single().rows.single().spacesName
        }
        assertEquals("Terminal · 1",label(false))
        assertEquals(label(false),label(true))
        assertEquals("Dev shell",label(false,""", "label":"Dev shell","custom_label":true"""))
    }

    @Test fun goToLabelsFollowNamedTabsAndSplitPositionsWithoutChangingAgentIdentity() {
        val payload="""{"result":{"type":"session_snapshot","snapshot":{
          "workspaces":[{"workspace_id":"w8","label":"Projects","branch":"feature/test"}],
          "tabs":[{"workspace_id":"w8","tab_id":"w8:t9","label":"Release"},
                  {"workspace_id":"w8","tab_id":"w8:t3","label":"2"},
                  {"workspace_id":"w8","tab_id":"w8:t1","label":"3"}],
          "panes":[
            {"workspace_id":"w8","tab_id":"w8:t9","pane_id":"w8:p4","terminal_id":"term_shell","label":"Manual shell","cwd":"/repos","foreground_cwd":"/repos/release"},
            {"workspace_id":"w8","tab_id":"w8:t9","pane_id":"w8:p2","terminal_id":"term_agent"},
            {"workspace_id":"w8","tab_id":"w8:t3","pane_id":"w8:p1","terminal_id":"term_tasks"},
            {"workspace_id":"w8","tab_id":"w8:t1","pane_id":"w8:p7","terminal_id":"term_logs"}
          ],
          "agents":[
            {"workspace_id":"w8","tab_id":"w8:t9","pane_id":"w8:p2","terminal_id":"term_agent",
             "name":"review","agent":"codex","agent_status":"blocked"},
            {"workspace_id":"w8","tab_id":"w8:t3","pane_id":"w8:p1","terminal_id":"term_tasks",
             "title":"Dev tasks","agent":"claude","agent_status":"working"}
          ]
        }}}"""
        val roster=TuiAgents.parse(payload)
        val space=spacesRoster(roster).single()
        assertEquals(listOf("Release · Manual shell · 1","Release · review · 2","Dev tasks","Terminal · 3"),
            space.rows.map {it.spacesName})
        assertEquals("/repos/release",space.rows.first().spacesPath)
        assertEquals("review",priorityAgentRoster(roster).first().name)
        assertSame(priorityAgentRoster(roster).first(),space.rows[1])
        assertEquals("Release",space.rows[1].rosterPlacement?.tabLabel)
        assertEquals(2,space.rows[1].rosterPlacement?.paneOrdinal)
        assertEquals("feature/test",space.rows[1].rosterPlacement?.workspaceBranch)
        fun search(query:String,filter:SpaceStatusFilter=SpaceStatusFilter.ALL)=
            filteredSpacesRoster(roster,query,filter).flatMap {it.rows}.map {it.address}
        assertEquals(listOf("term_shell","term_agent"),search("RELEASE release"))
        assertEquals(listOf("term_agent"),search("projects",SpaceStatusFilter.BLOCKED))
        assertEquals(listOf("term_shell"),search("/repos/release"))
        assertEquals(listOf("term_tasks"),search("dev tasks",SpaceStatusFilter.WORKING))
        assertTrue(search("release projects").isEmpty())
        assertEquals(space.rows.map {it.address},search("feature/test"))
        assertTrue(search("unmatched",SpaceStatusFilter.ALL).isEmpty())
        assertTrue(search("",SpaceStatusFilter.IDLE).isEmpty())
    }

    @Test fun stagedSwitchChecksTheBinaryVersionWithoutChangingNormalAttach() {
        val root=Files.createTempDirectory("herdr tui ").toFile()
        try {
            val app=root.resolve(".local/bin/herdr")
            checkNotNull(app.parentFile).mkdirs()
            app.writeText("""
                #!/bin/sh
                if [ "${'$'}1" = '--version' ]; then
                    printf '%s\n' "${'$'}FIXTURE_VERSION"
                    exit 0
                fi
                printf '%s\n' "${'$'}@"
            """.trimIndent()+"\n")
            assertTrue(app.setExecutable(true))
            fun run(version:String,command:String,expectedExit:Int):List<String> {
                val pb=ProcessBuilder("/bin/sh","-c",command).redirectErrorStream(true)
                pb.environment().apply {
                    put("PATH",root.resolve("no-path").path)
                    put("HOME",root.path)
                    put("FIXTURE_VERSION",version)
                }
                val process=pb.start()
                assertTrue(process.waitFor(3,TimeUnit.SECONDS))
                assertEquals(version,expectedExit,process.exitValue())
                return process.inputStream.bufferedReader().readLines()
            }
            val command=AgentBackend.TUI.switchCommand("term_abc","office")
            val actualArgv=listOf("--session","office","terminal","attach","term_abc")
            for (version in listOf("herdr 0.6.6","herdr 0.7.2","herdr 0.9.5","herdr 0.9.5-preview.23")) {
                assertEquals(actualArgv,run(version,command,0))
            }
            for (version in listOf("","herdr 0.6.5","unrelated-0.9.5","herdr 1.0.0","herdr 0.9.x")) {
                assertEquals(listOf("HERDR_POCKET_UNVERIFIED_DIRECT_ATTACH"),run(version,command,78))
            }
            assertEquals(actualArgv,run("herdr 0.6.5",TuiAgents.attachCommand("term_abc","office"),0))
            assertEquals(CoreSessions.attachCommand("box/t4"),AgentBackend.CORE.switchCommand("box/t4"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun doesNotConfuseBackendsOrNamedSessionsOnReconnect() {
        val endpoint=HostEndpoint.parse("example.invalid","22")!!
        val profile=ReconnectProfile(endpoint,"me","fingerprint","content://key")
        val core=AgentBackend.CORE.rememberedKey(endpoint,"me")
        val tui=AgentBackend.TUI.rememberedKey(endpoint,"me")
        assertNotEquals(core,tui)
        assertNotEquals(tui,AgentBackend.TUI.rememberedKey(endpoint,"me","office"))
        val target=ReconnectTarget(profile,ReconnectMode.AgentSession(true,"term_abc",AgentBackend.TUI,"office"))
        assertTrue(target.isAvailable(profile,false,"","office"))
        assertFalse(target.isAvailable(profile,false,""))
        assertFalse(target.isAvailable(profile,false,"","home"))
    }

    @Test fun agentOwnershipAndUnavailableServerHaveSafeDiagnostics() {
        assertTrue(TuiAgents.attachFailure(
            "herdr: server shut down: terminal attach failed: terminal term_abc already has an attached client; retry with --takeover".toByteArray(),
        ).orEmpty().contains("No takeover was attempted."))
        assertTrue(AgentBackend.TUI.listFailure(1,"server_not_running".toByteArray()).contains("No Herdr TUI server"))
        assertTrue(AgentBackend.TUI.listFailure(127,byteArrayOf()).contains("not found"))
        assertTrue(AgentBackend.TUI.listFailure(1,"protocol_mismatch".toByteArray()).contains("different versions"))
    }
}
