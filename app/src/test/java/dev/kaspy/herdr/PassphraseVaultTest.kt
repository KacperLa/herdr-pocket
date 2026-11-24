package dev.kaspy.herdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class PassphraseVaultTest {
    private val pin = sshSha256Fingerprint("fixture host wire public key".toByteArray())
    private val anotherPin = sshSha256Fingerprint("different fixture host wire public key".toByteArray())
    private val digest = keyFileSha256("fixture key bytes".toByteArray())
    private val scope = PassphraseScope(
        requireNotNull(HostEndpoint.parse("fixture.example", "22")),
        "fixture-user", "content://fixture/document/key", pin, digest
    )
    private val syntheticPassphrase = "synthetic fixture text".toByteArray()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripAndFreshIvDoNotStorePlaintext() {
        val aad = requireNotNull(scope.authenticatedData())
        val first = PassphraseCipher.encrypt(key, aad, syntheticPassphrase)
        val second = PassphraseCipher.encrypt(key, aad, syntheticPassphrase)
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.encode().contains(syntheticPassphrase.toString(Charsets.UTF_8)))
        val record = requireNotNull(PassphraseCipher.decode(first.encode()))
        assertEquals(first.encode(), record.encode())
        assertArrayEquals(syntheticPassphrase, PassphraseCipher.decrypt(key, aad, record))
    }

    @Test fun everyScopeComponentMustMatchAndMatchingKeyContentIsRequired() {
        val aad = requireNotNull(scope.authenticatedData())
        val record = PassphraseCipher.encrypt(key, aad, syntheticPassphrase)
        val variants = listOf(
            scope.copy(endpoint = requireNotNull(HostEndpoint.parse("another.example", "22"))),
            scope.copy(endpoint = requireNotNull(HostEndpoint.parse("fixture.example", "2222"))),
            scope.copy(username = "another-user"),
            scope.copy(keyUri = "content://fixture/document/another"),
            scope.copy(expectedPin = anotherPin),
            scope.copy(keyContentSha256 = keyFileSha256("different bytes at the same URI".toByteArray()))
        )
        variants.forEach {
            val changed = requireNotNull(it.authenticatedData())
            assertFalse(PassphraseCipher.matches(record, changed))
            assertNull(PassphraseCipher.decrypt(key, changed, record))
        }
        val normalized = scope.copy(endpoint = requireNotNull(HostEndpoint.parse(" FiXtUrE.example ", "022")),
            username = " fixture-user ")
        assertTrue(PassphraseCipher.matches(record, requireNotNull(normalized.authenticatedData())))
    }

    @Test(expected = AEADBadTagException::class)
    fun forgingScopeIdCannotMakeCiphertextOpenAgainstDifferentAad() {
        val aad = requireNotNull(scope.authenticatedData())
        val changed = requireNotNull(scope.copy(expectedPin = anotherPin).authenticatedData())
        val record = PassphraseCipher.encrypt(key, aad, syntheticPassphrase)
        val forged = record.copy(scopeId = PassphraseCipher.encrypt(key, changed, syntheticPassphrase).scopeId)
        assertTrue(PassphraseCipher.matches(forged, changed))
        PassphraseCipher.decrypt(key, changed, forged)
    }

    @Test fun malformedBindingsAndRecordsAreRejected() {
        assertNull(scope.copy(expectedPin = "").authenticatedData())
        assertNull(scope.copy(keyContentSha256 = "not a digest").authenticatedData())
        assertNull(scope.copy(username = " ").authenticatedData())
        assertNull(scope.copy(keyUri = "").authenticatedData())
        assertNull(PassphraseCipher.decode("v0.aaaa.aaaa.aaaa"))
        assertNull(PassphraseCipher.decode("v1.*.aaaa.aaaa"))
        assertNull(PassphraseCipher.decode("v1.aaaa.aaaa.aaaa"))
        assertNull(PassphraseCipher.decode("v1.aaaa.aaaa.aaaa.trailing"))
    }
}
