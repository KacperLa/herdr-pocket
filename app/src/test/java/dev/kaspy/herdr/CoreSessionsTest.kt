package dev.kaspy.herdr

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CoreSessionsTest {
    @Test fun agentCardRemovesOnlyExactStructuredWorkspaceSuffix() {
        for(separator in listOf(" · "," • "," | "," — "," – "," - ")) {
            assertEquals("Implement the small UI change",agentCardDescription(
                "Implement the small UI change${separator}Herdr Pocket","Herdr Pocket",
            ))
        }
        assertNull(agentCardDescription("Herdr Pocket","Herdr Pocket"))
        assertEquals("Switch to Herdr Pocket",agentCardDescription("Switch to Herdr Pocket","Herdr Pocket"))
        assertEquals("Fix Herdr Pocket navigation",agentCardDescription("Fix Herdr Pocket navigation","Herdr Pocket"))
        assertEquals("Audit · Herdr Pocket Test",agentCardDescription("Audit · Herdr Pocket Test","Herdr Pocket"))
        assertEquals("Audit · Herdr Pocket",agentCardDescription("Audit · Herdr Pocket",null))
    }

    @Test fun compactCardDropsDuplicateHeadingAndWorkspaceButKeepsTask() {
        fun desc(value:String)=agentCardDescription(value,"Pocket Space","Jetson AI")
        assertNull(desc("Jetson AI | Pocket Space"))
        assertNull(desc("  jetson ai|POCKET SPACE  "))
        assertEquals("Read logs",desc("Jetson AI | Read logs| Pocket Space"))
        assertEquals("Take Jetson AI to Pocket Space",desc("Take Jetson AI to Pocket Space"))
        assertEquals("Jetson AI related task | Pocket Space backup",
            desc("Jetson AI related task | Pocket Space backup"))
        assertEquals("Filter by | and keep results",desc("Jetson AI • Filter by | and keep results · Pocket Space"))
        assertEquals("Read logs",agentCardDescription("A | B | Read logs | C | D","C | D","A | B"))
    }

    @Test fun sharedRosterQueryUsesVisibleAgentFieldsAndKeepsPriority() {
        val workspace=RosterPlacement("w","Night Build",0,null,null)
        val roster=listOf(
            CoreSession("working","Test runner","live","Codex","working",description="Nightly smoke tests",
                rosterPlacement=workspace),
            CoreSession("shell","Manual shell","live","bash",null,rosterPlacement=workspace,isAgent=false),
            CoreSession("blocked","Release reviewer","live","Claude Code","blocked",
                description="Release reviewer | Fix night build | Night Build",rosterPlacement=workspace,
                compactDescription="Fix night build"),
            CoreSession("idle","Log reader","live","Codex","idle",description="Nightly logs",rosterPlacement=workspace),
        )
        fun agents(query:String)=filteredAgentRoster(roster,query).map {it.address}
        val priority=listOf("blocked","working","idle")
        assertEquals(priority,agents(""))
        assertEquals(priority,agents("  NIGHT   BUILD  "))
        assertEquals(listOf("blocked"),agents("CLAUDE code"))
        assertEquals(listOf("blocked"),agents("night fix"))
        assertEquals(listOf("working"),agents("smoke tests"))
        assertTrue(agents("manual shell").isEmpty())
        assertTrue(agents("night codex").isEmpty()) // No cross-field word fallback.
        assertEquals(listOf("shell"),filteredSpacesRoster(roster,"manual shell",SpaceStatusFilter.ALL)
            .flatMap { it.rows }.map {it.address})
        assertEquals(listOf("idle"),filteredSpacesRoster(roster,"night build",SpaceStatusFilter.IDLE)
            .flatMap { it.rows }.map {it.address})
    }

    @Test fun sharedStatusNarrowsAgentsAfterPrioritySortAndCombinesWithQuery() {
        val roster=listOf(
            CoreSession("older","Build older","live","Codex","working",attentionRecency=1uL),
            CoreSession("blocked","Build blocked","live","Codex","blocked"),
            CoreSession("newer","Build newer","live","Codex","WORKING",attentionRecency=7uL),
            CoreSession("other","Research","live","Codex","working",attentionRecency=8uL),
            CoreSession("shell","Build shell","live","bash","working",isAgent=false),
        )
        fun ids(filter:SpaceStatusFilter,query:String="build")=
            filteredAgentRoster(roster,query,filter).map {it.address}
        assertEquals(listOf("blocked","newer","older"),ids(SpaceStatusFilter.ALL))
        assertEquals(listOf("newer","older"),ids(SpaceStatusFilter.WORKING))
        assertEquals(listOf("other","newer","older"),ids(SpaceStatusFilter.WORKING,""))
        assertEquals(emptyList<String>(),ids(SpaceStatusFilter.IDLE))
    }

    @Test fun parsesPinnedCompactCliFormat() {
        val items=CoreSessions.parse("""{"terminals":[{"address":"t7","name":"review","state":"live","agent":"pi","status":"working"},{"address":"box/t3","state":"live","agent":"bash"}],"hidden_finished":1}""")
        assertEquals(2,items.size)
        assertEquals("box/t3",items[1].address)
        assertEquals("working",items[0].status)
        assertNull(items[1].status)
    }

    @Test fun coreUsesUnassignedSpaceAndReportedTimeWhenWorkspaceAndTuiSequenceAreAbsent() {
        val roster=CoreSessions.parse("""{"terminals":[
            {"address":"t3","name":"shell","state":"live","agent":"bash"},
            {"address":"t1","name":"older","state":"live","status":{"app":"codex","state":"blocked"},"status_ms":10},
            {"address":"t2","name":"newer","state":"live","status":{"app":"codex","state":"blocked"},"status_ms":50}],
            "hidden_finished":0}""")
        assertEquals(listOf("t2","t1"),priorityAgentRoster(roster).map {it.address})
        val space=spacesRoster(roster).single()
        assertEquals("Unassigned",space.label)
        assertEquals(listOf("t3","t1","t2"),space.rows.map {it.address})
    }

    @Test(expected=Exception::class) fun rejectsNonList() {
        CoreSessions.parse("""{"error":"no server"}""")
    }

    @Test fun acceptsEmptyList() {
        assertTrue(CoreSessions.parse("""{"terminals":[],"hidden_finished":0}""").isEmpty())
    }

    @Test fun noninteractiveHomeWithSpacesSupportsListAndAttach() = withShellFixture { path, home ->
        install(File(home, ".local/bin"), "HOME")
        assertEquals(Result(0,"HOME\nls\n--json\n",""), runShell(CoreSessions.listCommand(),path,home))
        val address="box/a'; printf nope; #\$(printf injected)"
        assertEquals(Result(0,"HOME\nattach\n--\n$address\n",""), runShell(CoreSessions.attachCommand(address),path,home))
    }

    @Test fun pathTakesPriorityForListAndAttach() = withShellFixture { path, home ->
        install(path,"PATH")
        install(File(home,".local/bin"),"HOME")
        assertEquals(Result(0,"PATH\nls\n--json\n",""), runShell(CoreSessions.listCommand(),path,home))
        assertEquals(Result(0,"PATH\nattach\n--\nbox/t3\n",""), runShell(CoreSessions.attachCommand("box/t3"),path,home))
    }

    @Test fun missingOrNonExecutableReturns127WithoutJsonNoise() = withShellFixture { path, home ->
        val nonExecutable=File(home,".local/bin/hc").apply { parentFile!!.mkdirs();writeText("not executable") }
        val expected=Result(127,"","Herdr Core (hc) not found on PATH or in ~/.local/bin.\n")
        assertEquals(expected,runShell(CoreSessions.listCommand(),path,home))
        assertTrue(nonExecutable.delete())
        assertEquals(expected,runShell(CoreSessions.attachCommand("box/t3"),path,home))
        assertEquals(expected,runShell(CoreSessions.listCommand(),path,null))
    }

    @Test fun prefersRememberedLiveOverAnUnambiguousMain() {
        val main=CoreSession("t1","main","live","shell",null)
        val chosen=CoreSession("t2","review","live","shell",null)
        assertEquals(chosen, CoreSessions.autoAttach(listOf(main,chosen),chosen.address))
        assertEquals(main, CoreSessions.autoAttach(listOf(main,chosen),"missing"))
        assertEquals(main, CoreSessions.autoAttach(listOf(main,chosen.copy(state="finished")),chosen.address))
    }

    @Test fun selectsOnlySoleOrUnambiguousLiveTargets() {
        val a=CoreSession("t1","first","live","shell",null)
        val b=CoreSession("t2","second","live","shell",null)
        assertEquals(a,CoreSessions.autoAttach(listOf(a,b.copy(state="finished")),null))
        assertNull(CoreSessions.autoAttach(listOf(a,b),null))
        assertNull(CoreSessions.autoAttach(listOf(a.copy(name="main"),b.copy(name="main")),null))
        assertNull(CoreSessions.autoAttach(listOf(a.copy(state="finished")),a.address))
        assertNull(CoreSessions.autoAttach(emptyList(),null))
    }

    @Test fun rememberedKeysSeparateSshEndpointAndLogin() {
        val normalized=HostEndpoint.parse(" BOX.example ","022")!!
        val same=HostEndpoint.parse("box.example","22")!!
        val otherPort=HostEndpoint.parse("box.example","2222")!!
        val key=CoreSessions.rememberedSessionKey(normalized," alice ")
        assertEquals(key,CoreSessions.rememberedSessionKey(same,"alice"))
        assertNotEquals(key,CoreSessions.rememberedSessionKey(same,"bob"))
        assertNotEquals(key,CoreSessions.rememberedSessionKey(otherPort,"alice"))
    }

    @Test fun createUsesBackgroundNoParentAndLiteralProgramArgument() = withShellFixture { path, home ->
        install(File(home, ".local/bin"), "HOME")
        val program="/home/test/bin/codex-plus"
        assertEquals(
            Result(0,"HOME\nrun\n--bg\n--json\n--no-parent\n--name\nreview_2\n--\n$program\n",""),
            runShell(CoreSessions.createCommand("review_2",program),path,home),
        )
    }

    @Test fun launchesConfiguredShellWithNoProgramAndFindsOnlyTheExactLiveName() = withShellFixture { path, home ->
        install(path,"PATH")
        val name="shell-generated"
        assertEquals(Result(0,"PATH\nrun\n--bg\n--json\n--no-parent\n--name\n$name\n",""),
            runShell(CoreSessions.createShellCommand(name),path,home))
        val roster=CoreSessions.parse("""{"terminals":[
            {"address":"t7","name":"$name","state":"live","agent":"bash"},
            {"address":"t8","name":"review","state":"live","status":{"app":"codex","state":"working"}},
            {"address":"t9","name":"$name","state":"finished"}],"hidden_finished":0}""")
        val (agents,terminals)=groupedLiveRoster(roster)
        assertEquals(listOf("t8"),agents.map {it.address})
        assertEquals(listOf("t7"),terminals.map {it.address})
        assertEquals("t7",CoreSessions.createdShell(roster,name)?.address)
        assertNull(CoreSessions.createdShell(roster+roster.first().copy(address="t11"),name))
        assertNull(CoreSessions.createdShell(roster,"shell"))
    }

    @Test fun createRejectsInvalidNamesAndNonExecutableFields() {
        assertTrue(CoreSessions.canCreate("main","/home/me/.local/bin/codex"))
        for(name in listOf("","not valid","Name","a".repeat(33),"a;bad"))
            assertFalse(CoreSessions.canCreate(name,"codex"))
        for(program in listOf("","--bg","codex --flag","codex\nother","codex;echo","tool\$IFS"))
            assertFalse(CoreSessions.canCreate("main",program))
    }

    @Test fun requestsExistingHistoryWhenHcAdvertisesIt() = withShellFixture { path, home ->
        install(File(home, ".local/bin"), "HOME", advertisesHistory=true)
        assertEquals(Result(0,"HOME\nattach\n--history\n1000\n--\nbox/t3\n",""),
            runShell(CoreSessions.attachCommand("box/t3"),path,home))
    }

    private data class Result(val exit:Int,val stdout:String,val stderr:String)

    private fun withShellFixture(test:(File,File)->Unit) {
        val dir=Files.createTempDirectory("herdr-hc").toFile()
        try { test(File(dir,"path bin").apply {mkdir()},File(dir,"home with spaces").apply {mkdir()}) }
        finally {dir.deleteRecursively()}
    }

    private fun install(dir:File,marker:String,advertisesHistory:Boolean=false) {
        dir.mkdirs()
        val stub=File(dir,"hc")
        val help=if(advertisesHistory) "if [ \"\$1\" = attach ] && [ \"\$2\" = --help ]; then printf '%s\\n' '--history <HISTORY>'; exit 0; fi\n" else ""
        stub.writeText("#!/bin/sh\n" + help + "printf '%s\\n' '$marker' \"\$@\"\n")
        assertTrue(stub.setExecutable(true))
    }

    private fun runShell(command:String,path:File,home:File?):Result {
        val process=ProcessBuilder("/bin/sh","-c",command).apply {
            environment().clear()
            environment()["PATH"]=path.absolutePath
            if(home!=null) environment()["HOME"]=home.absolutePath
        }.start()
        try {
            assertTrue("Shell command timed out",process.waitFor(5,TimeUnit.SECONDS))
            return Result(process.exitValue(),process.inputStream.bufferedReader().readText(),process.errorStream.bufferedReader().readText())
        } finally {process.destroyForcibly()}
    }
}
