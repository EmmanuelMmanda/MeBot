package mebot.nguli.com.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

/** No PIN is persisted. PBKDF2 runs off the UI thread; device tuning remains an acceptance gate. */
internal object PinSecurity {
    const val ITERATIONS = 600_000
    fun valid(pin: CharArray) = pin.size in 6..32 && pin.all { it in '0'..'9' }
    fun salt() = ByteArray(32).also(SecureRandom()::nextBytes)
    fun derive(pin: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(valid(pin) && salt.size == 32 && iterations == ITERATIONS)
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun matches(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** elapsedRealtime is wall-clock independent. A reboot conservatively restarts the stored delay. */
@Serializable
internal data class PinThrottle(val failures: Int = 0, val delayMillis: Long = 0, val sinceElapsed: Long = 0, val boot: Int = -1) {
    fun remaining(now: Long, currentBoot: Int): Long =
        if (currentBoot != boot || now < sinceElapsed) delayMillis
        else (delayMillis - (now - sinceElapsed)).coerceAtLeast(0)
    fun rebase(now: Long, currentBoot: Int): PinThrottle =
        if (boot != currentBoot || now < sinceElapsed || currentBoot < 0) copy(sinceElapsed = now, boot = currentBoot) else this
    fun reserve(now: Long, currentBoot: Int): PinThrottle {
        val count = (failures + 1).coerceAtMost(30)
        // Persist admission before expensive verification, so process killing cannot refund an attempt.
        val delay = when {
            count < 5 -> 0L
            else -> (30_000L * (1L shl (count - 5).coerceAtMost(10))).coerceAtMost(3_600_000L)
        }
        return PinThrottle(count, delay, now, currentBoot)
    }
}

@Serializable
internal data class LockRecord(
    val version: Int = 1,
    val salt: ByteArray,
    val verifier: ByteArray,
    val iterations: Int = PinSecurity.ITERATIONS,
    val biometric: Boolean = false,
    val throttle: PinThrottle = PinThrottle(),
) {
    fun validate(): LockRecord = apply {
        require(version == 1 && salt.size == 32 && verifier.size == 32 && iterations == PinSecurity.ITERATIONS)
        require(throttle.failures in 0..30 && throttle.delayMillis in 0..3_600_000 && throttle.sinceElapsed >= 0)
    }
}
