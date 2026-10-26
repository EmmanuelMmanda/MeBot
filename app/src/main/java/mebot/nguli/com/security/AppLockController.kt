package mebot.nguli.com.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Error codes only; never expose cryptographic/provider exception text to UI or logs. */
enum class LockProblem { WRONG_PIN, WAIT, STORAGE, INVALID_PIN, CANCELLED, BIOMETRIC }
data class AppLockState(
    val enabled: Boolean = false, val locked: Boolean = false, val biometric: Boolean = false,
    val busy: Boolean = false, val problem: LockProblem? = null, val waitMillis: Long = 0,
)

class AppLockController internal constructor(
    private val store: LockStorage,
    private val elapsed: () -> Long,
    private val boot: () -> Int,
    private val deviceLocked: () -> Boolean,
) {
    constructor(context: Context) : this(AppLockStore(context.applicationContext), SystemClock::elapsedRealtime,
        { try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 } },
        { context.getSystemService(KeyguardManager::class.java).isDeviceLocked })

    private val mutex = Mutex()
    private val sessionGuard = Any()
    private var generation = 0L
    private var foreground = false
    private var operationJob: Job? = null
    private val mutable = MutableStateFlow(initial())
    val state: StateFlow<AppLockState> = mutable
    private fun initial(): AppLockState = try {
        val record = store.read()
        if (record == null) AppLockState() else {
            val rebased = record.copy(throttle = record.throttle.rebase(elapsed(), boot()))
            if (rebased != record) store.write(rebased)
            AppLockState(enabled = true, locked = true, biometric = record.biometric)
        }
    } catch (_: Exception) { AppLockState(enabled = true, locked = true, problem = LockProblem.STORAGE) }

    fun foreground(active: Boolean) = synchronized(sessionGuard) {
        foreground = active
        if (!active || deviceLocked()) lock()
    }
    fun lock() = synchronized(sessionGuard) {
        generation++
        if (mutable.value.enabled) mutable.update { it.copy(locked = true, problem = null) }
    }
    internal fun ticket() = synchronized(sessionGuard) { generation }
    internal fun canAuthenticate(ticket: Long) = synchronized(sessionGuard) {
        foreground && !deviceLocked() && ticket == generation
    }
    private fun accept(ticket: Long, change: () -> Unit): Boolean = synchronized(sessionGuard) {
        if (!canAuthenticate(ticket) || operationJob?.isActive == false) return@synchronized problem(LockProblem.CANCELLED)
        change()
        true
    }
    internal fun biometricSuccess(ticket: Long): Boolean = synchronized(sessionGuard) {
        if (!mutable.value.enabled || !mutable.value.biometric) return@synchronized false
        accept(ticket) { mutable.update { it.copy(locked = false, problem = null) } }
    }
    fun biometricFailure() { mutable.update { it.copy(problem = LockProblem.BIOMETRIC) } }
    fun clearProblem() { mutable.update { it.copy(problem = null, waitMillis = 0) } }

    suspend fun setup(pin: CharArray): Boolean = operation(pin) { ticket ->
        check(!store.enabled())
        if (!PinSecurity.valid(pin)) return@operation problem(LockProblem.INVALID_PIN)
        val salt = PinSecurity.salt()
        val record = LockRecord(salt = salt, verifier = PinSecurity.derive(pin, salt))
        accept(ticket) {
            store.write(record)
            mutable.value = AppLockState(enabled = true, locked = true)
        }
    }

    suspend fun unlock(pin: CharArray): Boolean = operation(pin) { ticket ->
        if (!verify(pin)) return@operation false
        accept(ticket) { mutable.update { it.copy(locked = false, problem = null, waitMillis = 0) } }
    }

    suspend fun changePin(current: CharArray, replacement: CharArray): Boolean = try {
        operation(current) { ticket ->
            if (!PinSecurity.valid(replacement)) return@operation problem(LockProblem.INVALID_PIN)
            if (!verify(current)) return@operation false
            val salt = PinSecurity.salt()
            val next = store.read()!!.copy(salt = salt, verifier = PinSecurity.derive(replacement, salt))
            accept(ticket) { store.write(next) }
        }
    } finally { replacement.fill('\u0000') }

    suspend fun disable(pin: CharArray): Boolean = operation(pin) { ticket ->
        if (!verify(pin)) return@operation false
        accept(ticket) {
            store.disable()
            generation++
            mutable.value = AppLockState()
        }
    }

    /** Fresh PIN authentication is required, including when the app is already unlocked. */
    suspend fun setBiometric(pin: CharArray, enabled: Boolean, prepareKey: () -> Unit): Boolean = operation(pin) { ticket ->
        if (!verify(pin)) return@operation false
        accept(ticket) {
            if (enabled) prepareKey()
            store.write(store.read()!!.copy(biometric = enabled))
            mutable.update { it.copy(biometric = enabled) }
        }
    }

    private fun verify(pin: CharArray): Boolean {
        val record = store.read() ?: return problem(LockProblem.STORAGE)
        val wait = record.throttle.remaining(elapsed(), boot())
        if (wait > 0) {
            mutable.update { it.copy(problem = LockProblem.WAIT, waitMillis = wait) }
            return false
        }
        val reserved = record.copy(throttle = record.throttle.reserve(elapsed(), boot()))
        store.write(reserved)
        val derived = if (PinSecurity.valid(pin)) PinSecurity.derive(pin, record.salt, record.iterations) else ByteArray(32)
        val matches = try { PinSecurity.matches(record.verifier, derived) } finally { derived.fill(0) }
        if (!matches) return problem(LockProblem.WRONG_PIN)
        store.write(record.copy(throttle = PinThrottle()))
        return true
    }

    private fun problem(value: LockProblem): Boolean {
        mutable.update { it.copy(problem = value) }
        return false
    }
    private suspend fun operation(pin: CharArray, work: (Long) -> Boolean): Boolean {
        // Capture before queueing: an old attempt must not adopt a later foreground generation.
        val requestedTicket = ticket()
        try {
            return mutex.withLock {
                operationJob = currentCoroutineContext()[Job]
                mutable.update { it.copy(busy = true, problem = null, waitMillis = 0) }
                try { withContext(Dispatchers.Default) { work(requestedTicket) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // Failed reads/writes never downgrade configured protection.
                    val protected = kotlin.runCatching { store.enabled() }.getOrDefault(true)
                    mutable.update { it.copy(enabled = protected, locked = protected, problem = LockProblem.STORAGE) }
                    false
                } finally {
                    operationJob = null
                    mutable.update { it.copy(busy = false) }
                }
            }
        } finally { pin.fill('\u0000') } // Also clear attempts cancelled while waiting for the mutex.
    }
}
