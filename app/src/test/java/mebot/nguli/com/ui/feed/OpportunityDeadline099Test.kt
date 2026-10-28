package mebot.nguli.com.ui.feed

import mebot.nguli.com.core.FeedCitation
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.FeedTemporal
import mebot.nguli.com.core.TemporalProvenance
import mebot.nguli.com.core.TemporalRelevance
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OpportunityDeadline099Test {
    private val now = LocalDate.of(2026, 10, 28).atTime(12, 0)
        .atZone(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toEpochMilli()

    @Test fun sourceBackedDateHasNoInventedTimeAndLocalizesEnglishAndSwahili() {
        val parsed = opportunityDeadlinePresentation099(item("2026-11-05"), now, "UTC")
        assertNotNull(parsed)
        assertEquals(OpportunityDeadlineStatus099.UPCOMING, parsed?.status)
        assertEquals(LocalDate.of(2026, 11, 5), parsed?.date)
        assertEquals("5 Nov 2026", opportunityDeadlineDateLabel099(parsed!!.date, "en"))
        assertEquals("5 Novemba 2026", opportunityDeadlineDateLabel099(parsed.date, "sw"))
    }

    @Test fun sourceCalendarDaySetsTodayAndPastStatesWithoutCallingOpportunityClosed() {
        val todayDate = "2026-10-28"
        assertEquals(OpportunityDeadlineStatus099.TODAY,
            opportunityDeadlinePresentation099(item(todayDate), now, "Africa/Dar_es_Salaam")?.status)
        val past = opportunityDeadlinePresentation099(item("2026-10-27"), now, "Africa/Dar_es_Salaam")
        assertEquals(OpportunityDeadlineStatus099.PASSED, past?.status)
        assertEquals("27 Oct 2026", opportunityDeadlineDateLabel099(past!!.date, "en"))
    }

    @Test fun publicationOrEventStartMetadataDoesNotEraseSeparateCitedDeadline() {
        val publication = item("2026-11-05", temporal = FeedTemporal(
            provenance = TemporalProvenance.PUBLISHER,
            publishedAtMillis = now - 86_400_000L,
        ))
        assertEquals(OpportunityDeadlineStatus099.UPCOMING,
            opportunityDeadlinePresentation099(publication, now, "UTC")?.status)

        val eventStart = item("2026-11-05", temporal = FeedTemporal(
            provenance = TemporalProvenance.EVENT_SOURCE,
            relevanceDate = "2026-10-20",
            sourceTimezone = "Africa/Dar_es_Salaam",
            relevanceKind = TemporalRelevance.EVENT,
        ))
        assertEquals(OpportunityDeadlineStatus099.UPCOMING,
            opportunityDeadlinePresentation099(eventStart, now, "UTC")?.status)
    }

    @Test fun missingMalformedUncitedOrConflictingDatesAreNotAnnounced() {
        assertNull(opportunityDeadlinePresentation099(item(""), now, "UTC"))
        assertNull(opportunityDeadlinePresentation099(item("5 November 2026"), now, "UTC"))
        assertNull(opportunityDeadlinePresentation099(item("2026-11-05T17:00:00Z"), now, "UTC"))
        assertNull(opportunityDeadlinePresentation099(item("2026-11-05", citations = emptyList()), now, "UTC"))
        assertNull(opportunityDeadlinePresentation099(item("2026-11-05", sourceUrl = "http://example.org/call"), now, "UTC"))
        assertEquals(OpportunityDeadlineStatus099.UPCOMING,
            opportunityDeadlinePresentation099(item("2026-11-05", temporal = FeedTemporal(
            provenance = TemporalProvenance.PUBLISHER,
            publishedAtMillis = now,
        )), now, "UTC")?.status)
        assertNull(opportunityDeadlinePresentation099(item("2026-11-05", temporal = event("2026-11-06")), now, "UTC"))
    }

    @Test fun existingTypedEventEvidenceControlsSourceTimezoneForExpiry() {
        val deadline = item("2026-10-28", temporal = event("2026-10-28", "Pacific/Kiritimati"))
        // At this UTC instant it is already the 29th in Kiritimati; source-local date is authoritative.
        val nextDayUtc = LocalDate.of(2026, 10, 28).atTime(12, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(OpportunityDeadlineStatus099.PASSED,
            opportunityDeadlinePresentation099(deadline, nextDayUtc, "America/Los_Angeles")?.status)
    }

    @Test fun invalidFallbackTimezoneUsesDeterministicUtcRatherThanDeviceZone() {
        val atUtcDateBoundary = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val noTypedDeadline = item("2026-10-28", temporal = null)
        assertEquals(OpportunityDeadlineStatus099.TODAY,
            opportunityDeadlinePresentation099(noTypedDeadline, atUtcDateBoundary, "not/a-zone")?.status)
    }

    private fun event(date: String, zone: String = "Africa/Dar_es_Salaam") = FeedTemporal(
        provenance = TemporalProvenance.EVENT_SOURCE,
        relevanceDate = date,
        sourceTimezone = zone,
        relevanceKind = TemporalRelevance.DEADLINE,
    )

    private fun item(
        deadline: String,
        sourceUrl: String = "https://www.fsdt.or.tz/igf/",
        citations: List<FeedCitation> = listOf(FeedCitation("FSDT Impact Grant Fund", "https://www.fsdt.or.tz/igf/")),
        temporal: FeedTemporal? = event(deadline.takeIf { it.length == 10 && it[4] == '-' && it[7] == '-' }.orEmpty()),
    ) = FeedItem(
        id = "fsdt-test",
        moduleId = "opportunities",
        kind = "opportunities",
        title = "FSDT grants for eligible small businesses",
        summary = "A source-backed test opportunity.",
        sourceName = "Financial Sector Deepening Tanzania (FSDT)",
        sourceUrl = sourceUrl,
        citations = citations,
        deadline = deadline,
        contentType = "opportunity",
        temporal = temporal,
    )
}
