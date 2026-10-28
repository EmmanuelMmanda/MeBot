package mebot.nguli.com.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PersonalDailySelection098Test {
    private val now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()

    @Test fun legacyAndCorruptSelectionCachesAreDiscardedWithoutRejectingFeedData() {
        val legacy = Json.decodeFromString<FeedState>("""
            {"schemaVersion":1,"items":[{"id":"old","moduleId":"news","title":"Older item",
             "summary":"Still useful","sourceName":"Publisher","sourceUrl":"https://example.com/old"}]}
        """.trimIndent())
        assertNull(legacy.personalDailySelection098)

        val item = source(0)
        val note = ContextEntry(id = "private-note", text = "Confirmed interest", scopes = setOf("news"))
        val malformed = PersonalDailySelection098(
            selectionDate = "2026-10-27", timezone = "Africa/Dar_es_Salaam",
            algorithmVersion = PersonalDailySelectionLogic098.ALGORITHM_VERSION,
            itemIds = listOf(item.id), itemRevisions = listOf("a".repeat(64)),
            inputDigest = "b".repeat(64), selectedAtMillis = now,
        )
        val state = FeedState(items = listOf(item), savedIds = setOf(item.id), context = listOf(note), personalDailySelection098 = malformed)

        val validated = FeedLogic.validate(state)
        assertNull(validated.personalDailySelection098)
        assertEquals(listOf(item), validated.items)
        assertEquals(setOf(item.id), validated.savedIds)
        assertEquals(listOf(note), validated.context)
    }

    @Test fun selectionIsStableThroughSameDayReadingAndSaving() {
        val state = state((0 until 8).map { source(it) })
        val selected = PersonalDailySelectionLogic098.ensure(state, now)
        val record = requireNotNull(selected.personalDailySelection098)
        val ids = record.itemIds

        val opened = FeedLogic.markViewed(selected, ids.first(), now)
        val saved = opened.copy(savedIds = setOf(ids.last()))
        val ensuredAgain = PersonalDailySelectionLogic098.ensure(saved, now)

        assertEquals(record, ensuredAgain.personalDailySelection098)
        assertEquals(ids, PersonalDailySelectionLogic098.resolve(ensuredAgain, now).map { it.id })
    }

    @Test fun newCandidatesDoNotReorderAFullDayButExplicitRefreshMayRerank() {
        val initialState = state((0 until 5).map { source(it, relevance = 35) })
        val initial = PersonalDailySelectionLogic098.ensure(initialState, now)
        val oldRecord = requireNotNull(initial.personalDailySelection098)
        val newHighRanked = (0 until 300).map { index -> source(1000 + index, relevance = 100) }
        val expanded = initial.copy(items = initial.items + newHighRanked)

        val retained = PersonalDailySelectionLogic098.ensure(expanded, now)
        assertEquals(oldRecord, retained.personalDailySelection098)
        assertEquals(oldRecord.itemIds, PersonalDailySelectionLogic098.resolve(retained, now).map { it.id })

        val refreshed = PersonalDailySelectionLogic098.ensure(expanded, now, refresh = true)
        assertTrue(refreshed.personalDailySelection098!!.itemIds.any { it.startsWith("source-10") })
        assertEquals(oldRecord.selectionDate, refreshed.personalDailySelection098!!.selectionDate)
    }

    @Test fun initialSelectionMixesOneOriginalReadWithSourceDiversityWhenAvailable() {
        val state = state((0 until 8).map { source(it, relevance = 70) } + originalRead())
        val selected = PersonalDailySelectionLogic098.ensure(state, now)
        val items = PersonalDailySelectionLogic098.resolve(selected, now)

        assertEquals(5, items.size)
        assertEquals(1, items.count { it.contentType == "original_read" })
        val sourceHosts = items.filterNot { it.contentType == "original_read" }
            .map { java.net.URI(it.sourceUrl).host }.toSet()
        assertTrue("Expected source variety, got $sourceHosts", sourceHosts.size >= 2)
        assertTrue(PersonalDailySelectionLogic098.isValidRecord(requireNotNull(selected.personalDailySelection098)))
    }

    @Test fun scarceSourcesAndHoleFillingNeverSelectMoreThanOneOriginalRead() {
        val reads = (1..8).map { originalRead("read-$it") }
        val oneSource = PersonalDailySelectionLogic098.ensure(state(listOf(source(1)) + reads), now)
        val oneSourceItems = PersonalDailySelectionLogic098.resolve(oneSource, now)
        assertEquals(listOf("source-1", "read-1"), oneSourceItems.map { it.id })
        assertEquals(1, oneSourceItems.count { it.contentType == "original_read" })

        val twoSources = PersonalDailySelectionLogic098.ensure(state(listOf(source(2), source(3)) + reads), now)
        val twoSourceItems = PersonalDailySelectionLogic098.resolve(twoSources, now)
        assertEquals(2, twoSourceItems.count { it.contentType != "original_read" })
        assertEquals(1, twoSourceItems.count { it.contentType == "original_read" })

        // A same-day hole fill can add a newly arrived read, but never several reads to a source mix.
        val initiallySparse = PersonalDailySelectionLogic098.ensure(state(listOf(source(4))), now)
        val withReads = initiallySparse.copy(items = initiallySparse.items + reads)
        val filled = PersonalDailySelectionLogic098.ensure(withReads, now)
        val filledItems = PersonalDailySelectionLogic098.resolve(filled, now)
        assertEquals(1, filledItems.count { it.contentType == "original_read" })
        assertTrue(filledItems.any { it.id == "source-4" })
        assertTrue(filledItems.size <= 2)
    }

    @Test fun fewerThanFiveEligibleItemsAreNotPadded() {
        val state = state(listOf(source(1), originalRead()))
        val selected = PersonalDailySelectionLogic098.ensure(state, now)

        assertEquals(2, selected.personalDailySelection098!!.itemIds.size)
        assertEquals(listOf("source-1", "read-1"), PersonalDailySelectionLogic098.resolve(selected, now).map { it.id })
    }

    @Test fun hiddenDisabledAndExpiredTargetsLeaveTheFeedUntouchedAndKeepValidSurvivorsOrdered() {
        val sourceItems = (0 until 8).map { source(it) }
        val selected = PersonalDailySelectionLogic098.ensure(state(sourceItems), now)
        val oldIds = selected.personalDailySelection098!!.itemIds
        val hiddenId = oldIds.first()
        val hiddenState = selected.copy(hiddenIds = selected.hiddenIds + hiddenId)
        val afterHidden = PersonalDailySelectionLogic098.ensure(hiddenState, now)
        assertEquals(oldIds.drop(1), afterHidden.personalDailySelection098!!.itemIds.take(4))
        assertFalse(hiddenId in afterHidden.personalDailySelection098!!.itemIds)
        assertTrue(afterHidden.items.any { it.id == hiddenId })

        val modules = listOf("news", "technology", "business", "science", "learning", "security")
            .map { FeedModule(id = it, kind = it, enabled = true) }
        val multiModuleItems = modules.mapIndexed { index, module -> source(index, module.id, module.kind) }
        val multiState = state(multiModuleItems, modules)
        val multiSelected = PersonalDailySelectionLogic098.ensure(multiState, now)
        val multiIds = multiSelected.personalDailySelection098!!.itemIds
        val disabledId = multiSelected.items.single { it.id == multiIds.first() }.moduleId
        val disabledState = multiSelected.copy(modules = modules.map { if (it.id == disabledId) it.copy(enabled = false) else it })
        val afterDisabled = PersonalDailySelectionLogic098.ensure(disabledState, now)
        assertEquals(multiIds.drop(1), afterDisabled.personalDailySelection098!!.itemIds.take(4))
        assertTrue(afterDisabled.items.any { it.moduleId == disabledId })

        val weatherModule = FeedModule(id = "weather", kind = "weather", enabled = true)
        val forecast = FeedItem(
            id = "forecast", moduleId = "weather", kind = "weather", contentType = "weather",
            title = "Local forecast", summary = "A provider forecast.", sourceName = "Forecast service",
            sourceUrl = "https://weather.example/forecast", relevance = 100,
            publishedAtMillis = 0, fetchedAtMillis = now,
            temporal = FeedTemporal(
                provenance = TemporalProvenance.FORECAST_PROVIDER, verifiedAtMillis = now - 60_000,
                validFromMillis = now, validUntilMillis = now + 3_600_000,
            ),
            weather = FeedWeather(24.0, 24.0, 10, 1, "Dar es Salaam"),
        )
        val expiringState = state(listOf(forecast) + (10..13).map { source(it) }, listOf(weatherModule, newsModule))
        val beforeExpiry = PersonalDailySelectionLogic098.ensure(expiringState, now)
        assertFalse("Forecast is utility content, not a personal reading slot", "forecast" in beforeExpiry.personalDailySelection098!!.itemIds)
        assertTrue("The existing Today projection still exposes a current forecast", TodayFeed.edition(expiringState, now).any { it.item.id == "forecast" })
        val afterExpiry = PersonalDailySelectionLogic098.ensure(beforeExpiry, now + 2 * 3_600_000)
        assertFalse("forecast" in afterExpiry.personalDailySelection098!!.itemIds)
        assertTrue(afterExpiry.items.any { it.id == "forecast" })

        val horoscopeModule = FeedModule(id = "horoscope", kind = "horoscope", enabled = true)
        val horoscope = source(20, moduleId = "horoscope", kind = "horoscope")
        val utilityOnly = state(listOf(forecast, horoscope), listOf(weatherModule, horoscopeModule))
        val noUtilityReading = PersonalDailySelectionLogic098.ensure(utilityOnly, now)
        assertTrue(noUtilityReading.personalDailySelection098!!.itemIds.isEmpty())
        assertEquals(listOf(forecast, horoscope), noUtilityReading.items)
    }

    @Test fun changedItemOrScopedContextInvalidatesOnlyAffectedTargets() {
        val note = ContextEntry(id = "scope", text = "Confirmed software interests", scopes = setOf("news"))
        val initial = PersonalDailySelectionLogic098.ensure(state((0 until 8).map { source(it) }, context = listOf(note)), now)
        val record = initial.personalDailySelection098!!
        val changedItemId = record.itemIds.first()
        val changedItems = initial.items.map { if (it.id == changedItemId) it.copy(summary = "The source record was revised.") else it }
        val changedState = initial.copy(items = changedItems)

        assertEquals(record.itemIds.drop(1), PersonalDailySelectionLogic098.resolve(changedState, now).map { it.id })
        val repaired = PersonalDailySelectionLogic098.ensure(changedState, now)
        assertEquals(record.itemIds.drop(1), repaired.personalDailySelection098!!.itemIds.take(4))
        assertNotEquals(record.inputDigest, repaired.personalDailySelection098!!.inputDigest)
        assertEquals(initial.items.size, repaired.items.size)

        val changedContext = changedState.copy(context = listOf(note.copy(text = "A different confirmed scope")))
        assertTrue(PersonalDailySelectionLogic098.resolve(changedContext, now).isEmpty())
        val rebuilt = PersonalDailySelectionLogic098.ensure(changedContext, now)
        assertNotEquals(repaired.personalDailySelection098!!.inputDigest, rebuilt.personalDailySelection098!!.inputDigest)

        val changedProvider = rebuilt.copy(connection = rebuilt.connection.copy(model = "another-configured-model"))
        assertTrue(PersonalDailySelectionLogic098.resolve(changedProvider, now).isEmpty())
    }

    @Test fun localMidnightDstAndTimezoneChangesUseRecommendationDatesNotPublisherDates() {
        val newYorkState = state((0 until 6).map { source(it) }, timezone = "America/New_York")
        val beforeDstJump = Instant.parse("2026-03-08T06:59:00Z").toEpochMilli()
        val afterDstJump = Instant.parse("2026-03-08T07:01:00Z").toEpochMilli()
        val before = PersonalDailySelectionLogic098.ensure(newYorkState, beforeDstJump)
        val sameLocalDay = PersonalDailySelectionLogic098.ensure(before, afterDstJump)
        assertEquals("2026-03-08", before.personalDailySelection098!!.selectionDate)
        assertEquals(before.personalDailySelection098, sameLocalDay.personalDailySelection098)

        val beforeMidnight = Instant.parse("2026-03-08T04:59:00Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-03-08T05:01:00Z").toEpochMilli()
        val marchSeventh = PersonalDailySelectionLogic098.ensure(newYorkState, beforeMidnight)
        val marchEighth = PersonalDailySelectionLogic098.ensure(marchSeventh, afterMidnight)
        assertEquals("2026-03-07", marchSeventh.personalDailySelection098!!.selectionDate)
        assertEquals("2026-03-08", marchEighth.personalDailySelection098!!.selectionDate)
        assertNotEquals(marchSeventh.personalDailySelection098!!.inputDigest, marchEighth.personalDailySelection098!!.inputDigest)

        val changedTimezone = PersonalDailySelectionLogic098.ensure(marchSeventh.copy(timezone = "Africa/Dar_es_Salaam"), beforeMidnight)
        assertEquals("Africa/Dar_es_Salaam", changedTimezone.personalDailySelection098!!.timezone)
        assertEquals(newYorkState.items.map { it.publishedAtMillis }, changedTimezone.items.map { it.publishedAtMillis })
    }

    @Test fun originalReadsStayOutsidePublisherTodayAndSelectionDoesNotRewriteSourceTime() {
        val publishedToday = source(22).copy(
            publishedAtMillis = now,
            fetchedAtMillis = now,
            temporal = FeedTemporal(provenance = TemporalProvenance.PUBLISHER, publishedAtMillis = now),
        )
        val read = originalRead()
        val feed = state(listOf(publishedToday, read))
        val beforePublishedTime = feed.items.single { it.id == read.id }.publishedAtMillis
        val selected = PersonalDailySelectionLogic098.ensure(feed, now)

        assertEquals(beforePublishedTime, selected.items.single { it.id == read.id }.publishedAtMillis)
        assertEquals(0L, selected.items.single { it.id == read.id }.publishedAtMillis)
        assertEquals(listOf(publishedToday.id), TodayFeed.edition(selected, now).map { it.item.id })
        assertEquals(FeedLogic.dateAt(now, feed.timezone), selected.personalDailySelection098!!.selectionDate)
    }

    private fun state(
        items: List<FeedItem>,
        modules: List<FeedModule> = listOf(newsModule, technologyModule),
        context: List<ContextEntry> = emptyList(),
        timezone: String = "Africa/Dar_es_Salaam",
    ) = FeedState(items = items, modules = modules, context = context, timezone = timezone)

    private fun source(
        index: Int,
        moduleId: String = "news",
        kind: String = moduleId,
        relevance: Int = 65,
    ) = FeedItem(
        id = "source-$index", moduleId = moduleId, kind = kind,
        title = "A useful source item $index", summary = "A current public source-backed record $index.",
        sourceName = "Publisher ${index % 3}",
        sourceUrl = "https://publisher${index % 3}.example/story-$index",
        publishedAtMillis = now - 60_000, fetchedAtMillis = now - 60_000, relevance = relevance,
    )

    private fun originalRead(id: String = "read-1") = FeedItem(
        id = id, moduleId = "technology", kind = "technology", contentType = "original_read",
        title = "A substantial AI-authored read", summary = "A clear teaser for the durable reading.",
        aiGenerated = true, acquisition = FeedAcquisition.ORIGINAL_READ, authorship = FeedAuthorship.AI,
        sourceUrl = "", sourceName = "", publishedAtMillis = 0, fetchedAtMillis = now - 60_000,
        sections = listOf(
            FeedContentSection("The underlying idea", "A useful section with a concrete idea, context and implications for a reader. ".repeat(7)),
            FeedContentSection("How to apply it", "A second section with a practical example, trade-offs and questions for reflection. ".repeat(7)),
        ),
    )

    companion object {
        private val newsModule = FeedModule(id = "news", kind = "news", enabled = true)
        private val technologyModule = FeedModule(id = "technology", kind = "technology", enabled = true)
    }
}
