package mebot.nguli.com.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure, source-evidence-aware freshness rules for the active feed and local ranking. */
internal object FreshnessPolicy094 {
    private val timeSensitiveKinds = setOf(
        "news", "weather", "security", "business", "events", "opportunities", "scholarships", "sport",
    )
    private val topicalKinds = setOf("technology", "science", "github")
    private val evergreenKinds = setOf(
        "learning", "music", "entertainment", "media", "travel", "food", "farming", "family", "home", "wellbeing", "watch",
    )

    /** No age evidence means neutral decay. Fetch time is storage metadata, not a publication date. */
    fun rankingDateMillis(item: FeedItem): Long? {
        val temporal = item.temporal
        val evidenced = when (temporal?.provenance) {
            TemporalProvenance.PUBLISHER -> temporal.publishedAtMillis
            TemporalProvenance.FORECAST_PROVIDER -> temporal.validFromMillis
            TemporalProvenance.EVENT_SOURCE -> temporal.publishedAtMillis
            null -> item.publishedAtMillis.takeIf { it > 0 }
        }
        return evidenced?.takeIf { it > 0 }
    }

    fun freshnessScore(item: FeedItem, now: Long): Int {
        val date = rankingDateMillis(item) ?: return 0
        val ageHours = (now - date).coerceAtLeast(0) / 3_600_000.0
        val (halfLife, weight) = when (item.kind) {
            in timeSensitiveKinds -> 24.0 to 12
            in topicalKinds -> 72.0 to 8
            in evergreenKinds -> 336.0 to 6
            else -> 168.0 to 6
        }
        return (-kotlin.math.ln(1 + ageHours / halfLife) * weight).toInt().coerceAtLeast(-60)
    }

    /** Expire only explicit typed source evidence; unknown or legacy dates fail open. */
    fun isActive(item: FeedItem, now: Long): Boolean {
        val temporal = item.temporal ?: return true
        return when (temporal.provenance) {
            TemporalProvenance.PUBLISHER -> true // Old stories decay; they do not become false or deleted.
            TemporalProvenance.FORECAST_PROVIDER -> {
                if (item.kind != "weather") return true
                val verifiedAt = temporal.verifiedAtMillis ?: return true
                val validUntil = temporal.validUntilMillis ?: return true
                val age = now - verifiedAt
                // Match TodayFeed's six-hour verification window as well as the source's actual validity interval.
                validUntil > now && age >= -TodayFeed.CLOCK_SKEW_MILLIS && age <= TodayFeed.FORECAST_MAX_AGE_MILLIS
            }
            TemporalProvenance.EVENT_SOURCE -> {
                val sourceZone = temporal.sourceTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return true
                val sourceDate = temporal.relevanceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
                val sourceToday = Instant.ofEpochMilli(now).atZone(sourceZone).toLocalDate()
                !sourceDate.isBefore(sourceToday)
            }
        }
    }
}
