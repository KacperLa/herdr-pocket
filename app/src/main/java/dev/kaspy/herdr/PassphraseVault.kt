package dev.kaspy.herdr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * One optional remembered passphrase per SSH connection. Call save only after successful SSH authentication
 * and explicit user opt-in. No passphrase, reusable key file, or exportable AES key is stored.
 * Use a background dispatcher for read/save/clear; wipe returned arrays after use.
 */
class PassphraseVault(context: Context, connectionId: String? = SshHostProfiles.LEGACY) {
    private val suffix=SshHostProfiles.vaultSuffix(connectionId)
    private val keyAlias=KEY_ALIAS+suffix
    private val prefs = context.applicationContext.getSharedPreferences(PREFS+suffix, Context.MODE_PRIVATE)

    fun hasSaved(): Boolean = synchronized(lock) {
        runCatching { stored() != null }.getOrDefault(false)
    }

    fun has(scope: PassphraseScope): Boolean = synchronized(lock) {
        val aad = scope.authenticatedData() ?: return@synchronized false
        runCatching { stored()?.let { PassphraseCipher.matches(it, aad) } == true }.getOrDefault(false)
    }

    fun read(scope: PassphraseScope): ByteArray? = synchronized(lock) {
        val aad = scope.authenticatedData() ?: return@synchronized null
        try {
            val record = stored() ?: return@synchronized null
            if (!PassphraseCipher.matches(record, aad)) return@synchronized null
            val key = existingKey(keyStore())
            if (key == null) {
                removeRecord()
                return@synchronized null
            }
            PassphraseCipher.decrypt(key, aad, record)
        } catch (e: Exception) {
            if (e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
                e is UnrecoverableKeyException
            ) runCatching { removeRecord() }
            null
        }
    }

    fun save(scope: PassphraseScope, passphrase: ByteArray): Boolean = synchronized(lock) {
        val aad = scope.authenticatedData() ?: return@synchronized false
        if (passphrase.size !in 1..PassphraseCipher.MAX_PASSPHRASE_BYTES) return@synchronized false
        try {
            val store = keyStore()
            val sealed = try {
                PassphraseCipher.encrypt(existingKey(store) ?: generateKey(), aad, passphrase)
            } catch (e: Exception) {
                if (e !is KeyPermanentlyInvalidatedException && e !is UnrecoverableKeyException) throw e
                store.deleteEntry(keyAlias)
                PassphraseCipher.encrypt(generateKey(), aad, passphrase)
            }
            prefs.edit().putString(RECORD, sealed.encode()).commit()
        } catch (_: Exception) {
            false
        }
    }

    /** Forget this connection’s remembered passphrase, including their local app-owned Keystore key. */
    fun clear(): Boolean = synchronized(lock) {
        val erased = runCatching { removeRecord() }.getOrDefault(false)
        val keyErased = runCatching {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            true
        }.getOrDefault(false)
        // Either confirmed action makes the old passphrase unrecoverable by this app.
        erased || keyErased
    }

    private fun stored(): PassphraseCipher.Record? =
        prefs.getString(RECORD, null)?.let(PassphraseCipher::decode)

    private fun removeRecord(): Boolean = prefs.edit().remove(RECORD).commit()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(store: KeyStore): SecretKey? = store.getKey(keyAlias, null) as? SecretKey

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generateKey()
    }

    private companion object {
        val lock = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.kaspy.herdr.passphrase.aes.v1"
        const val PREFS = "encrypted_key_passphrase"
        const val RECORD = "record"
    }
}
