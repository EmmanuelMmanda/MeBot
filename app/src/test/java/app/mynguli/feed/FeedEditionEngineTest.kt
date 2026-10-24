package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class FeedEditionEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jobs = SupervisorJob()
    private lateinit var repository: FeedRepository
    private lateinit var vault: TestVault
    private var rssReads = 0
    private val aiModules = mutableListOf<String>()
    private val news = FeedModule(id = "news", kind = "news", sources = listOf(FeedSource("Fixture", "https://example.com/feed")))
    private val reader = object : SourceReader {
        override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> { rssReads++; return listOf(story("rss", module)) }
    }
    private val provider = object : AiProvider {
        override suspend fun check(connection: AiConnection, secret: String) = Unit
        override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String): List<FeedItem> {
            aiModules += module.id
            return listOf(story("ai-" + module.id, module).copy(aiGenerated = true))
        }
        override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) = AiAnswer("Test response")
    }

    @Before fun setup(): Unit = runBlocking {
        val files = temporary.newFolder("feed")
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        repository = FeedRepository(context, CoroutineScope(Dispatchers.IO + jobs))
        vault = TestVault(context)
        repository.update { it.copy(modules = listOf(news)) }
    }
    @After fun close() = runBlocking { jobs.cancelAndJoin() }

    @Test fun connectedDiscoveryDoesNotWaitForOrPreferRss() = runBlocking {
        vault.write("fixture-key")
        val engine = FeedEngine(repository, vault, reader, provider)
        val result = engine.refresh()
        assertNull(result.error)
        assertEquals(0, rssReads)
        assertEquals(listOf("news"), aiModules)
        assertEquals(listOf("ai-news"), repository.current().items.map { it.id })
    }

    @Test fun weatherUsesTypedPublicDataAndNeverConsumesAnAiRequest() = runBlocking {
        vault.write("fixture-key")
        val weather = FeedModule(id = "weather", kind = "weather")
        repository.update { it.copy(modules = listOf(news, weather), region = "Example city") }
        val public = object : PublicDiscovery {
            override suspend fun discover(state: FeedState, module: FeedModule, language: String, now: Long): List<FeedItem> =
                if (module.kind == "weather") listOf(story("forecast", weather).copy(summary = "", contentType = "weather",
                    weather = FeedWeather(24.2, 25.0, 40, 3, "Example city"))) else emptyList()
        }
        FeedEngine(repository, vault, reader, provider, discovery = public).refresh()
        assertEquals(listOf("news"), aiModules)
        assertEquals(1, repository.current().requestsToday)
        assertNotNull(repository.current().items.find { it.id == "forecast" }?.weather)
    }

    @Test fun fastPublicResultIsReadableBeforeSlowFallbackFinishes(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                entered.complete(Unit); release.await(); return emptyList()
            }
        }
        val public = object : PublicDiscovery {
            override suspend fun discover(state: FeedState, module: FeedModule, language: String, now: Long) = listOf(story("fast", module))
        }
        val refresh = async { FeedEngine(repository, vault, slow, provider, discovery = public).refresh() }
        withTimeout(5000) {
            entered.await()
            repository.state.first { it.items.any { item -> item.id == "fast" } }
        }
        assertFalse(refresh.isCompleted)
        release.complete(Unit)
        refresh.await()
    }

    @Test fun mediaFailureKeepsTheVerifiedArticleAndSuccessfulRefresh() = runBlocking {
        vault.write("fixture-key")
        val media = object : FeedEnricher {
            override suspend fun enrich(items: List<FeedItem>): List<FeedItem> { throw FeedFailure("offline") }
        }
        val result = FeedEngine(repository, vault, reader, provider, enricher = media).refresh()
        assertNull(result.error)
        assertEquals("ai-news", repository.current().items.single().id)
        assertNull(repository.current().items.single().media)
    }

    @Test fun contextChangeDuringMediaLoadCannotRestoreAStaleExplanation() = runBlocking {
        vault.write("fixture-key")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val media = object : FeedEnricher {
            override suspend fun enrich(items: List<FeedItem>): List<FeedItem> {
                entered.complete(Unit); release.await()
                return items.map { it.copy(media = FeedMedia("https://example.com/photo.jpg", it.sourceUrl)) }
            }
        }
        val refresh = async { FeedEngine(repository, vault, reader, provider, enricher = media).refresh() }
        withTimeout(5000) { entered.await() }
        repository.update { it.copy(context = listOf(ContextEntry(text = "A changed interest")),
            items = it.items.map { item -> item.copy(why = "", relevance = 0) }) }
        release.complete(Unit)
        refresh.await()
        assertNull(repository.current().items.single().media)
        assertEquals(0, repository.current().items.single().relevance)
    }

    @Test fun requestCapServesTheLeastRecentlyRefreshedModuleFirst() = runBlocking {
        vault.write("fixture-key")
        val security = FeedModule(id = "security", kind = "security")
        repository.update { it.copy(modules = listOf(news, security), dailyRequestLimit = 1,
            items = listOf(story("old-news", news).copy(aiGenerated = true, fetchedAtMillis = System.currentTimeMillis()))) }
        val outcome = FeedEngine(repository, vault, reader, provider).refresh()
        assertEquals("limit_reached", outcome.error)
        assertEquals(listOf("security"), aiModules)
        assertEquals(1, repository.current().requestsToday)
        assertTrue(repository.current().items.any { it.id == "ai-security" })
    }

    @Test fun wrongModuleFromAnAdapterCannotEnterTheFeed() = runBlocking {
        val wrong = object : PublicDiscovery {
            override suspend fun discover(state: FeedState, module: FeedModule, language: String, now: Long) =
                listOf(story("wrong", module).copy(moduleId = "someone-else"))
        }
        repository.update { it.copy(modules = listOf(news.copy(sources = emptyList()))) }
        val result = FeedEngine(repository, vault, reader, provider, discovery = wrong).refresh()
        assertNotNull(result.error)
        assertTrue(repository.current().items.isEmpty())
    }

    private fun story(id: String, module: FeedModule) = FeedItem(id, module.id, "Fixture " + id, "Synthetic transport test",
        sourceName = "Example", sourceUrl = "https://example.com/" + id, kind = module.kind, relevance = 70)
}
