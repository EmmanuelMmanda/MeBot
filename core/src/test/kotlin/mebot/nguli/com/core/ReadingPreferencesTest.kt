package mebot.nguli.com.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadingPreferencesTest {
    @Test fun oldBackupsGetPresentationDefaultsWithoutNotificationPermission() {
        val state = Json.decodeFromString<FeedState>("{}")
        assertTrue(state.readingPreferences.autoLoadMore)
        assertTrue(state.readingPreferences.notificationImages)
        assertFalse(state.notifications)
        assertFalse(state.backgroundEnabled)
    }
    @Test fun preferencesRoundTripAndStayIndependentOfAllowance() {
        val original = FeedState(readingPreferences = ReadingPreferences(false, false, false, false), dailyRequestLimit = 56)
        val restored = Json.decodeFromString<FeedState>(Json.encodeToString(original))
        assertEquals(original.readingPreferences, restored.readingPreferences)
        assertEquals(56, restored.dailyRequestLimit)
        assertFalse(restored.notifications)
    }
}
