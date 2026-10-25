package mebot.nguli.com.feed

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiActivityRegistryTest {
    @Test fun truthfulStagesAndOnlyPublishedSummariesAreExposed() = runBlocking {
        AiActivityRegistry.run("test-item", "music", "answer") {
            AiActivityRegistry.observe("response.web_search_call.searching", buildJsonObject {})
            assertEquals("searching", AiActivityRegistry.state.value.values.single().stage)
            AiActivityRegistry.observe("response.reasoning_text.delta", buildJsonObject { put("delta", "private raw reasoning") })
            assertEquals("", AiActivityRegistry.state.value.values.single().summary)
            AiActivityRegistry.observe("response.reasoning_summary_text.delta", buildJsonObject { put("delta", "Checking official music sources") })
            assertEquals("Checking official music sources", AiActivityRegistry.state.value.values.single().summary)
            AiActivityRegistry.observe("response.output_text.delta", buildJsonObject { put("delta", "Answer") })
            assertEquals("writing", AiActivityRegistry.state.value.values.single().stage)
        }
        assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
    @Test fun scopeIsIsolatedAcrossConcurrentRequests() = runBlocking {
        val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val a=launch { AiActivityRegistry.run("music", "music", "discovery") { ready.complete(Unit);release.await() } }
        ready.await()
        withTimeout(1000) { AiActivityRegistry.run("weather", "weather", "answer") {
            AiActivityRegistry.observe("response.web_search_call.searching",buildJsonObject {})
            assertEquals("searching",AiActivityRegistry.state.value.values.first{it.scope=="weather"}.stage)
            assertEquals("connecting",AiActivityRegistry.state.value.values.first{it.scope=="music"}.stage)
        } }
        release.complete(Unit);a.join();assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
    @Test fun cancellationAndFailuresReleaseCapacity() = runBlocking {
        val ready=CompletableDeferred<Unit>()
        val a=launch { AiActivityRegistry.run("cancel", "news", "answer") { ready.complete(Unit);awaitCancellation() } }
        ready.await();a.cancelAndJoin()
        assertTrue(AiActivityRegistry.state.value.isEmpty())
        try { AiActivityRegistry.run("error", "news", "answer") { throw IllegalStateException("test failure") } } catch(_:IllegalStateException) {}
        withTimeout(1000) { AiActivityRegistry.run("next", "news", "answer") { assertEquals(1,AiActivityRegistry.state.value.size) } }
        assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
    @Test fun summaryIsBounded() = runBlocking {
        AiActivityRegistry.run("bounded", "news", "answer") {
            AiActivityRegistry.observe("response.reasoning_summary_text.delta",buildJsonObject{put("delta","x".repeat(2000))})
            assertEquals(280,AiActivityRegistry.state.value.values.single().summary.length)
        }
    }
    @Test fun discoveryTextNeverClaimsToBeWritingAnAnswer() = runBlocking {
        AiActivityRegistry.run("opportunities", "opportunities", "discovery") {
            AiActivityRegistry.report("preparing", contextEntries = 2)
            AiActivityRegistry.observe("response.output_text.delta", buildJsonObject { put("delta", "{items:") })
            val a = AiActivityRegistry.state.value.values.single()
            assertEquals("summarizing", a.stage); assertEquals(2, a.contextEntries)
            assertNull(a.steps.last().count)
            AiActivityRegistry.report("ready", count = 4)
            assertEquals(4, AiActivityRegistry.state.value.values.single().steps.last().count)
        }
        assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
    @Test fun searchAndAnswerUseDifferentStages() = runBlocking {
        for (operation in listOf("search", "answer")) AiActivityRegistry.run("scope", "news", operation) {
            AiActivityRegistry.observe("response.output_text.delta", buildJsonObject {})
            assertEquals(if (operation == "search") "summarizing" else "writing", AiActivityRegistry.state.value.values.single().stage)
        }
    }
    @Test fun repeatedTokensDoNotGrowTheTimelineAndCompletionClearsIt() = runBlocking {
        AiActivityRegistry.run("bounded", "news", "discovery") {
            repeat(100) { AiActivityRegistry.observe("response.output_text.delta", buildJsonObject {}) }
            assertEquals(2, AiActivityRegistry.state.value.values.single().steps.size)
            repeat(30) { AiActivityRegistry.report("ready", count = it) }
            assertEquals(8, AiActivityRegistry.state.value.values.single().steps.size)
        }
        assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
    @Test fun progressCountsOnlyActualPublicTextDeltas() = runBlocking {
        AiActivityRegistry.run("progress", "news", "answer") {
            AiActivityRegistry.observe("response.reasoning_text.delta", buildJsonObject { put("delta", "private") })
            assertEquals(0, AiActivityRegistry.state.value.values.single().receivedCharacters)
            AiActivityRegistry.observe("response.output_text.delta", buildJsonObject { put("delta", "hello") })
            AiActivityRegistry.observe("response.output_text.delta", buildJsonObject { put("delta", " world") })
            assertEquals(11, AiActivityRegistry.state.value.values.single().receivedCharacters)
            AiActivityRegistry.report("ready", count = 2)
            assertEquals(2, AiActivityRegistry.state.value.values.single().steps.last().count)
        }
        assertTrue(AiActivityRegistry.state.value.isEmpty())
    }
}
