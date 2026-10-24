package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.*
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
        var askAction: suspend () -> AiAnswer = { AiAnswer("A test answer.") }
        val askCalls = AtomicInteger()
        override suspend fun check(connection: AiConnection, secret: String) { checkAction() }
        override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = feedAction()
        override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
            askCalls.incrementAndGet()
            return askAction()
        }
    }
}
