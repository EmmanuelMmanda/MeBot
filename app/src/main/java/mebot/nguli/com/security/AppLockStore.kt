package mebot.nguli.com.security

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only a non-personal enabled marker is available to direct-boot alarms. */
object AppLockPrivacy {
    private val policyGuard = Any()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onProtectionChanged(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
    fun <T> withPolicy(block: () -> T): T = synchronized(policyGuard, block)
    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("app_lock_protection_v1", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = runCatching {
        !context.getSystemService(UserManager::class.java).isUserUnlocked ||
            preferences(context).getBoolean("enabled", false) || recordExists(context)
    }.getOrDefault(true)
    internal fun recordExists(context: Context): Boolean {
        val base = File(context.noBackupFilesDir, "app-lock-v1.bin")
        return base.exists() || File(base.path + ".bak").exists()
    }
    internal fun mark(context: Context, enabled: Boolean) = withPolicy {
        val wasEnabled = preferences(context).getBoolean("enabled", false)
        val committed = preferences(context).edit().putBoolean("enabled", enabled).commit()
        if (enabled && !wasEnabled) scrubExistingNotifications(context)
        if (enabled != wasEnabled) listeners.forEach { listener -> runCatching(listener) }
        check(committed)
    }
    @android.annotation.SuppressLint("MissingPermission") // Existing notifications only; revocation/failure is handled without blocking PIN access.
    private fun scrubExistingNotifications(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        runCatching { manager.cancel(300) }
        val activeAlarms = runCatching { manager.activeNotifications.filter { it.notification.channelId == "mebot_active_alarm_v1" } }.getOrDefault(emptyList())
        activeAlarms.forEach { active ->
            runCatching {
                val safe = android.app.Notification.Builder.recoverBuilder(context, active.notification)
                    .setContentTitle(context.getString(mebot.nguli.com.R.string.alarm_generic_title))
                    .setContentText(context.getString(mebot.nguli.com.R.string.alarm_time_set))
                    .setOnlyAlertOnce(true).build()
                manager.notify(active.tag, active.id, safe)
            }.onFailure {
                // Do not leave an old personal label if a permission change prevents replacement.
                // Ringing/activity safety controls continue; the service can retry a generic notification.
                runCatching { manager.cancel(active.tag, active.id) }
            }
        }
    }
}

internal interface LockStorage {
    fun enabled(): Boolean
    fun read(): LockRecord?
    fun write(record: LockRecord)
    fun disable()
}

/** Credential-protected, no-backup atomic AEAD record; never part of FeedState or an import/export. */
internal class AppLockStore(private val context: Context, private val testKeyProvider: ((Boolean) -> SecretKey)? = null) : LockStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "app-lock-v1.bin"))
    private val json = Json { encodeDefaults = true }
    private fun key(create: Boolean): SecretKey {
        testKeyProvider?.let { return it(create) }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) // A missing key must never turn an existing lock into an unlocked app.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun enabled() = AppLockPrivacy.enabled(context)
    override fun read(): LockRecord? {
        if (!enabled()) return null
        val bytes = file.openRead().use { input -> ByteArray(16_385).let { buffer -> var size = 0; while (size < buffer.size) { val count = input.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }; buffer.copyOf(size) }.also { require(it.size in 29..16_384 && input.read() == -1) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(AAD)
        }
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        val record = try { json.decodeFromString<LockRecord>(plain.decodeToString()).validate() } finally { plain.fill(0) }
        // Repair an interrupted record-first setup only after authenticating/decrypting the record.
        AppLockPrivacy.mark(context, true)
        return record
    }
    override fun write(record: LockRecord) {
        record.validate()
        val new = !enabled()
        val secret = key(new)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret); updateAAD(AAD) }
        val plain = json.encodeToString(record).encodeToByteArray()
        val bytes = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        LockStoreTransactions.enable(writeRecord = {
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) } catch (failure: Throwable) { file.failWrite(output); throw failure }
        }, markEnabled = { AppLockPrivacy.mark(context, true) })
    }
    override fun disable() {
        // Each interruption retains either a usable previous verifier or the authenticated disabled state.
        LockStoreTransactions.disable(markDisabled = { AppLockPrivacy.mark(context, false) }, removeRecord = {
            file.delete()
            check(!AppLockPrivacy.recordExists(context))
        }, clearKeys = {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS); deleteEntry(BiometricUnlock.ALIAS) }
        })
    }
    companion object {
        private const val ALIAS = "mebot.app-lock.pin.v1"
        private val AAD = "mebot-app-lock-record-v1".encodeToByteArray()
    }
}

/** Ordering is shared with fault-injection tests; no security state is silently reset on I/O failure. */
internal object LockStoreTransactions {
    fun enable(writeRecord: () -> Unit, markEnabled: () -> Unit) {
        writeRecord() // A durable, usable PIN is the enabling record. Marker failure cannot lose it.
        markEnabled()
    }
    fun disable(markDisabled: () -> Unit, removeRecord: () -> Unit, clearKeys: () -> Unit) {
        markDisabled() // If this fails, keep the usable record and its key.
        removeRecord() // File existence continues to protect if deletion fails.
        runCatching(clearKeys) // Leftover non-exportable key is inert; cleanup must not undo authorized disable.
    }
}
