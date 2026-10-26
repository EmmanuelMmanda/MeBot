package mebot.nguli.com.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import java.time.Instant

class FreshnessPolicy094Test {
    private val now = Instant.parse("2026-10-26T00:30:00Z").toEpochMilli()
    private fun item(
        id: String,
        kind: String,
        publishedAt: Long = 0L,
        fetchedAt: Long = now,
        temporal: FeedTemporal? = null,
    ) = FeedItem(
        id = id, moduleId = kind, kind = kind, title = "Useful $id", summary = "Verified source material",
        sourceUrl = "https://example.com/$id", publishedAtMillis = publishedAt, fetchedAtMillis = fetchedAt,
        temporal = temporal,
    )

    private fun aiEstimate(item: FeedItem, state: FeedState, module: FeedModule) = AiRelevanceEstimate(
        fitEstimate = 80,
        assessedAtMillis = now,
        contentDigest = AiRelevance094.contentDigest(item),
        scopeProviderDigest = AiRelevance094.scopeProviderDigest(state, module),
    )

    @Test fun unknownPublicationDateStaysNeutralRegardlessOfFetchTime() {
        val fetchedNow = item("unknown-now", "news", fetchedAt = now)
        val fetchedLongAgo = item("unknown-old", "news", fetchedAt = now - 90 * 86_400_000L)
        assertNull(FreshnessPolicy094.rankingDateMillis(fetchedNow))
        assertEquals(0, FreshnessPolicy094.freshnessScore(fetchedNow, now))
        assertEquals(0, FreshnessPolicy094.freshnessScore(fetchedLongAgo, now))
        assertTrue(FreshnessPolicy094.isActive(fetchedNow, now))
        assertTrue(FreshnessPolicy094.isActive(fetchedLongAgo, now))
    }

    @Test fun legacyItemsWithoutTypedDatesDecodeAndRemainUndated() {
        val legacy = Json.decodeFromString<FeedState>(
            """{"items":[{"id":"legacy","moduleId":"news","title":"Older article","summary":"Legacy item","sourceUrl":"https://example.com/legacy","kind":"news","fetchedAtMillis":$now}]}""",
        )
        FeedLogic.validate(legacy)
        val decoded = legacy.items.single()
        assertEquals(0L, decoded.publishedAtMillis)
        assertNull(decoded.temporal)
        assertEquals(0, FreshnessPolicy094.freshnessScore(decoded, now))
        assertTrue(FeedLogic.visible(legacy, now).any { it.id == decoded.id })
    }

    @Test fun sourceDatedContentDecaysByKindAndNeverDropsBelowSoftFloor() {
        val current = item("current", "news", publishedAt = now)
        val oldNews = item("old-news", "news", publishedAt = now - 20 * 86_400_000L)
        val oldLesson = item("old-lesson", "learning", publishedAt = now - 20 * 86_400_000L)
        val eventWithNoPublisherDate = item("event", "events", publishedAt = now,
            temporal = FeedTemporal(TemporalProvenance.EVENT_SOURCE, relevanceDate = "2026-10-26",
                sourceTimezone = "UTC", relevanceKind = TemporalRelevance.EVENT))
        assertEquals(0, FreshnessPolicy094.freshnessScore(current, now))
        assertEquals(-36, FreshnessPolicy094.freshnessScore(oldNews, now))
        assertEquals(-5, FreshnessPolicy094.freshnessScore(oldLesson, now))
        assertNull(FreshnessPolicy094.rankingDateMillis(eventWithNoPublisherDate))
        assertEquals(0, FreshnessPolicy094.freshnessScore(eventWithNoPublisherDate, now))
        assertTrue(FreshnessPolicy094.freshnessScore(oldNews, now) < FreshnessPolicy094.freshnessScore(oldLesson, now))
        assertTrue(FreshnessPolicy094.freshnessScore(oldNews, now) >= -60)
        assertTrue(FreshnessPolicy094.isActive(oldNews, now)) // Age demotes; it does not delete old stories.
    }

    @Test fun aiFitIsASeparateBoundedComponentAndCannotOverrideExplicitNegativeFeedback() {
        val module = FeedModule(id = "news", kind = "news", query = "public tools")
        val base = item("candidate", "news", publishedAt = now - 20 * 86_400_000L).copy(relevance = 60)
        val feedback = RelevanceSignal(base.id, module.id, relevant = false, title = base.title,
            terms = setOf("useful", "candidate"), updatedAtMillis = now)
        val state = FeedState(modules = listOf(module), items = listOf(base), relevanceSignals = listOf(feedback))
        val estimate = AiRelevanceEstimate(
            fitEstimate = 100,
            assessedAtMillis = now,
            contentDigest = AiRelevance094.contentDigest(base),
            scopeProviderDigest = AiRelevance094.scopeProviderDigest(state, module),
        )
        val candidate = base.copy(aiRelevance = estimate)
        val rankedState = state.copy(items = listOf(candidate))
        FeedLogic.validate(rankedState)

        val direct = RecommendationPolicy.score(rankedState, candidate, now)
        val prepared = RecommendationPolicy.prepare(rankedState, now).score(candidate)
        assertEquals(10, direct.aiFit)
        assertEquals(-30, direct.feedback)
        assertEquals(-36, direct.freshness)
        assertEquals(direct.toString(), prepared.toString())

        val tooOld = candidate.copy(aiRelevance = estimate.copy(assessedAtMillis = now - AiRelevance094.CACHE_TTL_MILLIS - 1))
        assertEquals(0, RecommendationPolicy.score(rankedState, tooOld, now).aiFit)
        assertEquals(0, RecommendationPolicy.score(rankedState.copy(savedIds = setOf(candidate.id)), candidate, now).aiFit)
        assertEquals(0, RecommendationPolicy.score(rankedState.copy(hiddenIds = setOf(candidate.id)), candidate, now).aiFit)
        assertEquals(0, RecommendationPolicy.score(rankedState.copy(starredStories = listOf(StarredStory(candidate.id))), candidate, now).aiFit)
    }

    @Test fun refreshPreservesOnlyThePriorEstimateForIdenticalPublicContent() {
        val module = FeedModule(id = "news", kind = "news")
        val original = item("candidate", "news", publishedAt = now - 1_000)
        val state = FeedState(modules = listOf(module), items = listOf(original))
        val estimate = aiEstimate(original, state, module)
        val cached = original.copy(aiRelevance = estimate, media = FeedMedia(
            "https://example.com/image.png", "https://example.com/feed.xml", "Publisher",
        ))
        val stateWithCache = state.copy(items = listOf(cached))

        val sameContent = original.copy(fetchedAtMillis = now + 1, sourceUrl = original.sourceUrl + "?utm_source=refresh")
        val refreshed = FeedLogic.merge(stateWithCache, listOf(sameContent), now + 1).items.single()
        assertEquals(estimate, refreshed.aiRelevance)
        assertEquals(cached.media, refreshed.media)

        val changedContent = sameContent.copy(summary = "A materially changed summary")
        assertNull(FeedLogic.merge(stateWithCache, listOf(changedContent), now + 2).items.single().aiRelevance)

        val incomingCache = original.copy(aiRelevance = estimate)
        assertNull(FeedLogic.merge(state, listOf(incomingCache), now + 3).items.single().aiRelevance)

        val generated = original.copy(aiGenerated = true, aiRelevance = estimate)
        assertNull(FeedLogic.merge(stateWithCache, listOf(generated), now + 4).items.single().aiRelevance)
    }

    @Test fun onlyTypedExpiredWeatherEventAndDeadlineLeaveTheActiveList() {
        val expiredForecast = item("expired-weather", "weather", temporal = FeedTemporal(
            TemporalProvenance.FORECAST_PROVIDER,
            verifiedAtMillis = now - 1_000,
            validFromMillis = now - 3_600_000,
            validUntilMillis = now,
        )).copy(contentType = "weather", weather = FeedWeather(18.0, 18.0, 10, 1, "Dar es Salaam"))
        val staleForecast = item("stale-weather", "weather", temporal = FeedTemporal(
            TemporalProvenance.FORECAST_PROVIDER,
            verifiedAtMillis = now - TodayFeed.FORECAST_MAX_AGE_MILLIS - 1,
            validFromMillis = now - 1_000,
            validUntilMillis = now + 3_600_000,
        )).copy(contentType = "weather", weather = FeedWeather(18.0, 18.0, 10, 1, "Dar es Salaam"))
        val boundaryForecast = item("boundary-weather", "weather", temporal = FeedTemporal(
            TemporalProvenance.FORECAST_PROVIDER,
            verifiedAtMillis = now - TodayFeed.FORECAST_MAX_AGE_MILLIS,
            validFromMillis = now - 1_000,
            validUntilMillis = now + 1,
        )).copy(contentType = "weather", weather = FeedWeather(18.0, 18.0, 10, 1, "Dar es Salaam"))
        val oldEvent = item("old-event", "events", temporal = FeedTemporal(
            TemporalProvenance.EVENT_SOURCE, relevanceDate = "2026-10-25", sourceTimezone = "Africa/Dar_es_Salaam",
            relevanceKind = TemporalRelevance.EVENT,
        )).copy(contentType = "event")
        val oldDeadline = item("old-deadline", "opportunities", temporal = FeedTemporal(
            TemporalProvenance.EVENT_SOURCE, relevanceDate = "2026-10-25", sourceTimezone = "Africa/Dar_es_Salaam",
            relevanceKind = TemporalRelevance.DEADLINE,
        )).copy(contentType = "opportunity")
        val futureEvent = item("future-event", "events", temporal = FeedTemporal(
            TemporalProvenance.EVENT_SOURCE, relevanceDate = "2026-10-27", sourceTimezone = "Africa/Dar_es_Salaam",
            relevanceKind = TemporalRelevance.EVENT,
        )).copy(contentType = "event")
        val oldPublisherStory = item("old-publisher", "news", publishedAt = now - 90 * 86_400_000L,
            temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = now - 90 * 86_400_000L))
        val legacyDeadline = item("legacy-deadline", "opportunities", publishedAt = now - 10 * 86_400_000L)
            .copy(deadline = "2026-10-25", contentType = "opportunity")
        val items = listOf(expiredForecast, staleForecast, boundaryForecast, oldEvent, oldDeadline, futureEvent, oldPublisherStory, legacyDeadline)
        val modules = listOf("weather", "events", "opportunities", "news").map { FeedModule(id = it, kind = it) }
        val state = FeedState(timezone = "Pacific/Pago_Pago", modules = modules, items = items)

        val activeIds = FeedLogic.visible(state, now).map { it.id }.toSet()
        assertFalse(expiredForecast.id in activeIds)
        assertFalse(staleForecast.id in activeIds)
        assertTrue(boundaryForecast.id in activeIds) // Forecast verification is fresh through the six-hour boundary.
        assertFalse(oldEvent.id in activeIds)
        assertFalse(oldDeadline.id in activeIds)
        assertTrue(futureEvent.id in activeIds)
        assertTrue(oldPublisherStory.id in activeIds)
        assertTrue(legacyDeadline.id in activeIds) // A legacy/model deadline alone is not publisher-verified expiry.
        FeedLogic.validate(state)
    }

    @Test fun expiredSavedAndStarredItemsStayStoredAndAvailableInSavedState() {
        val savedForecast = item("saved-weather", "weather", temporal = FeedTemporal(
            TemporalProvenance.FORECAST_PROVIDER, verifiedAtMillis = now - 1_000,
            validFromMillis = now - 3_600_000, validUntilMillis = now,
        )).copy(contentType = "weather", weather = FeedWeather(18.0, 18.0, 10, 1, "Dar es Salaam"))
        val starredEvent = item("starred-event", "events", temporal = FeedTemporal(
            TemporalProvenance.EVENT_SOURCE, relevanceDate = "2026-10-25", sourceTimezone = "Africa/Dar_es_Salaam",
            relevanceKind = TemporalRelevance.EVENT,
        )).copy(contentType = "event")
        val state = FeedState(
            modules = listOf(FeedModule(id = "weather", kind = "weather"), FeedModule(id = "events", kind = "events")),
            items = listOf(savedForecast, starredEvent), savedIds = setOf(savedForecast.id),
            starredStories = listOf(StarredStory(starredEvent.id)),
        )
        assertTrue(FeedLogic.visible(state, now).isEmpty())

        val more = (1..270).map { index -> item("new-$index", "events", publishedAt = now + index) }
        val merged = FeedLogic.merge(state, more, now)
        assertTrue(merged.items.any { it.id == savedForecast.id })
        assertTrue(merged.items.any { it.id == starredEvent.id })
        assertTrue(merged.items.filter { it.id in merged.savedIds || it.id in merged.starredStories.map(StarredStory::itemId) }
            .any { it.id == savedForecast.id })
        assertEquals(2, merged.items.count { it.id in setOf(savedForecast.id, starredEvent.id) })
        FeedLogic.validate(merged)
    }
}
