package mebot.nguli.com.ui.feed

import mebot.nguli.com.core.TodayFeed

/** Foreground only: even fixed-offset zones with seconds roll over at their actual midnight. */
internal fun feedClockDelayMillis(now: Long, timezone: String): Long {
    val minute = 60_000L - Math.floorMod(now, 60_000L)
    val midnight = TodayFeed.dayWindow(now, timezone).endMillis - now
    return minOf(minute, midnight).coerceAtLeast(1L)
}
