package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSelectionTest {
    private val active = CoreSession("n1","main","live","codex",null)
    private val other = CoreSession("n2","other","live","shell",null)
    private val pty = Any()

    @Test fun currentLiveRowResumesItsExistingPtyInsteadOfSwitching() {
        assertEquals(AgentSelection.RESUME_CURRENT, agentSelection(active,listOf(active,other),active.address,pty,pty))
    }
    @Test fun renamedCurrentRowStillResumesSamePtyById() {
        val renamed=active.copy(name="Renamed",description="Unrelated task")
        assertEquals(AgentSelection.RESUME_CURRENT,agentSelection(renamed,listOf(renamed,other),active.address,pty,pty))
    }

    @Test fun anotherLiveRowStillSwitches() {
        assertEquals(AgentSelection.SWITCH, agentSelection(other,listOf(active,other),active.address,pty,pty))
    }
    @Test fun staleOrFinishedRowsNeverResumeOrStart() {
        val finished=active.copy(state="finished")
        assertEquals(AgentSelection.UNAVAILABLE, agentSelection(active,listOf(other),active.address,pty,pty))
        assertEquals(AgentSelection.UNAVAILABLE, agentSelection(finished,listOf(finished),active.address,pty,pty))
    }
    @Test fun goneOrReplacedPtyNeverResumesOrSwitches() {
        for (row in listOf(active,other)) {
            assertEquals(AgentSelection.UNAVAILABLE, agentSelection(row,listOf(active,other),active.address,pty,null))
            assertEquals(AgentSelection.UNAVAILABLE, agentSelection(row,listOf(active,other),active.address,pty,Any()))
            assertEquals(AgentSelection.UNAVAILABLE, agentSelection(row,listOf(active,other),null,pty,pty))
        }
    }
}
