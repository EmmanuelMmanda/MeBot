package mebot.nguli.com.ui.feed

/**
 * Serializes a permission ActivityResult launch with the exact ticket and private host payload.
 * Authorization invalidation deliberately leaves this slot occupied; only the actual result
 * callback consumes it, so a stale result cannot borrow a newer scope.
 */
internal class VoicePermissionLaunchSlot099<T>(
    private val ticketGate: VoicePermissionTicket099,
) {
    internal class Launch<T> internal constructor(
        val request: VoicePermissionTicket099.Request,
        val payload: T,
    ) {
        override fun toString(): String = "VoicePermissionLaunch099(<redacted>)"
    }

    private var outstanding: Launch<T>? = null

    /** Issues and binds the request atomically, or refuses while a platform result is outstanding. */
    @Synchronized
    fun issue(scopeKey: String, hostToken: String, payload: T): Launch<T>? {
        if (outstanding != null) return null
        val request = ticketGate.issue(scopeKey, hostToken) ?: return null
        return Launch(request, payload).also { outstanding = it }
    }

    /** Called once from the matching ActivityResult callback; a duplicate returns null. */
    @Synchronized
    fun consumeLaunch(): Launch<T>? = outstanding.also { outstanding = null }

    @Synchronized
    fun hasOutstandingLaunch(): Boolean = outstanding != null
}
