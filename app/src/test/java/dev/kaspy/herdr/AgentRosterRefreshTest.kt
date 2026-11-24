package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class AgentRosterRefreshTest {
    @Test fun onlyVisibleConnectedIdleRosterAcceptsGestureOrAccessibilityAction() {
        for (visible in listOf(false,true)) for (connected in listOf(false,true))
            for (target in listOf(false,true)) for (pending in listOf(false,true))
                for (dialog in listOf(false,true)) {
                    val expected=visible && connected && target && !pending && !dialog
                    assertEquals(expected,canRefreshAgentRoster(visible,connected,target,pending,dialog))
                }
    }

    @Test fun onlyTheTrackedRosterReadControlsTheIndicator() {
        val idle=AgentRosterRefresh()
        val reading=AgentRosterRefresh(13)
        assertFalse(idle.active)
        assertEquals(idle,idle.complete(22))
        assertTrue(reading.active)
        assertEquals(reading,reading.complete(12))
        assertEquals(idle,reading.complete(13))
        assertFalse(reading.complete(13).active)
    }

    @Test fun healthyRosterHasNoHeadingAndActionFeedbackAndEmptyStateStillAppear() {
        assertEquals("",agentRosterNotice(3,"Herdr TUI",null,null))
        assertEquals("No live Herdr Core sessions found.",agentRosterNotice(0,"Herdr Core",null,null))
        assertEquals("Pane renamed.",agentRosterNotice(3,"Herdr TUI","Pane renamed.",null))
        assertEquals("Agent started in the new tab.",agentRosterNotice(0,"Herdr TUI","Agent started in the new tab.",null))
        assertEquals("Check the tab.",agentRosterNotice(2,"Herdr TUI",null,"Check the tab."))
    }
}
