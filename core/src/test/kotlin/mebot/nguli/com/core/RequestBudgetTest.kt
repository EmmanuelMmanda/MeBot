package mebot.nguli.com.core

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RequestBudgetTest {
    private val now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()
    private fun state(limit: Int, used: Int = 0) = FeedState(
        timezone = "Africa/Dar_es_Salaam", dailyRequestLimit = limit,
        usageDate = FeedLogic.dateAt(now, "Africa/Dar_es_Salaam"), requestsToday = used,
    )

    @Test fun newProfilesStartAt28AndExistingExplicitLimitsAreNotIncreased() {
        assertEquals(28, FeedState().dailyRequestLimit)
        assertEquals(28, Json.decodeFromString<FeedState>("{}").dailyRequestLimit)
        assertEquals(12, Json.decodeFromString<FeedState>("{\"dailyRequestLimit\":12}").dailyRequestLimit)
        assertEquals(3, Json.decodeFromString<FeedState>("{\"dailyRequestLimit\":3}").dailyRequestLimit)
    }

    @Test fun largerPresetsAndCustomLimitsSurviveValidationAndSerialization() {
        (RequestBudget.presets + listOf(0, 500, 12_345, Int.MAX_VALUE)).forEach { limit ->
            val configured = state(limit, used = 12_345)
            assertEquals(configured, FeedLogic.validate(Json.decodeFromString<FeedState>(Json.encodeToString(configured))))
        }
        assertThrows(IllegalArgumentException::class.java) { FeedLogic.validate(state(-1)) }
        assertThrows(IllegalArgumentException::class.java) { FeedLogic.validate(state(0, used = -1)) }
    }

    @Test fun finiteLimitReservesTheLastSlotAndRejectsTheNextAttempt() {
        val last = RequestBudget.reserve(state(224, used = 223), now)!!
        assertEquals(224, last.requestsToday)
        assertNull(RequestBudget.reserve(last, now))
    }

    @Test fun unlimitedHasNoHiddenStorageOrRequestCeiling() {
        val next = RequestBudget.reserve(state(RequestBudget.UNLIMITED, used = 10_000), now)!!
        assertEquals(10_001, next.requestsToday)
        assertEquals(next, FeedLogic.validate(next))
        val saturated = RequestBudget.reserve(state(RequestBudget.UNLIMITED, used = Int.MAX_VALUE), now)!!
        assertEquals(Int.MAX_VALUE, saturated.requestsToday)
        assertNotNull(RequestBudget.reserve(saturated, now))
        assertEquals(saturated, FeedLogic.validate(saturated))
    }

    @Test fun usageResetsAtMidnightInTheConfiguredTimezone() {
        val beforeMidnight = Instant.parse("2026-10-23T20:59:59Z").toEpochMilli()
        val midnight = Instant.parse("2026-10-23T21:00:00Z").toEpochMilli()
        val exhausted = state(28, used = 28)
        assertEquals(28, RequestBudget.usedToday(exhausted, beforeMidnight))
        assertNull(RequestBudget.reserve(exhausted, beforeMidnight))
        assertEquals(0, RequestBudget.usedToday(exhausted, midnight))
        val first = RequestBudget.reserve(exhausted, midnight)!!
        assertEquals("2026-10-24", first.usageDate)
        assertEquals(1, first.requestsToday)
    }

    @Test fun changingThePreferenceRetainsAlreadyUsedRequests() {
        val exhausted = state(28, used = 28)
        val unlimited = RequestBudget.reserve(exhausted.copy(dailyRequestLimit = RequestBudget.UNLIMITED), now)!!
        assertEquals(29, unlimited.requestsToday)
        assertNull(RequestBudget.reserve(unlimited.copy(dailyRequestLimit = 28), now))
        assertEquals(30, RequestBudget.reserve(unlimited.copy(dailyRequestLimit = 56), now)!!.requestsToday)
    }
}
