package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class DictationProviderTest {
    @Test fun savedProviderRestoresWithoutSilentlySelectingAnExternalService() {
        assertEquals(DictationProvider.ANDROID, DictationProvider.fromKey(null))
        assertEquals(DictationProvider.ANDROID, DictationProvider.fromKey("unknown"))
        for (provider in DictationProvider.entries) assertEquals(provider, DictationProvider.fromKey(provider.key))
    }

    @Test fun requiresVerifiedRecordedAudioReleaseAndAnOfflineService() {
        fun issue(version: String?, installed: Boolean = true, service: Boolean = true, internet: Boolean = false) =
            parakeetypeProviderIssue(version, installed, service, internet)

        assertNull(issue("0.6.0"))
        assertNull(issue("0.10.1"))
        assertNull(issue("1.0.0"))
        assertEquals(DictationProviderIssue.PARAKEETYPE_UPDATE, issue("0.5.9"))
        assertEquals(DictationProviderIssue.PARAKEETYPE_UPDATE, issue("0.6.0-beta"))
        assertEquals(DictationProviderIssue.PARAKEETYPE_UPDATE, issue(null))
        assertEquals(DictationProviderIssue.PARAKEETYPE_MISSING, issue(null, installed = false))
        assertEquals(DictationProviderIssue.PARAKEETYPE_INELIGIBLE, issue("0.6.0", service = false))
        assertEquals(DictationProviderIssue.PARAKEETYPE_INELIGIBLE, issue("0.6.0", internet = true))
    }
}
