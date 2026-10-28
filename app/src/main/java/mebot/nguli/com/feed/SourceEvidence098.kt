package mebot.nguli.com.feed

import mebot.nguli.com.core.*

/** A generated date string is not publisher evidence; reuse only actual adapter/verified metadata. */
internal object SourceEvidence098 {
    fun publication(state: FeedState, moduleId: String, url: String, now: Long): FeedTemporal? {
        val canonical = FeedLogic.canonicalUrl(url)
        return state.items.asSequence().filter {
            it.moduleId == moduleId && FeedLogic.safeUrl(it.sourceUrl) && FeedLogic.canonicalUrl(it.sourceUrl) == canonical
        }.mapNotNull { item ->
            val verified = item.temporal?.takeIf { it.provenance == TemporalProvenance.PUBLISHER }
                ?.takeIf { runCatching { TodayFeed.validate(it) }.isSuccess }
            if (verified != null && (verified.publishedAtMillis ?: 0) in 1..(now + TodayFeed.CLOCK_SKEW_MILLIS)) verified
            else if (item.acquisition in setOf(FeedAcquisition.PUBLISHER_RSS, FeedAcquisition.PUBLIC_API) &&
                item.publishedAtMillis in 1..(now + TodayFeed.CLOCK_SKEW_MILLIS))
                FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = item.publishedAtMillis)
            else null
        }.firstOrNull()
    }
}
