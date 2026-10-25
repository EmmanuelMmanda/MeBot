package mebot.nguli.com.core

object SetupGuidance {
    const val SNOOZE_MILLIS = 7 * 86_400_000L
    fun hasPreferences(state: FeedState): Boolean = state.interestsConfigured ||
        state.context.any { it.confirmed } || state.modules.any { it.preferences.isNotEmpty() || it.kind == "watch" }

    fun next(state: FeedState, connected: Boolean, page: String, now: Long): String? {
        fun allowed(key: String) = state.dismissedNotices[key]?.let { now - it >= SNOOZE_MILLIS || it > now } ?: true
        return when {
            !connected && allowed("connection") -> "connection"
            !hasPreferences(state) && allowed("preferences") -> "preferences"
            else -> null
        }
    }
}
