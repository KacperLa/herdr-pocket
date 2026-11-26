package dev.kaspy.herdr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Encrypt endpoint-specific bearer credentials; swapping endpoints cannot forward an old key. */
internal object LiveSecretStore {
    private const val PROVIDER="AndroidKeyStore"
    private const val ALIAS="dev.kaspy.herdr.live.voice.aes.v1"
    private fun aad(scope:String)=("pocket-live.v1\n"+scope).toByteArray(Charsets.UTF_8)
    private fun store()=KeyStore.getInstance(PROVIDER).apply {load(null)}
    private fun key(store:KeyStore)=store.getKey(ALIAS,null) as? SecretKey

    @Synchronized fun seal(scope:String,secret:String):String? = runCatching {
        require(LiveExtractionClient.validKey(secret))
        val key=key(store()) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,PROVIDER).run {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        val bytes=secret.toByteArray(Charsets.UTF_8)
        try {PassphraseCipher.encrypt(key,aad(scope),bytes).encode()} finally {bytes.fill(0)}
    }.getOrNull()

    @Synchronized fun open(scope:String,record:String?):String? = runCatching {
        val saved=record?.let(PassphraseCipher::decode) ?: return null
        val key=key(store()) ?: return null
        val bytes=PassphraseCipher.decrypt(key,aad(scope),saved) ?: return null
        try {bytes.toString(Charsets.UTF_8).takeIf(LiveExtractionClient::validKey)} finally {bytes.fill(0)}
    }.getOrNull()
}
