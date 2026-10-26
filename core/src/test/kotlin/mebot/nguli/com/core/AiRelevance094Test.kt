package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class AiRelevance094Test {
    private val now = 1_800_000_000_000L
    private val module = FeedModule(id = "news", kind = "news", query = "technology")

    @Test fun cacheRequiresMatchingContentScopeProviderAndExpiry() {
        val item = item("candidate", "https://example.com/story", relevance = 40)
        val state = FeedState(modules = listOf(module), items = listOf(item))
        val estimate = estimate(state, item, 100)
        val cached = item.copy(aiRelevance = estimate)
        val cachedState = state.copy(items = listOf(cached))

        assertEquals(10, AiRelevance094.adjustment(cachedState, cached, now))
        assertEquals(0, AiRelevance094.adjustment(cachedState, cached.copy(summary = "Changed source text"), now))
        assertEquals(0, AiRelevance094.adjustment(cachedState.copy(context = listOf(
            ContextEntry(id = "new", text = "A changed approved priority", scopes = setOf("news")))), cached, now))
        assertEquals(0, AiRelevance094.adjustment(cachedState.copy(connection = AiConnection(mode = "direct", model = "another-model")), cached, now))
        assertEquals(0, AiRelevance094.adjustment(cachedState, cached, now + AiRelevance094.CACHE_TTL_MILLIS + 1))
        assertEquals(estimate.contentDigest, AiRelevance094.contentDigest(cached)) // Cache metadata never self-invalidates.
    }

    @Test fun aiFitCanImproveEditionRankWithoutMovingAlreadyPresentedItems() {
        val accepted = item("accepted", "https://example.com/accepted", relevance = 52)
        val incoming = item("incoming", "https://example.com/incoming", relevance = 50)
        val state = FeedState(modules = listOf(module), items = listOf(accepted, incoming))
        val rankedIncoming = incoming.copy(aiRelevance = estimate(state, incoming, 100))
        val enriched = state.copy(items = listOf(accepted, rankedIncoming))

        val edition = FeedLogic.edition(enriched, now)
        assertEquals(incoming.id, edition.first().id)
        // FeedHome uses this exact stable-window policy: accepted IDs stay first until the reader opts in.
        assertEquals(accepted.id, FeedPaging.ordered(edition, listOf(accepted.id)).first().id)
    }

    private fun estimate(state: FeedState, item: FeedItem, fit: Int) = AiRelevanceEstimate(
        fitEstimate = fit,
        assessedAtMillis = now,
        contentDigest = AiRelevance094.contentDigest(item),
        scopeProviderDigest = AiRelevance094.scopeProviderDigest(state, module),
    )

    private fun item(id: String, url: String, relevance: Int) = FeedItem(
        id = id,
        moduleId = module.id,
        kind = module.kind,
        title = "Source-backed candidate",
        summary = "A short factual source excerpt.",
        sourceUrl = url,
        relevance = relevance,
        publishedAtMillis = now - 3_600_000,
        fetchedAtMillis = now - 3_600_000,
    )
}
