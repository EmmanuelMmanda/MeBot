package mebot.nguli.com.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import mebot.nguli.com.R
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A success callback alone is not authentication: an auth-bound CryptoObject must perform the operation. */
class BiometricUnlock(private val activity: FragmentActivity, private val controller: AppLockController) {
    private var prompt: BiometricPrompt? = null
    private var attempt = 0L
    fun available() = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun authenticate(title: String = activity.getString(R.string.app_lock_unlock), negativeLabel: String = activity.getString(R.string.app_lock_use_pin)) {
        cancel()
        if (!available() || !controller.state.value.biometric) { controller.biometricFailure(); return }
        val ticket = controller.ticket()
        val current = attempt
        try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (attempt != current || !controller.canAuthenticate(ticket)) return
                    val returned = result.cryptoObject?.cipher
                    val valid = returned === cipher && runCatching { returned.doFinal(challenge).isNotEmpty() }.getOrDefault(false)
                    challenge.fill(0)
                    if (valid) controller.biometricSuccess(ticket) else controller.biometricFailure()
                    prompt = null
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    challenge.fill(0)
                    if (attempt == current) { controller.biometricFailure(); prompt = null }
                }
                override fun onAuthenticationFailed() { if (attempt == current) controller.biometricFailure() }
            })
            prompt!!.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeLabel).build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { controller.biometricFailure() } // invalidated/missing key: PIN remains available
    }
    fun cancel() { attempt++; prompt?.cancelAuthentication(); prompt = null }
    companion object {
        internal const val ALIAS = "mebot.app-lock.biometric.v1"
        /** Called only by an explicit, freshly PIN-authenticated settings action. */
        fun prepareKey() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else { @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1) }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
        }
    }
}
