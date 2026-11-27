package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class SshHostProfilesTest {
    private val first="7c37182d-3034-4363-9530-6c2dcad46e11"
    private val second="fe57fdfe-10ca-4ed1-8a97-9a23fc0528aa"
    @Test fun upgradeKeepsLegacyAndAddsWithoutReplacingIt() {
        val old=SshHostProfiles.decode(null)
        val added=old.add(first)!!
        assertEquals(listOf(SshHostProfiles.LEGACY,first),added.ids)
        assertEquals(old.selected,SshHostProfiles.decode(added.encode()).selected)
        assertEquals(old.selected,added.select(SshHostProfiles.LEGACY)!!.selected)
        assertNull(added.select(second))
        assertNull(added.add(first))
    }

    @Test fun removeSupportsInactiveCurrentAndLastWithoutResurrectingLegacy() {
        val both=SshHostProfiles.decode(null).add(first)!!.add(second)!!.select(second)!!
        val withoutInactive=both.remove(first)!!
        assertEquals(second,withoutInactive.selected)
        val withoutCurrent=withoutInactive.remove(second)!!
        assertNull(withoutCurrent.selected)
        assertEquals(withoutCurrent,SshHostProfiles.decode(withoutCurrent.encode()))
        val empty=withoutCurrent.remove(SshHostProfiles.LEGACY)!!
        assertEquals(SshHostProfiles(emptyList(),null),SshHostProfiles.decode(empty.encode()))
        assertEquals(listOf(first),empty.add(first)!!.ids)
    }

    @Test fun invalidRegistryCannotSelectArbitraryPreferenceBucket() {
        assertEquals(SshHostProfiles(emptyList(),null),
            SshHostProfiles.decode("""{"ids":["legacy","../bad"],"selected":"../bad"}"""))
        assertEquals(SshHostProfiles(emptyList(),null),
            SshHostProfiles.decode("""{"ids":["legacy"],"selected":"$first"}"""))
    }

    @Test fun bucketsAndVaultsAreIsolatedAndLegacyNamesAreUnchanged() {
        assertEquals("workspace",SshHostProfiles.preferenceBucket(SshHostProfiles.LEGACY))
        assertEquals("",SshHostProfiles.vaultSuffix(SshHostProfiles.LEGACY))
        val ids=listOf(null,SshHostProfiles.LEGACY,first,second)
        assertEquals(4,ids.map { SshHostProfiles.preferenceBucket(it) }.toSet().size)
        assertEquals(4,ids.map { SshHostProfiles.vaultSuffix(it) }.toSet().size)
    }

    @Test fun legacyRemovalDoesNotClearGlobalSettings() {
        for (key in listOf("host","port","user","key_document_uri","pin","startup_user","host-pin:example:22",
            "recent-session:example:22:me","agent_pins_v1_x","command","tui_session","connection_label"))
            assertTrue(key,SshHostProfiles.isLegacyHostKey(key))
        for (key in listOf("terminal_font_sp","dictation_press_enter","agent_notifications_enabled","live_profile",
            "ssh_host_profiles_v1","file_view_wrap"))
            assertFalse(key,SshHostProfiles.isLegacyHostKey(key))
    }
}
