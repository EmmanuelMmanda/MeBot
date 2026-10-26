package mebot.nguli.com.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Source adapter evidence only: a fetch time is never publication or relevance evidence. */
@Serializable
data class FeedTemporal(
    val provenance: TemporalProvenance,
    val verifiedAtMillis: Long? = null,
    val publishedAtMillis: Long? = null,
    val substantiveUpdatedAtMillis: Long? = null,
    val validFromMillis: Long? = null,
    val validUntilMillis: Long? = null,
    val relevanceDate: String? = null,
    val sourceTimezone: String? = null,
    val relevanceKind: TemporalRelevance = TemporalRelevance.NONE,
)
@Serializable enum class TemporalProvenance { PUBLISHER, FORECAST_PROVIDER, EVENT_SOURCE }
@Serializable enum class TemporalRelevance { NONE, EVENT, DEADLINE }
enum class TodayReason { PUBLISHED, UPDATED, FORECAST, HAPPENING, DUE }
data class TodayEntry(val item: FeedItem, val reason: TodayReason)
data class TodayWindow(val date: LocalDate, val startMillis: Long, val endMillis: Long)

/** Pure cached projection. Never changes source ownership, context scopes or request budget. */
object TodayFeed {
    const val CLOCK_SKEW_MILLIS = 300_000L
    const val FORECAST_MAX_AGE_MILLIS = 21_600_000L
    fun dayWindow(now: Long, timezone: String): TodayWindow {
        val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return TodayWindow(day, day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }
    fun reason(item: FeedItem, now: Long, window: TodayWindow): TodayReason? {
        val t = item.temporal ?: return null
        fun today(time: Long?) = time != null && time >= window.startMillis && time < window.endMillis && time <= now + CLOCK_SKEW_MILLIS
        return when(t.provenance) {
            TemporalProvenance.PUBLISHER -> when {
                today(t.substantiveUpdatedAtMillis) && t.publishedAtMillis != null && t.substantiveUpdatedAtMillis!! >= t.publishedAtMillis -> TodayReason.UPDATED
                today(t.publishedAtMillis) -> TodayReason.PUBLISHED
                else -> null
            }
            TemporalProvenance.FORECAST_PROVIDER -> if (item.kind == "weather" &&
                t.validFromMillis != null && t.validUntilMillis != null && t.validUntilMillis > t.validFromMillis &&
                t.validFromMillis < window.endMillis && t.validUntilMillis > window.startMillis &&
                t.validUntilMillis > now && t.verifiedAtMillis != null &&
                t.verifiedAtMillis <= now + CLOCK_SKEW_MILLIS && now - t.verifiedAtMillis <= FORECAST_MAX_AGE_MILLIS)
                TodayReason.FORECAST else null
            TemporalProvenance.EVENT_SOURCE -> {
                // A source-local date stays a date, not a fabricated publication instant.
                val sourceZone = t.sourceTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
                val date = t.relevanceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
                val start = date.atStartOfDay(sourceZone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(sourceZone).toInstant().toEpochMilli()
                if (start >= window.endMillis || end <= window.startMillis) null else when(t.relevanceKind) {
                    TemporalRelevance.EVENT -> TodayReason.HAPPENING
                    TemporalRelevance.DEADLINE -> TodayReason.DUE
                    TemporalRelevance.NONE -> null
                }
            }
        }
    }
    fun edition(state: FeedState, now: Long): List<TodayEntry> {
        val window=dayWindow(now,state.timezone)
        val seen=HashSet<String>()
        val eligible=FeedLogic.visible(state,now).filter { reason(it,now,window) != null }
        return FeedLogic.editionEligible(state,now,eligible).mapNotNull { item ->
            val reason=reason(item,now,window) ?: return@mapNotNull null
            val key=item.sourceUrl.takeIf { it.isNotBlank() }?.let(FeedLogic::canonicalUrl) ?: item.id
            if (!seen.add(key)) null else TodayEntry(item,reason)
        }
    }
    fun validate(t: FeedTemporal) {
        // Typed evidence must be internally consistent; import cannot relabel an event as publication.
        when(t.provenance) {
            TemporalProvenance.PUBLISHER -> {
                require(t.publishedAtMillis != null)
                require(t.validFromMillis == null && t.validUntilMillis == null)
                require(t.relevanceDate == null && t.relevanceKind == TemporalRelevance.NONE)
            }
            TemporalProvenance.FORECAST_PROVIDER -> {
                require(t.verifiedAtMillis != null && t.validFromMillis != null && t.validUntilMillis != null)
                require(t.publishedAtMillis == null && t.substantiveUpdatedAtMillis == null)
                require(t.relevanceDate == null && t.relevanceKind == TemporalRelevance.NONE)
            }
            TemporalProvenance.EVENT_SOURCE -> {
                require(t.relevanceDate != null && t.sourceTimezone != null && t.relevanceKind != TemporalRelevance.NONE)
                require(t.validFromMillis == null && t.validUntilMillis == null)
                require(t.substantiveUpdatedAtMillis == null || t.publishedAtMillis != null)
            }
        }
        listOfNotNull(t.verifiedAtMillis,t.publishedAtMillis,t.substantiveUpdatedAtMillis,t.validFromMillis,t.validUntilMillis).forEach { require(it in 1..32_503_680_000_000L) }
        t.sourceTimezone?.let { require(it.length <= 80); ZoneId.of(it) }
        t.relevanceDate?.let { require(it.length <= 10); LocalDate.parse(it) }
        if(t.validFromMillis!=null && t.validUntilMillis!=null) require(t.validUntilMillis>t.validFromMillis)
        if(t.publishedAtMillis!=null && t.substantiveUpdatedAtMillis!=null) require(t.substantiveUpdatedAtMillis>=t.publishedAtMillis)
    }
}
