package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mebot.nguli.com.core.*
import mebot.nguli.com.security.AppLockController
import mebot.nguli.com.security.LockRecord
import mebot.nguli.com.security.LockStorage
import mebot.nguli.com.ui.ChatGptJourneyApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The local Today recommendation projection is persisted without consulting any provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalDailySelection098TestApplication::class)
class PersonalDailySelection098ViewModelTest {
    private lateinit var app: PersonalDailySelection098TestApplication
    private lateinit var stores: ViewModelStore
    private lateinit var vm: FeedViewModel
    private lateinit var seed: FeedState
    private lateinit var published: FeedItem
    private lateinit var read: FeedItem

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedSecrets.clear()
        app.feedRepository.erase()

        val now = System.currentTimeMillis()
        val module = FeedModule(id = "technology-daily", kind = "technology", title = "Technology",
            sources = listOf(FeedSource("Example publisher", "https://example.com/technology")))
        published = FeedItem(
            id = "public-technology-1", moduleId = module.id, title = "A public technology update",
            summary = "A bounded synthetic source record for local daily-selection tests.",
            sourceName = "Example publisher", sourceUrl = "https://example.com/technology/update-1",
            publishedAtMillis = now - 60_000, fetchedAtMillis = now, kind = "technology", relevance = 80,
            acquisition = FeedAcquisition.PUBLISHER_RSS, authorship = FeedAuthorship.PUBLISHER,
        )
        read = FeedItem(
            id = "original-read-1", moduleId = module.id, title = "A synthetic original read",
            summary = "A substantial synthetic long-form read used only to exercise the local selection projection.",
            fetchedAtMillis = now, kind = "technology", relevance = 70, aiGenerated = true,
            contentType = "original_read", acquisition = FeedAcquisition.ORIGINAL_READ,
            authorship = FeedAuthorship.AI,
            sections = listOf(
                FeedContentSection("Background", "A bounded synthetic section about a public technology topic. ".repeat(10)),
                FeedContentSection("Considerations", "A second bounded synthetic section describing trade-offs and open questions. ".repeat(10)),
            ),
        )
        seed = FeedState(
            onboardingComplete = true, personalSeedApplied = true, timezone = "UTC",
            modules = listOf(module), items = listOf(published, read),
            connection = AiConnection(mode = "direct", model = "synthetic-selection-model"),
            lastRefreshMillis = now, lastAiRefreshMillis = now, dailyRequestLimit = 10,
            usageDate = FeedLogic.dateAt(now, "UTC"), requestsToday = 2,
        )
        app.feedSecrets.write("synthetic-daily-selection-key")
        app.feedRepository.update { seed }
        app.resetCounters()
        stores = ViewModelStore()
        vm = newViewModel()
        withTimeout(10_000) { vm.ui.first { it.state != null && it.connected && !it.loading } }
        Unit
    }

    @After fun tearDown() {
        if (::stores.isInitialized) stores.clear()
        Dispatchers.resetMain()
    }

    @Test fun ensurePersistsStableIdsWithoutProviderQuotaOrSourceDateChangesAndSurvivesViewModelRecreation() = runBlocking {
        val before = app.feedRepository.current()
        assertNull(before.personalDailySelection098)
        val beforePublishedAt = before.items.single { it.id == published.id }.publishedAtMillis
        val beforeFetchedAt = before.items.single { it.id == published.id }.fetchedAtMillis
        val beforeReadFetchedAt = before.items.single { it.id == read.id }.fetchedAtMillis

        vm.ensurePersonalDailySelection098()
        val selected = withTimeout(10_000) {
            app.feedRepository.awaitFeedState { it.personalDailySelection098 != null }
        }
        val record = selected.personalDailySelection098!!
        assertTrue(record.itemIds.isNotEmpty())
        assertTrue(record.itemIds.size <= PersonalDailySelectionLogic098.MAX_SELECTION_SIZE)
        assertEquals(setOf(published.id, read.id), record.itemIds.toSet())
        assertTrue(record.itemIds.contains(published.id))
        assertTrue("At most one Original Read is selected when sourced candidates exist", record.itemIds.count { it == read.id } <= 1)
        assertEquals(record.itemIds, PersonalDailySelectionLogic098.resolve(selected, System.currentTimeMillis()).map { it.id })
        assertTrue(PersonalDailySelectionLogic098.isValidRecord(record))
        assertEquals(beforePublishedAt, selected.items.single { it.id == published.id }.publishedAtMillis)
        assertEquals(beforeFetchedAt, selected.items.single { it.id == published.id }.fetchedAtMillis)
        assertEquals(beforeReadFetchedAt, selected.items.single { it.id == read.id }.fetchedAtMillis)
        assertEquals(before.lastRefreshMillis, selected.lastRefreshMillis)
        assertEquals(before.lastAiRefreshMillis, selected.lastAiRefreshMillis)
        assertEquals(before.usageDate, selected.usageDate)
        assertEquals(before.requestsToday, selected.requestsToday)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())

        // A new ViewModel reads the same durable device-local selection; ensuring again is idempotent.
        stores.clear()
        stores = ViewModelStore()
        vm = newViewModel()
        val recreated = withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading }.state!! }
        assertEquals(record, recreated.personalDailySelection098)
        vm.ensurePersonalDailySelection098()
        val afterEnsure = withTimeout(10_000) {
            app.feedRepository.awaitFeedState { it.personalDailySelection098?.inputDigest == record.inputDigest }
        }
        assertEquals(record, afterEnsure.personalDailySelection098)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }

    @Test fun sharedFeedStateAwaitCollectsOffTheRunBlockingMainLoop(): Unit = runBlocking {
        val before = app.feedRepository.current()
        val testThread = Thread.currentThread()
        val collectorThread = AtomicReference<Thread?>()
        val baselineObserved = CompletableDeferred<Unit>()
        val waiter = async {
            app.feedRepository.awaitFeedState { state ->
                collectorThread.compareAndSet(null, Thread.currentThread())
                if (state.editionSequence == before.editionSequence) {
                    baselineObserved.complete(Unit)
                    false
                } else state.editionSequence == before.editionSequence + 1
            }
        }
        try {
            val observed = withTimeout(10_000) {
                baselineObserved.await()
                app.feedRepository.update { it.copy(editionSequence = before.editionSequence + 1) }
                waiter.await()
            }

            assertEquals(before.editionSequence + 1, observed.editionSequence)
            assertNotNull("shared state observer must run independently of the test/Main event loop", collectorThread.get())
            assertNotSame(testThread, collectorThread.get())
        } finally {
            waiter.cancelAndJoin()
        }
    }

    @Test fun invalidationArrivingBeforeEnsureJobCompletesIsCoalescedAndRecomputedFromLatestFeed() = runBlocking {
        val now = System.currentTimeMillis()
        val firstCommit = CompletableDeferred<Unit>()
        val releaseFirstJob = CompletableDeferred<Unit>()
        val pauseCount = AtomicInteger()
        vm.afterPersonalDailySelectionCommit098 = {
            if (pauseCount.getAndIncrement() == 0) {
                firstCommit.complete(Unit)
                releaseFirstJob.await()
            }
        }

        vm.ensurePersonalDailySelection098()
        withTimeout(10_000) { firstCommit.await() }
        val english = app.feedRepository.current()
        val englishRecord = requireNotNull(english.personalDailySelection098)
        assertTrue(PersonalDailySelectionLogic098.resolve(english, now).isNotEmpty())

        // This models the real locale transition: FeedEdition's state.items-keyed effect fires
        // while the first durable ensure job is still active, after its old-revision record commits.
        val localized = app.feedRepository.update { current ->
            current.copy(items = current.items.map { item ->
                item.copy(title = "${item.title} · Kiswahili", summary = "Maelezo yaliyotafsiriwa: ${item.summary}")
            })
        }
        assertTrue(PersonalDailySelectionLogic098.resolve(localized, now).isEmpty())
        vm.ensurePersonalDailySelection098()

        releaseFirstJob.complete(Unit)
        val current = withTimeout(10_000) {
            app.feedRepository.awaitFeedState { state ->
                state.personalDailySelection098?.inputDigest != englishRecord.inputDigest &&
                    PersonalDailySelectionLogic098.resolve(state, now).isNotEmpty()
            }
        }
        val selected = requireNotNull(current.personalDailySelection098)
        val expected = PersonalDailySelectionLogic098.ensure(localized, now).personalDailySelection098
        assertEquals(expected?.itemIds, selected.itemIds)
        assertEquals(expected?.itemRevisions, selected.itemRevisions)
        assertTrue("The latest localized invalidation is not silently dropped", pauseCount.get() >= 2)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
        assertEquals(seed.requestsToday, current.requestsToday)
        assertEquals(seed.usageDate, current.usageDate)
        assertEquals(seed.lastRefreshMillis, current.lastRefreshMillis)
        assertEquals(seed.lastAiRefreshMillis, current.lastAiRefreshMillis)
        assertEquals(published.publishedAtMillis, current.items.single { it.id == published.id }.publishedAtMillis)
    }

    @Test fun resetInvalidatesDerivedDailySelectionWithoutRefreshingOrDeletingViaSelectionLogic() = runBlocking {
        val firstCommit = CompletableDeferred<Unit>()
        val holdEnsure = CompletableDeferred<Unit>()
        vm.afterPersonalDailySelectionCommit098 = {
            firstCommit.complete(Unit)
            holdEnsure.await()
        }
        vm.ensurePersonalDailySelection098()
        withTimeout(10_000) { firstCommit.await() }
        val selected = app.feedRepository.current()
        assertNotNull(selected.personalDailySelection098)
        assertEquals(setOf(published.id, read.id), selected.items.map { it.id }.toSet())

        // Queue a second invalidation while the first ensure is held, then prove reset cancels
        // the drain rather than allowing a post-clear projection to repopulate erased state.
        vm.ensurePersonalDailySelection098()
        vm.reset().join()
        holdEnsure.complete(Unit)
        val reset = app.feedRepository.current()
        assertNull(reset.personalDailySelection098)
        assertTrue(reset.items.isEmpty())
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }

    private fun newViewModel(): FeedViewModel =
        ViewModelProvider(stores, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
}

/** Synthetic app counters catch accidental provider/source work from the local projection. */
class PersonalDailySelection098TestApplication : ChatGptJourneyApplication() {
    val sourceReaderCalls = AtomicInteger()
    val aiProviderCalls = AtomicInteger()

    override val appLock by lazy {
        AppLockController(PersonalDailySelectionMemoryLockStorage(), elapsed = { 100_000L }, boot = { 7 }, deviceLocked = { false })
    }

    fun resetCounters() {
        sourceReaderCalls.set(0)
        aiProviderCalls.set(0)
    }

    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets,
            sources = object : SourceReader {
                override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                    sourceReaderCalls.incrementAndGet()
                    return emptyList()
                }
            },
            provider = object : AiProvider {
                override suspend fun check(connection: AiConnection, secret: String) { aiProviderCalls.incrementAndGet() }
                override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String): List<FeedItem> {
                    aiProviderCalls.incrementAndGet()
                    return emptyList()
                }
                override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
                    aiProviderCalls.incrementAndGet()
                    return AiAnswer("Synthetic answer", item?.citations.orEmpty())
                }
                override suspend fun suggest(
                    state: FeedState, item: FeedItem?, conversation: AiConversation?, module: FeedModule?,
                    secret: String, language: String,
                ): List<String> {
                    aiProviderCalls.incrementAndGet()
                    return emptyList()
                }
            }, chatGpt = chatGptAuth, language = { "en" })
    }
}

private class PersonalDailySelectionMemoryLockStorage : LockStorage {
    private var record: LockRecord? = null
    override fun enabled() = record != null
    override fun read() = record
    override fun write(record: LockRecord) { this.record = record }
    override fun disable() { record = null }
}
