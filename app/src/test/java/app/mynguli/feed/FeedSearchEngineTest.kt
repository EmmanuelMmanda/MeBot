package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.*
import app.mynguli.feed.auth.ChatGptDiagnostic
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
class FeedSearchEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val job = SupervisorJob()
    private lateinit var repository: FeedRepository
    private lateinit var vault: MemoryVault
    private val provider = SearchProvider()
    private val news = FeedModule(id = "news", kind = "news")
    private val technology = FeedModule(id = "technology", kind = "technology")
    private val disabled = FeedModule(id = "disabled", kind = "security", enabled = false)
    private val weather = FeedModule(id = "weather", kind = "weather")
    private val horoscope = FeedModule(id = "horoscope", kind = "horoscope", zodiacSign = "aries")

    @Before fun setUp(): Unit = runBlocking {
        val directory = temporaryFolder.newFolder("files")
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        repository = FeedRepository(context, CoroutineScope(Dispatchers.IO + job))
        vault = MemoryVault(context).also { it.value = "synthetic-search-key" }
        repository.update { it.copy(modules = listOf(news, technology, disabled, weather, horoscope)) }
        Unit
    }

    @After fun tearDown() = runBlocking { job.cancelAndJoin() }

    @Test fun oneSearchUsesOneRequestForAllEligibleModulesAndLeavesPersistenceToTheCaller() = runBlocking {
        provider.action = { state, modules, query, secret, language ->
            assertEquals(listOf(news, technology), modules)
            assertEquals("Useful tools", query)
            assertEquals("synthetic-search-key", secret)
            assertEquals("sw", language)
            assertEquals(1, state.requestsToday)
            listOf(article(technology))
        }
        val items = engine().search("  Useful tools  ")
        assertEquals(technology.id, items.single().moduleId)
        assertEquals(1, provider.calls.get())
        assertEquals(1, repository.current().requestsToday)
        assertTrue(repository.current().items.isEmpty())
    }

    @Test fun aModuleSearchPassesOnlyThatEnabledModule() = runBlocking {
        provider.action = { _, modules, _, _, _ ->
            assertEquals(listOf(technology), modules)
            listOf(article(technology))
        }
        assertEquals(technology.id, engine().search("Find resources", technology.id).single().moduleId)
        assertEquals(1, repository.current().requestsToday)
    }

    @Test fun invalidQueriesAndNonfactualOrDisabledScopesConsumeNoRequests() = runBlocking {
        val engine = engine()
        listOf(null to " ", null to "x".repeat(2001), "missing" to "Search", disabled.id to "Search", weather.id to "Search", horoscope.id to "Search", "" to "Search").forEach { (moduleId, query) ->
            assertEquals("invalid_input", (runCatching { engine.search(query, moduleId) }.exceptionOrNull() as FeedFailure).code)
        }
        assertEquals(0, provider.calls.get())
        assertEquals(0, repository.current().requestsToday)
    }

    @Test fun concurrentSearchesRespectTheSharedDailyRequestLimit() = runBlocking {
        repository.update { it.copy(dailyRequestLimit = 2) }
        val engine = engine()
        val results = (1..8).map { async { runCatching { engine.search("Find an update") } } }.awaitAll()
        assertEquals(2, results.count { it.isSuccess })
        assertEquals(2, provider.calls.get())
        assertEquals(2, repository.current().requestsToday)
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as FeedFailure).code == "limit_reached" })
    }

    @Test fun changesToCredentialsContextOrSelectedModuleSettingsRejectInFlightSearches() = runBlocking {
        repeat(5) { change ->
            vault.value = "synthetic-search-key"
            repository.update { it.copy(modules = listOf(news, technology), context = emptyList(), dailyRequestLimit = 20) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            provider.action = { _, _, _, _, _ -> entered.complete(Unit); release.await(); listOf(article(news)) }
            val engine = engine()
            val search = async { runCatching { engine.search("Search") } }
            withTimeout(5000) { entered.await() }
            when (change) {
                0 -> repository.update { it.copy(context = listOf(ContextEntry(id = "changed", text = "A corrected interest", scopes = setOf("news")))) }
                1 -> repository.update { it.copy(modules = it.modules.map { module -> if (module.id == news.id) module.copy(query = "A revised selection goal") else module }) }
                2 -> repository.update { it.copy(modules = it.modules.map { module -> module.copy(enabled = false) }) }
                3 -> engine.disconnect()
                else -> engine.connect(AiConnection(model = "replacement-model"), "replacement-synthetic-key")
            }
            release.complete(Unit)
            assertEquals("invalid_input", (search.await().exceptionOrNull() as FeedFailure).code)
            assertTrue(repository.current().items.isEmpty())
        }
    }

    @Test fun reorderingUnchangedModulesDoesNotInvalidateAnOtherwiseCurrentSearch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.action = { _, _, _, _, _ -> entered.complete(Unit); release.await(); listOf(article(news)) }
        val search = async { engine().search("Search") }
        withTimeout(5000) { entered.await() }
        repository.update { it.copy(modules = it.modules.reversed()) }
        release.complete(Unit)
        assertEquals(news.id, search.await().single().moduleId)
    }

    @Test fun aSearchFailurePreservesTheAccountSavedItemsAndDiagnostic() = runBlocking {
        val cached = article(news)
        repository.update { it.copy(items = listOf(cached), savedIds = setOf(cached.id)) }
        val before = repository.current().connection
        val diagnostic = ChatGptDiagnostic("inference", httpStatus = 200, responseShape = "html_body", responseMediaType = "text/html")
        provider.action = { _, _, _, _, _ -> throw FeedFailure("invalid_response", diagnostic) }
        val failure = runCatching { engine().search("Search") }.exceptionOrNull() as FeedFailure
        assertEquals("invalid_response", failure.code)
        assertEquals(diagnostic, failure.diagnostic)
        assertEquals(before, repository.current().connection)
        assertEquals("synthetic-search-key", vault.value)
        assertEquals(listOf(cached), repository.current().items)
        assertEquals(setOf(cached.id), repository.current().savedIds)
    }

    @Test fun cancellationIsNotConvertedToAnEmptyOrSuccessfulSearch() = runBlocking {
        provider.action = { _, _, _, _, _ -> throw CancellationException("Synthetic cancellation") }
        assertTrue(runCatching { engine().search("Search") }.exceptionOrNull() is CancellationException)
        assertEquals("synthetic-search-key", vault.value)
        assertEquals(1, provider.calls.get())
    }

    @Test fun outOfScopeOrUncitedProviderResultsCannotEnterTheApp() = runBlocking {
        listOf(article(technology), article(news).copy(citations = emptyList())).forEach { item ->
            provider.action = { _, _, _, _, _ -> listOf(item) }
            assertEquals("invalid_response", (runCatching { engine().search("Search", news.id) }.exceptionOrNull() as FeedFailure).code)
        }
        assertTrue(repository.current().items.isEmpty())
    }

    private fun engine() = FeedEngine(repository, vault, provider = provider, language = { "sw" })

    private fun article(module: FeedModule): FeedItem {
        val url = "https://publisher.example/${module.id}"
        return FeedItem(FeedLogic.itemId(url), module.id, "Synthetic source-backed result", "Test-only content.",
            sourceUrl = url, kind = module.kind, citations = listOf(FeedCitation("Original", url)))
    }

    private class MemoryVault(context: Context) : SecretVault(context) {
        var value: String? = null
        override fun read() = value
        override fun write(secret: String) { value = secret }
        override fun clear() { value = null }
    }

    private class SearchProvider : AiProvider {
        val calls = AtomicInteger()
        var action: suspend (FeedState, List<FeedModule>, String, String, String) -> List<FeedItem> = { _, _, _, _, _ -> emptyList() }
        override suspend fun check(connection: AiConnection, secret: String) = Unit
        override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
        override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) = AiAnswer("Synthetic response")
        override suspend fun search(state: FeedState, modules: List<FeedModule>, query: String, secret: String, language: String): List<FeedItem> {
            calls.incrementAndGet()
            return action(state, modules, query, secret, language)
        }
    }
}
