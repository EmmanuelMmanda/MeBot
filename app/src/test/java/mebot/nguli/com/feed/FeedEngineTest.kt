package mebot.nguli.com.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repository: FeedRepository
    private lateinit var vault: MemoryVault
    private val job = SupervisorJob()
    private val provider = ControlledProvider()
    private val news = FeedModule(id = "news", kind = "news")

    @Before fun setUp() {
        val directory = temporaryFolder.newFolder("files")
        val application = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        repository = FeedRepository(context, CoroutineScope(Dispatchers.IO + job))
        vault = MemoryVault(context)
    }

    @After fun tearDown() = runBlocking { job.cancelAndJoin() }

    @Test fun failedCredentialChangeKeepsTheWorkingConnection() = runBlocking {
        vault.value = "existing-test-credential"
        val before = repository.current()
        provider.checkAction = { throw FeedFailure("unauthorized") }
        val failure = runCatching { engine().connect(AiConnection(model = "unavailable-model"), "replacement-test-credential") }.exceptionOrNull()
        assertEquals("unauthorized", (failure as FeedFailure).code)
        assertEquals("existing-test-credential", vault.value)
        assertEquals(before.connection, repository.current().connection)
    }

    @Test fun explicitModelChecksRespectTheLocalRequestCapAndKeepWorkingCredentials() = runBlocking {
        vault.value = "existing-test-credential"
        val before = repository.current()
        repository.update { it.copy(dailyRequestLimit = 1) }
        var checks = 0
        provider.checkAction = { checks++; throw FeedFailure("invalid_response") }
        val engine = engine()
        assertEquals("invalid_response", (runCatching { engine.connect(AiConnection(), "replacement-test-credential") }.exceptionOrNull() as FeedFailure).code)
        assertEquals("limit_reached", (runCatching { engine.connect(AiConnection(), "replacement-test-credential") }.exceptionOrNull() as FeedFailure).code)
        assertEquals(1, checks)
        assertEquals(1, repository.current().requestsToday)
        assertEquals(before.connection, repository.current().connection)
        assertEquals("existing-test-credential", vault.value)
    }

    @Test fun aPartialSourceFailurePreservesCachedSavedItemsAndNewResults() = runBlocking {
        val cached = article("cached")
        val incoming = article("new")
        val configured = news.copy(sources = listOf(
            FeedSource("Good", "https://example.com/good.xml"),
            FeedSource("Unavailable", "https://example.com/unavailable.xml"),
        ))
        repository.update { it.copy(modules = listOf(configured), items = listOf(cached), savedIds = setOf(cached.id)) }
        val reader = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                if (source.name == "Unavailable") throw FeedFailure("offline")
                return listOf(incoming)
            }
        }
        val outcome = engine(reader).refresh()
        assertEquals("partial_refresh", outcome.notice)
        assertEquals("offline", outcome.error)
        assertEquals(1, outcome.newItems)
        assertEquals(setOf("cached", "new"), repository.current().items.map { it.id }.toSet())
        assertEquals(setOf("cached"), repository.current().savedIds)
    }

    @Test fun aiFitEnrichesAPublicCandidateInsideTheExistingBudgetedRequestAfterPublicCommit() = runBlocking {
        vault.value = "test-credential"
        val candidate = article("public-candidate")
        val configured = news.copy(sources = listOf(FeedSource("Example", "https://example.com/feed.xml")))
        repository.update { it.copy(modules = listOf(configured), dailyRequestLimit = 1) }
        val reader = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(candidate)
        }
        provider.discoverAction = { candidates ->
            assertEquals(listOf(candidate.id), candidates.map { it.id })
            // Public content is committed before the selected provider's optional ranking output arrives.
            assertTrue(repository.current().items.any { it.id == candidate.id })
            FeedDiscoveryResult(emptyList(), listOf(FeedCandidateFit(candidate.id, 90)), listOf(
                FeedCandidateInteractions(candidate.id, listOf(AiSuggestedInteraction(
                    label = "Check the source date", prompt = "Which publication date does the source show?"))))
            )
        }

        engine(reader).refresh()

        assertEquals(1, provider.discoveryCalls.get())
        assertEquals(1, repository.current().requestsToday) // Ranking piggybacks; it reserves no extra request.
        val enriched = repository.current().items.single { it.id == candidate.id }
        assertEquals(90, enriched.aiRelevance?.fitEstimate)
        assertEquals(AiRelevance094.contentDigest(enriched), enriched.aiRelevance?.contentDigest)
        assertEquals(AiRelevance094.scopeProviderDigest(repository.current(), configured), enriched.aiRelevance?.scopeProviderDigest)
        assertEquals("Which publication date does the source show?", enriched.suggestedInteractions.single().prompt)
        assertTrue(AiSuggestedInteractions095.isApplicable(repository.current(), enriched, System.currentTimeMillis(), "en"))
    }

    @Test fun malformedCandidateFitsAreRejectedAsAWholeWithoutClamping() = runBlocking {
        vault.value = "test-credential"
        val candidate = article("public-candidate-bad")
        val configured = news.copy(sources = listOf(FeedSource("Example", "https://example.com/feed.xml")))
        (0..2).forEach { index ->
            val submitted = candidate.copy(id = "${candidate.id}-$index", sourceUrl = "https://example.com/bad-$index")
            val badFits = when (index) {
                0 -> listOf(FeedCandidateFit("unknown-id", 90))
                1 -> listOf(FeedCandidateFit(submitted.id, 90), FeedCandidateFit(submitted.id, 80))
                else -> listOf(FeedCandidateFit(submitted.id, 101))
            }
            repository.update { it.copy(modules = listOf(configured), items = emptyList(), requestsToday = 0,
                dailyRequestLimit = RequestBudget.UNLIMITED, usageDate = FeedLogic.dateAt(System.currentTimeMillis(), it.timezone)) }
            provider.discoverAction = { FeedDiscoveryResult(emptyList(), badFits) }
            val reader = object : SourceReader {
                override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(submitted)
            }
            engine(reader).refresh()
            assertNull(repository.current().items.single().aiRelevance)
        }
    }

    @Test fun replacingCredentialsForTheSameConnectionClearsDerivedFitAndQuestionsOnlyAfterSuccessfulCheck() = runBlocking {
        val item = article("rank-cached")
        val connection = AiConnection()
        val base = FeedState(modules = listOf(news), connection = connection)
        val estimate = AiRelevanceEstimate(80, System.currentTimeMillis(), AiRelevance094.contentDigest(item),
            AiRelevance094.scopeProviderDigest(base, news))
        val interaction = AiSuggestedInteraction(label = "Check the source", prompt = "What does the source say about this update?")
        val cachedItem = item.copy(aiRelevance = estimate, suggestedInteractions = listOf(interaction),
            suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(item),
            suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(base, news, "en"),
            suggestedInteractionsGeneratedAtMillis = System.currentTimeMillis())
        repository.update { it.copy(modules = listOf(news), connection = connection, items = listOf(cachedItem)) }
        vault.value = "previous-test-credential"
        provider.checkAction = { throw FeedFailure("unauthorized") }

        assertEquals("unauthorized", (runCatching { engine().connect(connection, "replacement-test-credential") }.exceptionOrNull() as FeedFailure).code)
        assertEquals(80, repository.current().items.single().aiRelevance?.fitEstimate)
        assertEquals(listOf(interaction), repository.current().items.single().suggestedInteractions)

        provider.checkAction = {}
        engine().connect(connection, "replacement-test-credential")
        assertEquals("replacement-test-credential", vault.value)
        assertNull(repository.current().items.single().aiRelevance)
        assertTrue(repository.current().items.single().suggestedInteractions.isEmpty())
    }

    @Test fun unchangedPublicCandidateReusesItsCachedFitAfterASecondSourceMerge() = runBlocking {
        vault.value = "test-credential"
        val candidate = article("same-public-candidate")
        val configured = news.copy(sources = listOf(FeedSource("Example", "https://example.com/feed.xml")))
        repository.update { it.copy(modules = listOf(configured), dailyRequestLimit = RequestBudget.UNLIMITED) }
        val reader = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(candidate)
        }
        val requestedIds = mutableListOf<List<String>>()
        provider.discoverAction = { candidates ->
            requestedIds += candidates.map { it.id }
            FeedDiscoveryResult(emptyList(),
                if (candidates.isEmpty()) emptyList() else listOf(FeedCandidateFit(candidate.id, 80)),
                if (candidates.isEmpty()) emptyList() else listOf(FeedCandidateInteractions(candidate.id, listOf(
                    AiSuggestedInteraction(label = "Check the update", prompt = "What change does this source announce?")))),
            )
        }
        val engine = engine(reader)

        engine.refresh()
        assertEquals(80, repository.current().items.single().aiRelevance?.fitEstimate)
        engine.refresh()

        assertEquals(listOf(listOf(candidate.id), emptyList<String>()), requestedIds)
        assertEquals(2, repository.current().requestsToday) // One ordinary discovery request per refresh, no ranking add-on.
        assertEquals(80, repository.current().items.single().aiRelevance?.fitEstimate)
    }

    @Test fun lateFitAndQuestionSuggestionsAreDiscardedAfterContentOrContextChangesLogoutOrSave() = runBlocking {
        val configured = news.copy(sources = listOf(FeedSource("Example", "https://example.com/feed.xml")))
        listOf("content", "context", "logout", "save", "star").forEach { change ->
            vault.value = "test-credential"
            val candidate = article("late-fit-$change")
            repository.update { it.copy(modules = listOf(configured), items = emptyList(), savedIds = emptySet(), hiddenIds = emptySet(),
                context = emptyList(), starredStories = emptyList(), dailyRequestLimit = RequestBudget.UNLIMITED,
                usageDate = FeedLogic.dateAt(System.currentTimeMillis(), it.timezone), requestsToday = 0) }
            val entered = CompletableDeferred<List<FeedItem>>()
            val release = CompletableDeferred<Unit>()
            provider.discoverAction = { candidates ->
                entered.complete(candidates)
                release.await()
                FeedDiscoveryResult(emptyList(), listOf(FeedCandidateFit(candidate.id, 95)), listOf(
                    FeedCandidateInteractions(candidate.id, listOf(AiSuggestedInteraction(
                        label = "Check the deadline", prompt = "Which deadline does the source state?")))))
            }
            val reader = object : SourceReader {
                override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(candidate)
            }
            // Each iteration has a fresh refresh operation while retaining the same test repository/provider.
            val currentEngine = engine(reader)
            val refresh = async { currentEngine.refresh() }
            val submitted = withTimeout(5000) { entered.await() }
            assertEquals(listOf(candidate.id), submitted.map { it.id })
            when (change) {
                "content" -> repository.update { state -> state.copy(items = state.items.map { if (it.id == candidate.id) it.copy(summary = "Edited while provider was working") else it }) }
                "context" -> repository.update { it.copy(context = listOf(ContextEntry(id = "changed", text = "Changed confirmed goal", scopes = setOf("news")))) }
                "logout" -> currentEngine.disconnect()
                "save" -> repository.update { it.copy(savedIds = it.savedIds + candidate.id) }
                "star" -> repository.update { it.copy(starredStories = it.starredStories + StarredStory(candidate.id, starredAtMillis = System.currentTimeMillis())) }
            }
            release.complete(Unit)
            refresh.await()
            val latest = repository.current()
            val result = latest.items.single { it.id == candidate.id }
            assertNull("late fit committed after $change change", result.aiRelevance)
            assertTrue("late suggestion committed after $change change", result.suggestedInteractions.isEmpty())
            if (change == "save") assertTrue(candidate.id in latest.savedIds)
            if (change == "star") assertTrue(latest.starredStories.any { it.itemId == candidate.id })
        }
    }

    @Test fun cancelledSuggestionRequestNeverPersistsPartialOrLateQuestions() = runBlocking {
        vault.value = "test-credential"
        val candidate = article("cancelled-interactions")
        val configured = news.copy(sources = listOf(FeedSource("Example", "https://example.com/feed.xml")))
        repository.update { it.copy(modules = listOf(configured), items = emptyList(), requestsToday = 0,
            dailyRequestLimit = RequestBudget.UNLIMITED, usageDate = FeedLogic.dateAt(System.currentTimeMillis(), it.timezone)) }
        val entered = CompletableDeferred<List<FeedItem>>()
        provider.discoverAction = { candidates ->
            entered.complete(candidates)
            awaitCancellation()
        }
        val reader = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(candidate)
        }

        val refresh = async { engine(reader).refresh() }
        assertEquals(listOf(candidate.id), withTimeout(5000) { entered.await() }.map { it.id })
        refresh.cancelAndJoin()

        val latest = repository.current()
        val published = latest.items.single { it.id == candidate.id }
        assertTrue("public source data stays readable", published.title.isNotBlank() && published.sourceUrl == candidate.sourceUrl)
        assertTrue(published.suggestedInteractions.isEmpty())
        assertNull(published.aiRelevance)
        assertEquals(1, latest.requestsToday) // Only the existing discovery reservation; never a second suggestion request.
    }

    @Test fun refreshRejectsAnAiResultAfterItsPersonalContextChanges() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vault.value = "test-credential"
        repository.update { it.copy(modules = listOf(news)) }
        provider.feedAction = { entered.complete(Unit); release.await(); listOf(article("stale")) }
        val refresh = async { engine().refresh() }
        withTimeout(5000) { entered.await() }
        repository.update { it.copy(context = listOf(ContextEntry(id = "new-context", text = "A corrected priority"))) }
        release.complete(Unit)
        refresh.await()
        assertTrue(repository.current().items.isEmpty())
        assertEquals("A corrected priority", repository.current().context.single().text)
    }

    @Test fun disablingAModuleDuringSourceRefreshPreventsItReappearing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.update { it.copy(modules = listOf(news.copy(sources = listOf(FeedSource("Example", "https://example.com/rss"))))) }
        val reader = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                entered.complete(Unit); release.await(); return listOf(article("late-source"))
            }
        }
        val refresh = async { engine(reader).refresh() }
        withTimeout(5000) { entered.await() }
        repository.update { it.copy(modules = it.modules.map { m -> m.copy(enabled = false) }) }
        release.complete(Unit)
        refresh.await()
        assertTrue(repository.current().items.isEmpty())
        assertTrue(FeedLogic.visible(repository.current()).isEmpty())
    }

    @Test fun concurrentQuestionsCannotExceedTheDailyRequestLimit() = runBlocking {
        vault.value = "test-credential"
        val date = FeedLogic.dateAt(System.currentTimeMillis(), "Africa/Dar_es_Salaam")
        repository.update { it.copy(timezone = "Africa/Dar_es_Salaam", dailyRequestLimit = 3, usageDate = date) }
        val engine = engine()
        val results = (1..12).map { async { runCatching { engine.ask(null, "Explain the update") } } }.awaitAll()
        assertEquals(3, results.count { it.isSuccess })
        assertEquals(3, provider.askCalls.get())
        assertEquals(3, repository.current().requestsToday)
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as? FeedFailure)?.code == "limit_reached" })
    }

    @Test fun aNewLocalDateResetsUsageBeforeTheNextRequest() = runBlocking {
        vault.value = "test-credential"
        repository.update { it.copy(timezone = "Africa/Dar_es_Salaam", dailyRequestLimit = 1, usageDate = "2000-01-01", requestsToday = 1) }
        engine().ask(null, "Explain the update")
        assertEquals(1, repository.current().requestsToday)
        assertEquals(FeedLogic.dateAt(System.currentTimeMillis(), "Africa/Dar_es_Salaam"), repository.current().usageDate)
    }

    @Test fun unlimitedAllowsConcurrentQuestionsBeyondTheOldStorageCeilingAndStillCountsThem() = runBlocking {
        vault.value = "test-credential"
        val date = FeedLogic.dateAt(System.currentTimeMillis(), "Africa/Dar_es_Salaam")
        repository.update { it.copy(timezone = "Africa/Dar_es_Salaam", dailyRequestLimit = RequestBudget.UNLIMITED,
            usageDate = date, requestsToday = 10_000) }
        val engine = engine()
        val answers = (1..8).map { async { engine.ask(null, "Explain the update") } }.awaitAll()
        assertEquals(8, answers.size)
        assertEquals(8, provider.askCalls.get())
        assertEquals(10_008, repository.current().requestsToday)
    }

    @Test fun largerCustomLimitsRetainAtomicEnforcementAtTheirBoundary() = runBlocking {
        vault.value = "test-credential"
        val date = FeedLogic.dateAt(System.currentTimeMillis(), "UTC")
        repository.update { it.copy(timezone = "UTC", dailyRequestLimit = 500, usageDate = date, requestsToday = 499) }
        val engine = engine()
        val results = (1..6).map { async { runCatching { engine.ask(null, "Explain the update") } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, provider.askCalls.get())
        assertEquals(500, repository.current().requestsToday)
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as? FeedFailure)?.code == "limit_reached" })
    }

    @Test fun invalidQuestionsDoNotConsumeUsage() = runBlocking {
        vault.value = "test-credential"
        val engine = engine()
        assertEquals("invalid_input", (runCatching { engine.ask(null, " ") }.exceptionOrNull() as FeedFailure).code)
        assertEquals("invalid_input", (runCatching { engine.ask("missing-item", "Explain") }.exceptionOrNull() as FeedFailure).code)
        assertEquals(0, provider.askCalls.get())
        assertEquals(0, repository.current().requestsToday)
    }

    @Test fun disconnectDropsAnInFlightPersonalizedFeedResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vault.value = "test-credential"
        repository.update { it.copy(modules = listOf(news)) }
        provider.feedAction = { entered.complete(Unit); release.await(); listOf(article("late-ai")) }
        val engine = engine()
        val refresh = async { engine.refresh() }
        withTimeout(5000) { entered.await() }
        engine.disconnect()
        release.complete(Unit)
        refresh.await()
        assertNull(vault.value)
        assertTrue(repository.current().items.isEmpty())
        assertEquals(0L, repository.current().lastAiRefreshMillis)
    }

    @Test fun rotatingCredentialsDropsAnInFlightAnswer() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vault.value = "previous-test-credential"
        provider.askAction = { entered.complete(Unit); release.await(); AiAnswer("An obsolete response.") }
        val engine = engine()
        val answer = async { runCatching { engine.ask(null, "Explain this update") } }
        withTimeout(5000) { entered.await() }
        engine.connect(AiConnection(), "replacement-test-credential")
        release.complete(Unit)
        assertEquals("invalid_input", (answer.await().exceptionOrNull() as FeedFailure).code)
        assertEquals("replacement-test-credential", vault.value)
    }

    @Test fun completedModuleIsVisibleWhileAnotherModuleStillRunsAndFinalFailureIsReported() = runBlocking {
        val slow=CompletableDeferred<Unit>()
        val started=CompletableDeferred<Unit>()
        val module=news.copy(sources=listOf(FeedSource("Fast","https://example.com/fast")))
        val second=FeedModule(id="slow",kind="technology",sources=listOf(FeedSource("Slow","https://example.com/slow")))
        repository.update { it.copy(modules=listOf(module,second)) }
        val reader=object:SourceReader {
            override suspend fun read(source:FeedSource,module:FeedModule,now:Long):List<FeedItem> {
                if(module.id=="slow"){started.complete(Unit);slow.await();throw FeedFailure("offline")}
                return listOf(article("new-live"))
            }
        }
        val e=engine(reader);val job=async{e.refresh()}
        // Durable content and progress are published separately; observe both before asserting.
        // The slow source remains suspended throughout this bounded wait.
        withTimeout(5000) {
            started.await()
            while (repository.current().items.none { it.id == "new-live" } ||
                "news" !in e.progress.value.completed || e.progress.value.newItems != 1) delay(10)
        }
        assertTrue(e.progress.value.running)
        assertTrue(e.progress.value.completed.contains("news"))
        assertEquals(1,e.progress.value.newItems)
        assertFalse(job.isCompleted)
        slow.complete(Unit)
        assertEquals("partial_refresh",job.await().notice)
        assertFalse(e.progress.value.running)
        assertTrue(e.progress.value.failed.contains("slow"))
        assertTrue(repository.current().items.any{it.id=="new-live"})
    }

    @Test fun feedbackChangesInvalidateInFlightPersonalizedResults() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        vault.value="test-credential"
        repository.update { it.copy(modules=listOf(news),items=listOf(article("feedback-seed"))) }
        provider.feedAction={entered.complete(Unit);release.await();listOf(article("stale-vote-result"))}
        val e=engine();val job=async { e.refresh() }
        withTimeout(5000){entered.await()}
        repository.update { RelevanceFeedback.record(it,"feedback-seed",false,System.currentTimeMillis()) }
        release.complete(Unit);job.await()
        assertTrue(repository.current().items.none{it.id=="stale-vote-result"})
        assertFalse(e.progress.value.running)
        assertEquals(false,RelevanceFeedback.current(repository.current(),article("feedback-seed")))
    }

    private fun engine(reader: SourceReader = object : SourceReader {
        override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = emptyList<FeedItem>()
    }) = FeedEngine(repository, vault, reader, provider)

    private fun article(id: String) = FeedItem(
        id = id, moduleId = "news", title = "Synthetic update $id", summary = "Test-only content.",
        sourceUrl = "https://example.com/$id", publishedAtMillis = 1_790_000_000_000,
        fetchedAtMillis = 1_790_000_010_000,
    )

    private class MemoryVault(context: Context) : SecretVault(context) {
        var value: String? = null
        override fun read() = value
        override fun write(secret: String) { value = secret }
        override fun clear() { value = null }
    }

    private class ControlledProvider : AiProvider {
        var checkAction: suspend () -> Unit = {}
        var feedAction: suspend () -> List<FeedItem> = { emptyList() }
        var discoverAction: (suspend (List<FeedItem>) -> FeedDiscoveryResult)? = null
        var askAction: suspend () -> AiAnswer = { AiAnswer("A test answer.") }
        val askCalls = AtomicInteger()
        val discoveryCalls = AtomicInteger()
        override suspend fun check(connection: AiConnection, secret: String) { checkAction() }
        override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = feedAction()
        override suspend fun discover(state: FeedState, module: FeedModule, secret: String, language: String, candidates: List<FeedItem>): FeedDiscoveryResult {
            discoveryCalls.incrementAndGet()
            return discoverAction?.invoke(candidates) ?: FeedDiscoveryResult(feedAction())
        }
        override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
            askCalls.incrementAndGet()
            return askAction()
        }
    }
}
