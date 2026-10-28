package mebot.nguli.com.ui.feed

/** Small UI-owned gate for one runtime microphone permission request. No persistence/network. */
internal class VoicePermissionTicket099(
    private val nowElapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    init { require(ttlMillis > 0L) }
    internal class Request internal constructor(
        val hostToken: String,
        val scopeKey: String,
        internal val serial: Long,
        internal val issuedAtElapsedMillis: Long,
    ) {
        override fun toString(): String = "VoicePermissionRequest099(<redacted>)"
    }

    internal enum class Result { START_NOW, WAIT_FOR_RESUME, DENIED, STALE }

    private var serial = 0L
    private var current: Request? = null
    private var resultReceived = false
    private var grantedWaitingForResume = false

    /** Issues exactly one short-lived request. A caller must retain the returned opaque ticket. */
    @Synchronized
    fun issue(scopeKey: String, hostToken: String): Request? {
        expireIfNecessary()
        if (current != null || scopeKey.isBlank() || hostToken.isBlank()) return null
        return Request(hostToken, scopeKey, ++serial, nowElapsedMillis()).also {
            current = it
            resultReceived = false
            grantedWaitingForResume = false
        }
    }

    /** Called only from this request's Activity Result callback. */
    @Synchronized
    fun onPermissionResult(
        request: Request,
        granted: Boolean,
        resumed: Boolean,
        pageActive: Boolean,
        unlocked: Boolean,
        currentScopeKey: String?,
    ): Result {
        if (!isCurrent(request) || resultReceived) return Result.STALE
        if (!isUsable(request, pageActive, unlocked, currentScopeKey)) {
            clear()
            return Result.STALE
        }
        resultReceived = true
        if (!granted) {
            clear()
            return Result.DENIED
        }
        if (resumed) {
            clear()
            return Result.START_NOW
        }
        // The grant arrived before Lifecycle.State.RESUMED. Keep it only until a matching
        // ON_RESUME; ON_STOP/lock/scope change/expiry permanently invalidates this request.
        grantedWaitingForResume = true
        return Result.WAIT_FOR_RESUME
    }

    /** Returns the granted ticket once, and only for the same foreground scope. */
    @Synchronized
    fun consumeOnResume(pageActive: Boolean, unlocked: Boolean, currentScopeKey: String?): Request? {
        expireIfNecessary()
        val request = current ?: return null
        if (!grantedWaitingForResume) return null
        if (!isUsable(request, pageActive, unlocked, currentScopeKey)) {
            clear()
            return null
        }
        clear()
        return request
    }

    /** Scope/page changes must be reported even if the user later navigates back to the old scope. */
    @Synchronized
    fun invalidateIfScopeChanged(pageActive: Boolean, unlocked: Boolean, currentScopeKey: String?) {
        val request = current ?: return
        if (!isUsable(request, pageActive, unlocked, currentScopeKey)) clear()
    }

    @Synchronized fun invalidateOnStop() = clear()
    @Synchronized fun invalidateOnLock() = clear()
    @Synchronized fun hasPendingRequest(): Boolean {
        expireIfNecessary()
        return current != null
    }

    private fun isCurrent(request: Request): Boolean = current == request

    private fun isUsable(request: Request, pageActive: Boolean, unlocked: Boolean, currentScopeKey: String?): Boolean {
        val age = nowElapsedMillis() - request.issuedAtElapsedMillis
        return current == request && age >= 0L && age < ttlMillis && pageActive && unlocked &&
            !currentScopeKey.isNullOrBlank() && currentScopeKey == request.scopeKey
    }

    private fun expireIfNecessary() {
        val request = current ?: return
        val age = nowElapsedMillis() - request.issuedAtElapsedMillis
        if (age < 0L || age >= ttlMillis) clear()
    }

    private fun clear() {
        current = null
        resultReceived = false
        grantedWaitingForResume = false
    }

    companion object { const val DEFAULT_TTL_MILLIS = 15_000L }
}
