package mebot.nguli.com.core

import java.time.ZonedDateTime

/**
 * Finds the first requested local-time occurrence strictly after [now]. Disabled alarms can be
 * previewed with this function; callers must filter [AlarmPlan.enabled] before scheduling them.
 * DST gaps move forward by the gap using java.time's transition rules. An overlap uses the earlier
 * offset only, so rescheduling after a ring cannot produce a second alarm on the same local date.
 */
fun nextAlarmOccurrence(alarm: AlarmPlan, now: ZonedDateTime): ZonedDateTime {
    StateValidation.alarm(alarm)
    for (dayOffset in 0L..7L) {
        val date = now.toLocalDate().plusDays(dayOffset)
        if (alarm.days.isNotEmpty() && date.dayOfWeek.value !in alarm.days) continue
        val candidate = date.atTime(alarm.hour, alarm.minute).atZone(now.zone)
        if (candidate.toInstant().isAfter(now.toInstant())) return candidate
    }
    error("No alarm occurrence found in the next week.")
}
