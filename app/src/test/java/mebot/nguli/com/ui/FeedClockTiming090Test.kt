package mebot.nguli.com.ui

import mebot.nguli.com.ui.feed.feedClockDelayMillis
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FeedClockTiming090Test {
    private fun time(value:String)=Instant.parse(value).toEpochMilli()

    @Test fun secondLevelFixedOffsetTicksAtItsActualLocalMidnight() {
        val before=time("2026-10-25T23:59:25Z")
        assertEquals(5_000L,feedClockDelayMillis(before,"+00:00:30"))
        assertEquals(30_000L,feedClockDelayMillis(before+5_000,"+00:00:30"))
    }

    @Test fun daylightSavingDaysTickAtTheirRealNextMidnight() {
        // New York's spring-forward and fall-back days have different next-midnight offsets.
        assertEquals(500L,feedClockDelayMillis(time("2026-03-09T03:59:59.500Z"),"America/New_York"))
        assertEquals(500L,feedClockDelayMillis(time("2026-11-02T04:59:59.500Z"),"America/New_York"))
    }

    @Test fun ordinaryMinuteUpdatesStayBoundedAndNeverSpinAtTheBoundary() {
        assertEquals(15_000L,feedClockDelayMillis(time("2026-10-25T12:00:45Z"),"Africa/Dar_es_Salaam"))
        assertEquals(60_000L,feedClockDelayMillis(time("2026-10-25T12:01:00Z"),"Africa/Dar_es_Salaam"))
    }
}
