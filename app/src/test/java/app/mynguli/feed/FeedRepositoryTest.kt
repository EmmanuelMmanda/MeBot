package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class FeedRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var storageContext: Context
    private lateinit var storageDirectory: File
    private val jobs = mutableListOf<Job>()

    @Before fun prepareStorage() {
        storageDirectory = temporaryFolder.newFolder("feed-storage")
        val application = ApplicationProvider.getApplicationContext<Context>()
        storageContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = storageDirectory
        }
    }

    @After fun stopStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test fun firstUseHasNoInventedNewsOrPersonalIdentity() = runBlocking {
        val state = repository().first.current()
        assertFalse(state.onboardingComplete)
        assertTrue(state.items.isEmpty())
        assertTrue(state.context.isEmpty())
        assertEquals("", state.name)
        assertFalse(state.notifications)
    }

    @Test fun contextSavedItemsAndModuleChoicesSurviveReopening() = runBlocking {
        val (store, job) = repository()
        val expected = sampleState()
        store.update { expected }
        job.cancelAndJoin()
        val restored = repository().first.current()
        assertEquals(expected, restored)
        assertEquals("Evaluate private deployments", restored.context.single().text)
        assertEquals(setOf("article-a"), restored.savedIds)
        assertFalse(restored.modules.single { it.id == "opportunities" }.enabled)
    }

    @Test fun concurrentMutationsPreserveIndependentSavesAndUsage() = runBlocking {
        val store = repository().first
        coroutineScope {
            repeat(40) { index -> launch(Dispatchers.Default) {
                store.update { it.copy(savedIds = it.savedIds + "saved-$index", requestsToday = it.requestsToday + 1) }
            } }
        }
        assertEquals(40, store.current().requestsToday)
        assertEquals((0 until 40).map { "saved-$it" }.toSet(), store.current().savedIds)
    }

    @Test fun invalidConfigurationDoesNotReplaceTheStoredFeed() = runBlocking {
        val store = repository().first
        store.update { sampleState() }
        val before = store.current()
        val failure = runCatching { store.update { it.copy(connection = it.connection.copy(serviceUrl = "http://insecure.example.com")) } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, store.current())
    }

    @Test fun resetRemovesPersonalContextAndDoesNotReapplyASeed() = runBlocking {
        val (store, job) = repository()
        store.update { sampleState() }
        val erased = store.erase()
        assertEquals(FeedState(personalSeedApplied = true), erased)
        job.cancelAndJoin()
        val restored = repository().first
        restored.applyOptionalSeed(storageContext)
        assertEquals(erased, restored.current())
    }

    @Test fun corruptOrFutureStateFailsWithoutSilentlyDestroyingTheOriginal() = runBlocking {
        val file = storageDirectory.resolve("mebot/feed.json")
        file.parentFile!!.mkdirs()
        val original = "{\"schemaVersion\":999,\"name\":\"Unmigrated profile\"}"
        file.writeText(original)
        assertTrue(runCatching { repository().first.current() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(original, file.readText())
    }

    private fun repository(): Pair<FeedRepository, Job> {
        val job = SupervisorJob()
        jobs += job
        return FeedRepository(storageContext, CoroutineScope(Dispatchers.IO + job)) to job
    }

    private fun sampleState() = FeedState(
        onboardingComplete = true,
        name = "Test reader",
        region = "Test region",
        timezone = "Africa/Dar_es_Salaam",
        context = listOf(ContextEntry(id = "context-a", text = "Evaluate private deployments", updatedAtMillis = 1_790_000_000_000)),
        items = listOf(FeedItem(
            id = "article-a", moduleId = "news", title = "A deployment update", summary = "A synthetic test article.",
            sourceName = "Example", sourceUrl = "https://example.com/deployment", publishedAtMillis = 1_790_000_000_000,
            fetchedAtMillis = 1_790_000_010_000,
        )),
        savedIds = setOf("article-a"),
        hiddenIds = setOf("article-hidden"),
        lastRefreshMillis = 1_790_000_010_000,
        personalSeedApplied = true,
    )
}
