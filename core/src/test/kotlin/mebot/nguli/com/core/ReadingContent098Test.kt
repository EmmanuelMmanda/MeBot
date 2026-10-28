package mebot.nguli.com.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingContent098Test {
    @Test fun legacyItemsDecodeWithUnknownProvenanceDefaultsAndStayReadable() {
        val legacy = Json.decodeFromString<FeedState>("""
            {"schemaVersion":1,"items":[{"id":"legacy","moduleId":"news","title":"Older item","summary":"Old summary",
             "sourceName":"Publisher","sourceUrl":"https://example.com/legacy","publishedAtMillis":10}]}
        """.trimIndent())

        val item = legacy.items.single()
        assertEquals(FeedAcquisition.LEGACY_UNKNOWN, item.acquisition)
        assertEquals(FeedAuthorship.LEGACY_UNKNOWN, item.authorship)
        assertTrue(item.sections.isEmpty())
        assertEquals("Older item", FeedLogic.validate(legacy).items.single().title)
    }

    @Test fun futureProvenanceCodesRoundTripWithoutClosedEnumFailure() {
        val future = sourced().copy(acquisition = "future_catalog", authorship = "community_edited")
        val encoded = Json.encodeToString(future)
        val decoded = Json.decodeFromString<FeedItem>(encoded)

        assertEquals("future_catalog", decoded.acquisition)
        assertEquals("community_edited", decoded.authorship)
        FeedLogic.validateItem(decoded)
    }

    @Test fun rssAndPublicApiRecordsNeedDirectAttributionButNotProviderCitations() {
        val rss = sourced().copy(acquisition = FeedAcquisition.PUBLISHER_RSS, authorship = FeedAuthorship.PUBLISHER)
        val api = sourced().copy(acquisition = FeedAcquisition.PUBLIC_API, authorship = FeedAuthorship.PUBLISHER)
        FeedLogic.validateItem(rss)
        FeedLogic.validateItem(api)

        assertFails { FeedLogic.validateItem(rss.copy(sourceName = "")) }
        assertFails { FeedLogic.validateItem(api.copy(sourceUrl = "")) }
    }

    @Test fun bothAiGroundedAcquisitionKindsRequireEvidenceNotFurtherReading() {
        for (acquisition in listOf(FeedAcquisition.AI_WEB_GROUNDED, FeedAcquisition.AI_SOURCE_GROUNDED)) {
            val grounded = sourced().copy(
                acquisition = acquisition,
                authorship = FeedAuthorship.AI,
                aiGenerated = true,
            )
            assertFails { FeedLogic.validateItem(grounded.copy(furtherReading = listOf(FeedCitation("Read more", "https://example.com/more")))) }
            FeedLogic.validateItem(grounded.copy(citations = listOf(FeedCitation("Consulted report", "https://example.com/evidence"))))
            assertFails { FeedLogic.validateItem(grounded.copy(citations = emptyList(), furtherReading = emptyList())) }
            assertFails { FeedLogic.validateItem(grounded.copy(aiGenerated = false, citations = listOf(FeedCitation("Evidence", "https://example.com/evidence")))) }
        }
    }

    @Test fun originalReadIsAFullAiAuthoredReadWithoutPublisherDateOrUrl() {
        val read = originalRead()
        FeedLogic.validateItem(read)
        assertEquals(read.sections.sumOf { it.body.trim().length }, FeedLogic.originalReadBodyChars(read))
        assertTrue(FeedLogic.originalReadBodyChars(read) >= FeedLogic.ORIGINAL_READ_MIN_BODY_CHARS_098)
        val restored = Json.decodeFromString<FeedState>(Json.encodeToString(FeedState(items = listOf(read))))
        assertEquals(read, FeedLogic.validate(restored).items.single())

        assertFails { FeedLogic.validateItem(read.copy(sourceUrl = "https://example.com/fake")) }
        assertFails { FeedLogic.validateItem(read.copy(sourceUrl = "   ")) }
        assertFails { FeedLogic.validateItem(read.copy(sourceName = "Invented Publisher")) }
        assertFails { FeedLogic.validateItem(read.copy(publishedAtMillis = 1L)) }
        assertFails { FeedLogic.validateItem(read.copy(fetchedAtMillis = 0L)) }
        assertFails { FeedLogic.validateItem(read.copy(deadline = "2026-10-31")) }
        assertFails { FeedLogic.validateItem(read.copy(kind = "weather")) }
        assertFails { FeedLogic.validateItem(read.copy(kind = "horoscope")) }
        assertFails { FeedLogic.validateItem(read.copy(acquisition = FeedAcquisition.LEGACY_UNKNOWN)) }
        assertFails { FeedLogic.validateItem(read.copy(authorship = FeedAuthorship.PUBLISHER)) }
        assertFails { FeedLogic.validateItem(read.copy(aiGenerated = false)) }
    }

    @Test fun teaserOrTooFewSectionsCannotStandInForAnOriginalRead() {
        val read = originalRead()
        assertFails { FeedLogic.validateItem(read.copy(sections = read.sections.take(1))) }
        assertFails {
            FeedLogic.validateItem(read.copy(sections = read.sections.map { it.copy(body = "Brief teaser text only.") }))
        }
        assertFails {
            FeedLogic.validateItem(read.copy(sections = read.sections + List(3) { index ->
                FeedContentSection("Extra $index", "x".repeat(180))
            }))
        }
    }

    @Test fun originalReadBodyAndCountAreBoundedAtStateBoundary() {
        val maxLength = originalRead().copy(sections = listOf(
            FeedContentSection("First", "a".repeat(6_000)),
            FeedContentSection("Second", "b".repeat(6_000)),
        ))
        FeedLogic.validateItem(maxLength)
        val tooLong = originalRead().copy(sections = listOf(
            FeedContentSection("One", "a".repeat(6_000)),
            FeedContentSection("Two", "b".repeat(6_001)),
        ))
        assertFails { FeedLogic.validateItem(tooLong) }

        val excessive = (0..FeedLogic.ORIGINAL_READ_MAX_COUNT_098).map { index ->
            originalRead().copy(id = "read-$index")
        }
        assertFails { FeedLogic.validate(FeedState(items = excessive)) }

        val excessiveBodyBytes = (0 until 84).map { index ->
            originalRead().copy(
                id = "large-read-$index",
                sections = listOf(FeedContentSection("Part one", "a".repeat(6_000)), FeedContentSection("Part two", "b".repeat(6_000))),
            )
        }
        assertFails { FeedLogic.validate(FeedState(items = excessiveBodyBytes)) }

        val multibyteRead = originalRead().copy(sections = listOf(
            FeedContentSection("Kwanza", "🌿".repeat(3_000)),
            FeedContentSection("Pili", "📖".repeat(3_000)),
        ))
        assertEquals(12_000, FeedLogic.originalReadBodyChars(multibyteRead))
        assertEquals(24_000, FeedLogic.originalReadBodyBytes(multibyteRead))
        assertFails {
            FeedLogic.validate(FeedState(items = (0 until 42).map { index -> multibyteRead.copy(id = "sw-read-$index") }))
        }
    }

    @Test fun changedReadContentOrProvenanceInvalidatesItemAiDigests() {
        val item = sourced()
        val suggestionDigest = AiSuggestedInteractions095.contentDigest(item)
        val relevanceDigest = AiRelevance094.contentDigest(item)

        val sectionChanged = item.copy(sections = listOf(FeedContentSection("Context", "Additional material")))
        val provenanceChanged = item.copy(acquisition = FeedAcquisition.PUBLIC_API)

        assertNotEquals(suggestionDigest, AiSuggestedInteractions095.contentDigest(sectionChanged))
        assertNotEquals(relevanceDigest, AiRelevance094.contentDigest(sectionChanged))
        assertNotEquals(suggestionDigest, AiSuggestedInteractions095.contentDigest(provenanceChanged))
        assertNotEquals(relevanceDigest, AiRelevance094.contentDigest(provenanceChanged))
    }

    @Test fun unknownLegacyAcquisitionDoesNotPermitBlankSourceExceptStrictOriginalRead() {
        val noSourceArticle = sourced().copy(sourceUrl = "", acquisition = FeedAcquisition.LEGACY_UNKNOWN)
        assertFails { FeedLogic.validateItem(noSourceArticle) }
    }

    @Test fun originalReadCanCarryItsIndependentCompleteFiveQuestionSet() {
        val module = FeedModule(id = "technology", kind = "technology", query = "practical software", enabled = true)
        val state = FeedState(modules = listOf(module), connection = AiConnection(model = "gpt-6-luna"))
        val read = originalRead()
        val questions = listOf(
            AiSuggestedInteraction(label = "Deployment timeline", prompt = "What does the reading say about the deployment timeline?"),
            AiSuggestedInteraction(label = "Evidence quality", prompt = "Which evidence or mechanism supports the main claim?"),
            AiSuggestedInteraction(label = "Practical limits", prompt = "What limitation would change whether this approach is useful?"),
            AiSuggestedInteraction(label = "Apply the idea", prompt = "How might someone apply this idea in a small first experiment?"),
            AiSuggestedInteraction(label = "Compare alternatives", prompt = "What alternative approach would be useful to compare, and why?"),
        )
        val prepared = read.copy(
            suggestedInteractions = questions,
            suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(read),
            suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(state, module, "en"),
            suggestedInteractionsGeneratedAtMillis = read.fetchedAtMillis,
        )

        FeedLogic.validateItem(prepared)
        assertTrue(AiSuggestedInteractions095.isCompleteSet(prepared.suggestedInteractions))
        assertTrue(AiSuggestedInteractions095.isApplicable(state.copy(items = listOf(prepared)), prepared, read.fetchedAtMillis, "en"))

        val malformed = prepared.copy(sections = prepared.sections.map { it.copy(body = "Brief teaser only.") })
        assertFalse(FeedLogic.isValidOriginalReadShape(malformed))
        assertFails { FeedLogic.validateItem(malformed) }
    }

    @Test fun readBudgetRejectsOnlyTheOptionalCandidateWithoutEvictingExistingState() {
        val existing = (0 until 83).map { index -> largeOriginalRead("budget-$index") }
        val state = FeedState(items = existing)
        FeedLogic.validate(state)

        assertFalse(FeedLogic.canAcceptOriginalRead(state, largeOriginalRead("new-read")))
        assertTrue(FeedLogic.canAcceptOriginalRead(state, originalRead().copy(id = existing.first().id)))
        assertFalse(FeedLogic.canAcceptOriginalRead(state, originalRead().copy(
            id = "invalid-read", sections = listOf(FeedContentSection("Short", "Teaser only.")),
        )))
        assertEquals(existing, state.items)
        assertEquals(996_000, existing.sumOf(FeedLogic::originalReadBodyBytes))
    }

    private fun sourced() = FeedItem(
        id = "item", moduleId = "technology", title = "A source-backed update",
        summary = "A useful source summary.", sourceName = "Official Publisher",
        sourceUrl = "https://example.com/story", publishedAtMillis = 1_000, fetchedAtMillis = 2_000,
    )

    private fun originalRead() = FeedItem(
        id = "read", moduleId = "technology", kind = "technology", contentType = "original_read",
        title = "A useful original reading", summary = "A short teaser for a much longer reading.",
        aiGenerated = true, acquisition = FeedAcquisition.ORIGINAL_READ, authorship = FeedAuthorship.AI,
        sourceUrl = "", sourceName = "", publishedAtMillis = 0, fetchedAtMillis = 2_000,
        sections = listOf(
            FeedContentSection("Why this matters", "A grounded reading section that develops a real idea with enough detail to be useful. ".repeat(6)),
            FeedContentSection("A practical way to apply it", "A second grounded section that describes how to put the idea into practice and what to watch for. ".repeat(6)),
        ),
    )

    private fun largeOriginalRead(id: String) = originalRead().copy(
        id = id,
        sections = listOf(
            FeedContentSection("First section", "a".repeat(6_000)),
            FeedContentSection("Second section", "b".repeat(6_000)),
        ),
    )

    private inline fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
