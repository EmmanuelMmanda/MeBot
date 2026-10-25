package mebot.nguli.com.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTimeTest {
    private val dar = ZoneId.of("Africa/Dar_es_Salaam")

    @Test fun `next one-time alarm today retains local zone`() {
        val now = ZonedDateTime.of(2026, 10, 21, 6, 0, 0, 0, dar)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 6, minute = 30, days = emptySet()), now)
        assertEquals("2026-10-21T06:30+03:00[Africa/Dar_es_Salaam]", next.toString())
        assertEquals("2026-10-21T03:30:00Z", next.toInstant().toString())
    }

    @Test fun `one-time alarm at the current instant goes to tomorrow`() {
        val now = ZonedDateTime.of(2026, 10, 21, 7, 0, 0, 0, dar)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 7, days = emptySet()), now)
        assertEquals("2026-10-22", next.toLocalDate().toString())
    }

    @Test fun `weekday alarm after Friday skips weekend`() {
        val now = ZonedDateTime.of(2026, 10, 23, 8, 0, 0, 0, dar)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 7), now)
        assertEquals("2026-10-26", next.toLocalDate().toString())
        assertEquals(1, next.dayOfWeek.value)
    }

    @Test fun `single weekday rolls exactly one week after missed occurrence`() {
        val now = ZonedDateTime.of(2026, 10, 21, 7, 1, 0, 0, dar)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 7, days = setOf(3)), now)
        assertEquals("2026-10-28", next.toLocalDate().toString())
    }

    @Test fun `midnight wraps year without using a fixed day duration`() {
        val now = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, dar)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 0, days = emptySet()), now)
        assertEquals("2027-01-01T00:00+03:00[Africa/Dar_es_Salaam]", next.toString())
    }

    @Test fun `spring gap shifts requested local time forward by gap`() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 1, 55, 0, 0, zone)
        val next = nextAlarmOccurrence(AlarmPlan(hour = 2, minute = 30, days = setOf(7)), now)
        assertEquals("2026-03-08T03:30-04:00[America/New_York]", next.toString())
    }

    @Test fun `fall overlap never schedules a second occurrence on the same day`() {
        val now = ZonedDateTime.parse("2026-11-01T01:45-04:00[America/New_York]")
        val next = nextAlarmOccurrence(AlarmPlan(hour = 1, minute = 30, days = setOf(7)), now)
        assertEquals("2026-11-08T01:30-05:00[America/New_York]", next.toString())
        assertTrue(next.toInstant().isAfter(now.toInstant()))
    }

    @Test fun `fall overlap chooses first occurrence when both are in future`() {
        val now = ZonedDateTime.parse("2026-11-01T00:45-04:00[America/New_York]")
        val next = nextAlarmOccurrence(AlarmPlan(hour = 1, minute = 30, days = setOf(7)), now)
        assertEquals("2026-11-01T01:30-04:00[America/New_York]", next.toString())
    }

    @Test fun `disabled alarm still supports next occurrence preview`() {
        val now = ZonedDateTime.of(2026, 10, 21, 6, 0, 0, 0, dar)
        assertEquals(7, nextAlarmOccurrence(AlarmPlan(enabled = false), now).hour)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid weekday is rejected`() {
        nextAlarmOccurrence(AlarmPlan(days = setOf(0)), ZonedDateTime.now(dar))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid hour is rejected`() {
        nextAlarmOccurrence(AlarmPlan(hour = 24), ZonedDateTime.now(dar))
    }
}
