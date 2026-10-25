package mebot.nguli.com.core

/** A local preference, independent of a provider's own quota or billing rules. */
object RequestBudget {
    const val UNLIMITED = 0
    const val DEFAULT_LIMIT = 28
    val presets = listOf(28, 56, 112, 224)

    fun isValidLimit(limit: Int): Boolean = limit >= 0
    fun isUnlimited(limit: Int): Boolean = limit == UNLIMITED

    fun usedToday(state: FeedState, now: Long = System.currentTimeMillis()): Int =
        if (state.usageDate == FeedLogic.dateAt(now, state.timezone)) state.requestsToday else 0

    /** Called inside the repository's atomic update so parallel requests share one budget. */
    fun reserve(state: FeedState, now: Long = System.currentTimeMillis()): FeedState? {
        require(isValidLimit(state.dailyRequestLimit) && state.requestsToday >= 0)
        val count = usedToday(state, now)
        if (!isUnlimited(state.dailyRequestLimit) && count >= state.dailyRequestLimit) return null
        // An unlimited preference must never wrap the counter or stop at a storage ceiling.
        val next = if (count == Int.MAX_VALUE) count else count + 1
        return state.copy(usageDate = FeedLogic.dateAt(now, state.timezone), requestsToday = next)
    }
}
