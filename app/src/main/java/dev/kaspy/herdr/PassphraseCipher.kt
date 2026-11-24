package dev.kaspy.herdr

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bind one saved key passphrase to exactly one verified SSH/key-file combination. */
data class PassphraseScope(
    val endpoint: HostEndpoint,
    val username: String,
    val keyUri: String,
    val expectedPin: String,
    val keyContentSha256: String,
) {
    internal fun authenticatedData(): ByteArray? {
        val user = username.trim()
        if (user.isBlank() || user.any { it.isISOControl() } ||
            keyUri.isBlank() || keyUri.any { it.isISOControl() } || !isSshSha256Pin(expectedPin) ||
            !isSshSha256Pin(keyContentSha256)
        ) return null
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use { out ->
            listOf("dev.kaspy.herdr.passphrase.v1", endpoint.host, endpoint.port.toString(), user, keyUri, expectedPin, keyContentSha256)
                .forEach { field ->
                    val bytes = field.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
        }
        return data.toByteArray()
    }
}

/** Digest for binding a remembered passphrase to the current content of the chosen key. */
fun keyFileSha256(bytes: ByteArray): String = sshSha256Fingerprint(bytes)

/** Portable record and cipher implementation; production receives a nonexportable AndroidKeyStore key. */
internal object PassphraseCipher {
    internal const val MAX_PASSPHRASE_BYTES = 128 * 1024
    private const val GCM_IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val SCOPE_BYTES = 32
    private const val ALGORITHM = "AES/GCM/NoPadding"

    data class Record(val scopeId: ByteArray, val iv: ByteArray, val encrypted: ByteArray) {
        fun encode(): String = "v1." + listOf(scopeId, iv, encrypted).joinToString(".") {
            Base64.getEncoder().withoutPadding().encodeToString(it)
        }
    }

    fun decode(raw: String): Record? {
        if (raw.length > (MAX_PASSPHRASE_BYTES + TAG_BYTES + GCM_IV_BYTES + SCOPE_BYTES) * 2) return null
        val parts = raw.split('.', limit = 5)
        if (parts.size != 4 || parts[0] != "v1") return null
        return try {
            val decoder = Base64.getDecoder()
            val scopeId = decoder.decode(parts[1])
            val iv = decoder.decode(parts[2])
            val encrypted = decoder.decode(parts[3])
            if (scopeId.size != SCOPE_BYTES || iv.size != GCM_IV_BYTES ||
                encrypted.size !in TAG_BYTES + 1..MAX_PASSPHRASE_BYTES + TAG_BYTES
            ) null else Record(scopeId, iv, encrypted)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun matches(record: Record, aad: ByteArray): Boolean =
        MessageDigest.isEqual(record.scopeId, scopeId(aad))

    fun encrypt(key: SecretKey, aad: ByteArray, passphrase: ByteArray): Record {
        require(passphrase.size in 1..MAX_PASSPHRASE_BYTES)
        val cipher = Cipher.getInstance(ALGORITHM)
        // The provider generates an unpredictable IV. AndroidKeyStore rejects caller IVs on encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES)
        cipher.updateAAD(aad)
        return Record(scopeId(aad), iv, cipher.doFinal(passphrase))
    }

    fun decrypt(key: SecretKey, aad: ByteArray, record: Record): ByteArray? {
        if (!matches(record, aad)) return null
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, record.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(record.encrypted)
    }

    private fun scopeId(aad: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(aad)
}
