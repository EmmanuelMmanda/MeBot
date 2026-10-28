package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test

class SourceEvidence098Test {
    private val now = 1_793_100_000_000L
    private val url = "https://example.org/announcement"
    private val item = FeedItem("one", "news", "A source record", "Synthetic fixture", sourceName = "Example",
        sourceUrl = url, publishedAtMillis = now - 60_000, fetchedAtMillis = now)

    @Test fun modelOrFetchTimeAloneNeverEstablishesPublication() {
        assertNull(SourceEvidence098.publication(FeedState(items = listOf(item.copy(aiGenerated = true))), "news", url, now))
        assertNull(SourceEvidence098.publication(FeedState(items = listOf(item.copy(publishedAtMillis = 0))), "news", url, now))
        assertNull(SourceEvidence098.publication(FeedState(items = listOf(item.copy(acquisition = FeedAcquisition.AI_WEB_GROUNDED))), "news", url, now))
    }

    @Test fun actualAdapterMetadataOrVerifiedPublisherEvidenceCanBePreserved() {
        for (path in listOf(FeedAcquisition.PUBLISHER_RSS, FeedAcquisition.PUBLIC_API)) {
            val state = FeedState(items = listOf(item.copy(acquisition = path)))
            assertEquals(item.publishedAtMillis, SourceEvidence098.publication(state, "news", url, now)?.publishedAtMillis)
            assertNull(SourceEvidence098.publication(state, "other-module", url, now))
            assertNull(SourceEvidence098.publication(state, "news", "https://example.org/other", now))
        }
        val temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = item.publishedAtMillis)
        assertEquals(temporal, SourceEvidence098.publication(FeedState(items = listOf(item.copy(temporal = temporal))), "news", url, now))
        assertNull(SourceEvidence098.publication(FeedState(items = listOf(item.copy(
            acquisition = FeedAcquisition.PUBLISHER_RSS, publishedAtMillis = now + 86_400_000))), "news", url, now))
    }
}
