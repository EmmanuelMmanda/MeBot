package mebot.nguli.com.realtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Voice broker credentials have a dedicated encrypted namespace. They never replace the
 * text-provider credential and are intentionally absent from FeedState/backup schemas.
 */
open class RealtimeBrokerSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mebot.realtime.broker", Context.MODE_PRIVATE)
    private val alias = "mebot.realtime.broker.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    @Synchronized
    open fun read(): RealtimeBrokerConfig? = runCatching {
        val encoded = preferences.getString("sealed", null) ?: return null
        val parts = encoded.split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val fields = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8).split('\n', limit = 2)
        require(fields.size == 2)
        RealtimeBrokerConfig.create(fields[0], fields[1])
    }.getOrNull()

    @Synchronized
    open fun write(serviceUrl: String, serviceToken: String) {
        val config = RealtimeBrokerConfig.create(serviceUrl, serviceToken)
        val plaintext = "${config.serviceUrl}\n${config.serviceToken()}".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP)
        check(preferences.edit().putString("sealed", sealed).commit())
    }

    @Synchronized
    open fun clear() {
        val hadCiphertext = preferences.contains("sealed")
        val cleared = preferences.edit().clear().commit()
        if (cleared) {
            // Do not initialize AndroidKeyStore on normal first-run/full-reset paths when no
            // voice setting was ever written. Preference removal has already been committed.
            if (hadCiphertext) deleteKeyBestEffort()
            return
        }

        // A failed synchronous preference commit must not leave recoverable credentials.
        // Invalidate the encryption key first, retry only removal of the ciphertext, and
        // report reset failure to the caller even if the retry succeeds.
        deleteKeyBestEffort()
        preferences.edit().remove("sealed").commit()
        throw IllegalStateException("Could not clear realtime broker settings")
    }

    private fun deleteKeyBestEffort() {
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }
}
