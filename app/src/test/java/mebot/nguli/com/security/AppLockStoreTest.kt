package mebot.nguli.com.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic ephemeral AES key proves AEAD/file behavior, not Android hardware Keystore behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.noBackupFilesDir, "app-lock-v1.bin")
    private val record = LockRecord(salt = ByteArray(32) { 1 }, verifier = ByteArray(32) { 2 })
    private fun store() = AppLockStore(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    @Test fun encryptedRecordRoundTripsWithFreshIvAndNoPlaintextFields() {
        val store = store()
        store.write(record)
        val first = file.readBytes()
        val read = store.read()!!
        assertArrayEquals(record.salt, read.salt); assertArrayEquals(record.verifier, read.verifier)
        assertFalse(first.decodeToString().contains("verifier"))
        store.write(record)
        assertFalse(first.contentEquals(file.readBytes()))
        assertTrue(store.enabled())
    }
    @Test fun ciphertextTamperingFailsClosedWithoutReplacingRecordOrKey() {
        val store = store(); store.write(record)
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertTrue(runCatching { store.read() }.isFailure)
        assertTrue(store.enabled()); assertArrayEquals(damaged, file.readBytes())
    }
    @Test fun missingKeyDoesNotGenerateReplacementForExistingProtection() {
        store().write(record)
        var creationRequested = false
        val missing = AppLockStore(context) { create -> creationRequested = create; error("synthetic missing key") }
        assertTrue(runCatching { missing.read() }.isFailure)
        assertFalse(creationRequested); assertTrue(missing.enabled())
    }
    @Test fun oversizedRecordIsRejectedBeforeCrypto() {
        file.writeBytes(ByteArray(16_385))
        var keyRequested = false
        val store = AppLockStore(context) { keyRequested = true; error("must not reach crypto") }
        assertTrue(runCatching { store.read() }.isFailure)
        assertFalse(keyRequested); assertTrue(store.enabled())
    }
    @Test fun verifiedRecordRepairsInterruptedMarkerAndDisableRemovesProtection() {
        val store = store(); store.write(record)
        AppLockPrivacy.mark(context, false)
        assertTrue(store.enabled()) // Durable record still enables protection.
        assertNotNull(store.read())
        assertTrue(context.createDeviceProtectedStorageContext().getSharedPreferences("app_lock_protection_v1", Context.MODE_PRIVATE).getBoolean("enabled", false))
        store.disable()
        assertFalse(file.exists()); assertFalse(store.enabled()); assertNull(store.read())
    }
}
