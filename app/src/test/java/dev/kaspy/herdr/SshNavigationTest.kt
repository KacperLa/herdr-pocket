package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class SshNavigationTest {
    @Test fun terminalBackOpensAgentsThenAgentBackReturnsToConnections() {
        assertEquals(TerminalBackDestination.AGENTS,terminalBackDestination(hasAgentsView=true))
        assertEquals(SshNavigation.Connections,SshNavigation.Session.back())
        assertEquals(SshNavigation.Connections,SshNavigation.Settings.back())
    }

    @Test fun appSettingsBackReturnsToConnectionsJustLikeHostSettings() {
        assertEquals(SshNavigation.Connections,SshNavigation.AppSettings.back())
    }

    @Test fun customTerminalBackReturnsToConnectionsWithoutInventingAnAgentsList() {
        assertEquals(TerminalBackDestination.CONNECTIONS,terminalBackDestination(hasAgentsView=false))
    }

    @Test fun backingOutOfAddOrEditReturnsToTheList() {
        assertEquals(SshNavigation.Connections,SshNavigation.Edit(null).back())
        assertEquals(SshNavigation.Connections,SshNavigation.Edit("saved-profile").back())
    }

    @Test fun connectedHerdrHostOpensRosterInsteadOfConnecting() {
        assertEquals(SavedHostPrimaryAction.OPEN_AGENTS,savedHostPrimaryAction(
            isSelected=true,connectionOpen=true,hasAgentsView=true))
    }

    @Test fun connectedCustomTerminalOpensWithoutConnecting() {
        assertEquals(SavedHostPrimaryAction.OPEN_TERMINAL,savedHostPrimaryAction(
            isSelected=true,connectionOpen=true,hasAgentsView=false))
    }

    @Test fun otherHostsAndDroppedConnectionsNeedExplicitConnect() {
        assertEquals(SavedHostPrimaryAction.CONNECT,savedHostPrimaryAction(
            isSelected=false,connectionOpen=true,hasAgentsView=true))
        assertEquals(SavedHostPrimaryAction.CONNECT,savedHostPrimaryAction(
            isSelected=true,connectionOpen=false,hasAgentsView=true))
    }
}
