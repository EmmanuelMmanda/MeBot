package app.mynguli.feed

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-owned credentials. Deliberately excluded from all application export schemas. */
open class SecretVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mebot.credentials", Context.MODE_PRIVATE)
    private val alias = "mebot.provider.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized open fun read(): String? {
        val encoded = preferences.getString("credential", null) ?: return null
        val parts = encoded.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    @Synchronized open fun write(secret: String) {
        require(secret.length in 8..4096 && !secret.contains('\n'))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("credential", value).commit())
    }
    @Synchronized open fun clear() { check(preferences.edit().clear().commit()) }
}
