package mebot.nguli.com.feed

import kotlinx.serialization.json.*
import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test

class OriginalReads098Test {
    private val module = FeedModule(id = "news", kind = "news", enabled = true)
    private val state = FeedState(modules = listOf(module))
    private val now = 1_793_100_000_000L
    private val source = FeedCitation("Example documentation", "https://example.org/guide")
    private fun reading(basis: String = "reflection", evidence: List<String> = emptyList(), further: List<String> = emptyList()) = buildJsonObject {
        put("title", "Make a small workflow experiment")
        put("summary", "A synthetic reading about choosing one manageable workflow experiment.")
        put("why", "A practical reflection for the selected technical topic.")
        put("basis", basis)
        put("sections", buildJsonArray {
            add(buildJsonObject {
                put("heading", "Choose the friction")
                put("body", "Pick one recurring interruption from your own work and describe what happens before and after it. ".repeat(7))
            })
            add(buildJsonObject {
                put("heading", "Try a reversible change")
                put("body", "Choose a small reversible experiment, keep your existing work safe, and review whether it helped before expanding it. ".repeat(7))
            })
        })
        put("evidenceUrls", JsonArray(evidence.map(::JsonPrimitive)))
        put("furtherReadingUrls", JsonArray(further.map(::JsonPrimitive)))
        put("suggestedInteractions", JsonArray(emptyList()))
    }
    private fun parse(row: JsonObject, sources: List<FeedCitation> = emptyList()) = OriginalReads098.parse(
        JsonArray(listOf(row)), state, module, "en", now,
        sources.associateBy { FeedLogic.canonicalUrl(it.url) }, { emptyList() },
    )

    @Test fun reflectionHasRealBodyAndCreationTimeWithoutInventedPublisher() {
        val item = parse(reading()).single()
        assertTrue(FeedLogic.isValidOriginalReadShape(item))
        assertEquals(FeedAcquisition.ORIGINAL_READ, item.acquisition)
        assertEquals(FeedAuthorship.AI, item.authorship)
        assertEquals("", item.sourceName)
        assertEquals("", item.sourceUrl)
        assertEquals(0L, item.publishedAtMillis)
        assertEquals(now, item.fetchedAtMillis)
        assertEquals(2, item.sections.size)
        assertTrue(item.id.startsWith("read-"))
        assertEquals(item.id, parse(reading()).single().id)
    }

    @Test fun consultedEvidenceIsRequiredForGroundedReadingAndFurtherReadingIsSeparate() {
        assertTrue(parse(reading("source_grounded", further = listOf(source.url)), listOf(source)).isEmpty())
        val item = parse(reading("source_grounded", listOf(source.url), listOf(source.url)), listOf(source)).single()
        assertEquals(listOf(source), item.citations)
        assertEquals(listOf(source), item.furtherReading)
        assertTrue(parse(reading("source_grounded", listOf("https://unconsulted.example/claim")), listOf(source)).isEmpty())
        assertTrue(parse(reading(further = listOf("file:///private/document"))).isEmpty())
    }

    @Test fun malformedOrExtraClaimFieldsDoNotBecomeARead() {
        assertTrue(parse(JsonObject(reading() + ("publishedAt" to JsonPrimitive("2026-10-27")))).isEmpty())
        val short = JsonObject(reading() + ("sections" to buildJsonArray {
            repeat(2) { add(buildJsonObject { put("heading", "Short"); put("body", "Only a teaser.") }) }
        }))
        assertTrue(parse(short).isEmpty())
        assertTrue(parse(JsonObject(reading() + ("summary" to JsonPrimitive("Visit https://invented.example/read")))).isEmpty())
    }

    @Test fun currentCommitBudgetSkipsOnlyOptionalNewReadAndNeverEvictsSavedContent() {
        val reading = parse(reading()).single()
        val existing = (0 until 128).map { reading.copy(id = "read-existing-$it") }
        val full = state.copy(items = existing, savedIds = setOf(existing.first().id))
        val source = FeedItem("source-new", module.id, "A public update", "Synthetic source summary",
            sourceName = "Example", sourceUrl = "https://example.org/update", fetchedAtMillis = now)
        assertEquals(listOf(source), OriginalReads098.withinBudget(full, listOf(reading, source)))
        assertEquals(existing, full.items)
        assertEquals(setOf(existing.first().id), full.savedIds)
        val replacement = reading.copy(id = existing.first().id)
        assertEquals(listOf(replacement), OriginalReads098.withinBudget(full, listOf(replacement)))
    }

    @Test fun absentLegacyOutputAndUnsupportedModulesDoNotGenerateOrPad() {
        assertTrue(OriginalReads098.parse(null, state, module, "en", now, emptyMap(), { emptyList() }).isEmpty())
        for (kind in listOf("weather", "horoscope")) {
            val excluded = module.copy(kind = kind)
            assertTrue(OriginalReads098.parse(JsonArray(listOf(reading())), state, excluded, "en", now, emptyMap(), { emptyList() }).isEmpty())
        }
        assertTrue(OriginalReads098.parse(JsonArray(listOf(reading(), reading())), state, module, "en", now, emptyMap(), { emptyList() }).isEmpty())
    }
}
