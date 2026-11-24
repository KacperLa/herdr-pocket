package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentCardSummaryTest {
    private val row=CoreSession("terminal","Refactor agent","live","codex","working")

    @Test fun showsOnlyDescriptionAndBestReportedWorkingPath() {
        val session=row.copy(description="Legacy description",compactDescription="Fix parser",
            spacesPath="/home/me/src/app",workingDirectory="/home/me")
        assertEquals(AgentCardSummary("Fix parser","/home/me/src/app"),agentCardSummary(session))
        assertEquals(AgentCardSummary("Fix parser","/home/me"),agentCardSummary(session.copy(spacesPath=" ")))
    }

    @Test fun hidesDuplicateSummaryAndDoesNotInventPath() {
        for(text in listOf(null," "," CODEX ","refactor AGENT"," /project ")) {
            assertEquals(AgentCardSummary(null,"/project"),
                agentCardSummary(row.copy(compactDescription=text,workingDirectory="/project")))
        }
        assertEquals(AgentCardSummary(null,null),agentCardSummary(row))
        assertEquals(AgentCardSummary("Update /project config","/project"),
            agentCardSummary(row.copy(compactDescription="Update /project config",workingDirectory="/project")))
    }
}
