package app.mynguli.feed

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.mynguli.MyNguliApplication
import app.mynguli.core.*
import app.mynguli.ui.UiFixtureChatGptAuth
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel, DataStore and engine; only the external provider is synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = V06FlowTestApplication::class)
class MeBotV06FlowTest {
    private lateinit var app: V06FlowTestApplication
    private lateinit var models: ViewModelStore
    private lateinit var vm: FeedViewModel
    private val news = FeedModule(id = "news", kind = "news")
    private val technology = FeedModule(id = "technology", kind = "technology")
    private val article = FeedItem("fixture-article", "news", "A sourced fixture article", "The supplied article excerpt.",
        sourceUrl = "https://publisher.example/original", sourceName = "Publisher",
        citations = listOf(FeedCitation("Original article", "https://publisher.example/original")),
        fetchedAtMillis = 100, publishedAtMillis = 90)

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedSecrets.write("synthetic-v06-key")
        app.feedRepository.update { FeedState(onboardingComplete = true, personalSeedApplied = true,
            modules = listOf(news, technology), items = listOf(article), savedIds = setOf(article.id),
            connection = AiConnection(model = "fixture-model"), timezone = "UTC", dailyRequestLimit = 20,
            lastRefreshMillis = 100, lastAiRefreshMillis = 90) }
        app.repository.update { AppState(settings = Settings(language = "en")) }
        openViewModel()
    }

    @After fun tearDown() {
        if (::models.isInitialized) models.clear()
        Dispatchers.resetMain()
    }

    @Test fun openingAnItemStoresOnlyTheCompletedExplanationAndReusesItsDurableCache() = runBlocking {
        vm.loadDetail(article.id)
        val completed = awaitUi { !it.detailLoading && it.detailTurns.singleOrNull()?.text == V06FlowTestApplication.ANSWER }
        assertEquals(listOf(V06FlowTestApplication.CITATION), completed.detailCitations)
        assertEquals("", completed.detailText)
        assertEquals(V06FlowTestApplication.ANSWER, app.feedRepository.current().briefs.single().text)
        assertEquals(1, app.streamCalls.size)
        val requestCount = app.feedRepository.current().requestsToday

        models.clear()
        openViewModel()
        vm.loadDetail(article.id)
        awaitUi { !it.detailLoading && it.detailTurns.singleOrNull()?.text == V06FlowTestApplication.ANSWER }
        assertEquals(1, app.streamCalls.size)
        assertEquals(requestCount, app.feedRepository.current().requestsToday)
    }

    @Test fun partialStreamingTextIsVisibleButNeverCachedAfterFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.streamAction = { _, _, _, _, onText ->
            onText("A provisional paragraph"); entered.complete(Unit); release.await()
            throw FeedFailure("ai_interrupted")
        }
        vm.loadDetail(article.id)
        withTimeout(10_000) { entered.await() }
        assertTrue(vm.ui.value.detailLoading)
        assertEquals("A provisional paragraph", vm.ui.value.detailText)
        assertTrue(app.feedRepository.current().briefs.isEmpty())
        release.complete(Unit)
        val failed = awaitUi { !it.detailLoading && it.detailError != null }
        assertEquals("ai_interrupted", failed.detailError)
        assertEquals("", failed.detailText)
        assertTrue(failed.detailTurns.isEmpty())
        assertTrue(app.feedRepository.current().briefs.isEmpty())
        assertEquals(listOf(article), app.feedRepository.current().items)
        assertEquals("synthetic-v06-key", app.feedSecrets.read())
    }

    @Test fun leavingTheDetailCancelsAndDiscardsEvenALateTransportCallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        app.streamAction = { _, _, _, _, onText ->
            onText("Provisional"); entered.complete(Unit)
            withContext(NonCancellable) {
                release.await(); onText("Late callback after leaving"); finished.complete(Unit)
                AiAnswer(V06FlowTestApplication.ANSWER)
            }
        }
        vm.loadDetail(article.id)
        withTimeout(10_000) { entered.await() }
        vm.cancelDetail()
        release.complete(Unit)
        withTimeout(10_000) { finished.await() }
        assertFalse(vm.ui.value.detailLoading)
        assertEquals("", vm.ui.value.detailText)
        assertTrue(app.feedRepository.current().briefs.isEmpty())
    }

    @Test fun detailFollowUpsPersistTheExplanationAndBothSidesOfTheConversation() = runBlocking {
        vm.loadDetail(article.id)
        awaitUi { !it.detailLoading && it.detailTurns.isNotEmpty() }
        vm.followUpDetail(article.id, "What should I check first?")
        awaitUi { !it.detailLoading && it.detailTurns.size == 3 }
        vm.followUpDetail(article.id, "And what is still uncertain?")
        val result = awaitUi { !it.detailLoading && it.detailTurns.size == 5 }
        assertEquals(listOf("assistant", "user", "assistant", "user", "assistant"), result.detailTurns.map { it.role })
        assertEquals(listOf("What should I check first?", "And what is still uncertain?"), result.detailTurns.filter { it.role == "user" }.map { it.text })
        val stored = app.feedRepository.current().conversations.single()
        assertEquals(article.id, stored.itemId)
        assertEquals(result.detailTurns, stored.turns)
        assertEquals(4, app.streamCalls.last().conversation.turns.size)
        assertEquals(V06FlowTestApplication.ANSWER, app.streamCalls.last().conversation.turns[2].text)
        assertEquals("", result.detailText)
    }

    @Test fun changedContextOrConnectionCannotCommitAnOldAutomaticExplanation() = runBlocking {
        repeat(2) { change ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            app.streamAction = { _, _, _, _, onText ->
                onText("Stale provisional text"); entered.complete(Unit); release.await()
                AiAnswer(V06FlowTestApplication.ANSWER)
            }
            vm.loadDetail(article.id, force = true)
            withTimeout(10_000) { entered.await() }
            app.feedRepository.update { state -> if (change == 0) state.copy(context = listOf(
                ContextEntry(id = "corrected", text = "A corrected interest", scopes = setOf("news"))))
                else state.copy(connection = state.connection.copy(model = "another-selected-model")) }
            release.complete(Unit)
            val failure = awaitUi { !it.detailLoading && it.detailError != null }
            assertEquals("invalid_input", failure.detailError)
            assertEquals("", failure.detailText)
            assertTrue(app.feedRepository.current().briefs.isEmpty())
        }
    }

    @Test fun aFailedFollowUpKeepsTheQuestionButDoesNotSaveAnAssistantFragment() = runBlocking {
        vm.loadDetail(article.id)
        awaitUi { !it.detailLoading && it.detailTurns.isNotEmpty() }
        app.streamAction = { _, _, _, _, onText -> onText("Unfinished follow-up"); throw FeedFailure("ai_interrupted") }
        vm.followUpDetail(article.id, "Explain the limitation")
        val failed = awaitUi { !it.detailLoading && it.detailError != null }
        assertEquals("ai_interrupted", failed.detailError)
        assertEquals(listOf("assistant", "user"), app.feedRepository.current().conversations.single().turns.map { it.role })
        assertEquals("", failed.detailText)
        assertEquals(V06FlowTestApplication.ANSWER, app.feedRepository.current().briefs.single().text)
    }

    @Test fun searchTracksTheQueryScopeAndResultsWithoutChangingRefreshTimes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = article.copy(id = "search-result", moduleId = technology.id, kind = technology.kind,
            title = "A useful discovered resource", sourceUrl = "https://publisher.example/resource",
            citations = listOf(FeedCitation("Resource", "https://publisher.example/resource")))
        app.searchAction = { _, modules, query ->
            assertEquals(listOf(technology), modules); assertEquals("useful tools", query)
            entered.complete(Unit); release.await(); listOf(result)
        }
        vm.search("  useful tools  ", technology.id)
        withTimeout(10_000) { entered.await() }
        assertEquals("useful tools", vm.ui.value.searchQuery)
        assertEquals(technology.id, vm.ui.value.searchModuleId)
        assertTrue(vm.ui.value.searching)
        assertFalse(vm.ui.value.searchCompleted)
        assertTrue(vm.ui.value.searchResults.isEmpty())
        release.complete(Unit)
        val completed = awaitUi { it.searchCompleted && !it.searching }
        assertEquals(listOf(result), completed.searchResults)
        assertTrue(app.feedRepository.current().items.contains(result))
        assertEquals(100L, app.feedRepository.current().lastRefreshMillis)
        assertEquals(90L, app.feedRepository.current().lastAiRefreshMillis)

        app.searchAction = { _, _, _ -> throw FeedFailure("offline") }
        vm.search("another query")
        val failed = awaitUi { !it.searching && it.searchError != null }
        assertEquals("another query", failed.searchQuery)
        assertTrue(failed.searchResults.isEmpty())
        assertFalse(failed.searchCompleted)
        assertEquals("offline", failed.searchError)
        assertTrue(app.feedRepository.current().items.contains(result))
    }

    @Test fun contextImportPreviewMakesNoChangesUntilExplicitConfirmation() = runBlocking {
        val before = app.feedRepository.current()
        val context = PersonalContextImport(name = "Synthetic imported reader", region = "Example region", timezone = "UTC",
            entries = listOf(ContextEntry(id = "new-context", text = "A new approved topic", scopes = setOf("technology"))))
        val file = File(app.cacheDir, "v06-context-fixture.json").apply { writeText(FeedJson.encodeToString(context)) }
        vm.prepareContextImport(Uri.fromFile(file))
        val preview = awaitUi { !it.importWorking && it.importPreview != null }
        assertEquals("context", preview.importPreview?.kind)
        assertEquals(1, preview.importPreview?.entries)
        assertEquals(before, app.feedRepository.current())
        assertEquals("synthetic-v06-key", app.feedSecrets.read())
        assertTrue(app.streamCalls.isEmpty())
        vm.confirmImport()
        awaitUi { !it.importWorking && it.importSummary == "context" }
        val after = app.feedRepository.current()
        assertEquals(context.name, after.name)
        assertEquals(context.entries.map { it.copy(origin = "imported") }, after.context)
        assertEquals(before.connection, after.connection)
        assertEquals(before.savedIds, after.savedIds)
        assertEquals("synthetic-v06-key", app.feedSecrets.read())
    }

    @Test fun rejectedContextSaveReleasesBusyStateAndAllowsACorrectedSave() = runBlocking {
        val before = app.feedRepository.current()
        vm.saveContext("Reader".repeat(17), "Example region", "UTC", before.context)?.join()
        val rejected = awaitUi { !it.contextSaving && it.error != null }
        assertEquals("invalid_input", rejected.error)
        assertEquals(before, app.feedRepository.current())

        val context = listOf(ContextEntry(id = "corrected-context", text = "A confirmed interest", scopes = setOf("news")))
        vm.saveContext("Reader", "Example region", "UTC", context)?.join()
        val saved = awaitUi { !it.contextSaving && it.state?.context == context }
        assertNull(saved.error)
        assertEquals("Reader", app.feedRepository.current().name)
        assertEquals(context, app.feedRepository.current().context)

        models.clear()
        openViewModel()
        assertFalse(vm.ui.value.contextSaving)
        assertEquals(context, vm.ui.value.state?.context)
    }

    @Test fun moduleSaveFailureIsRecoverableAndRemovalPublishesOnlyDurableState() = runBlocking {
        val before = app.feedRepository.current()
        vm.saveModule(news.copy(preferences = mapOf("unsupported" to "value")))?.join()
        val rejected = awaitUi { it.moduleSavingId == null && it.error != null }
        assertEquals("invalid_input", rejected.error)
        assertEquals(before, app.feedRepository.current())

        val updated = news.copy(query = "A confirmed topic", preferences = mapOf("language" to "en"))
        vm.saveModule(updated)?.join()
        val saved = awaitUi { it.moduleSavingId == null && it.state?.modules?.find { m -> m.id == news.id } == updated }
        assertNull(saved.error)
        assertEquals(updated, app.feedRepository.current().modules.single { it.id == news.id })

        vm.removeModule(news.id)?.join()
        val removed = awaitUi { it.moduleSavingId == null && it.state?.modules?.none { m -> m.id == news.id } == true }
        assertEquals(app.feedRepository.current(), removed.state)
        assertTrue(app.feedRepository.current().items.contains(article))
        assertTrue(article.id in app.feedRepository.current().savedIds)
    }

    @Test fun backupPreviewAndCancelPreserveTheWorkingConnectionAndData() = runBlocking {
        val before = app.feedRepository.current()
        val candidate = before.copy(name = "A backup reader", items = emptyList(), savedIds = emptySet())
        val file = File(app.cacheDir, "v06-backup-fixture.json").apply { writeText(FeedJson.encodeToString(candidate)) }
        vm.prepareFeedBackup(Uri.fromFile(file))
        awaitUi { !it.importWorking && it.importPreview?.kind == "backup" }
        assertEquals(before, app.feedRepository.current())
        assertEquals("synthetic-v06-key", app.feedSecrets.read())
        vm.cancelImport()
        assertNull(vm.ui.value.importPreview)
        vm.confirmImport()
        assertEquals(before, app.feedRepository.current())
        assertEquals("synthetic-v06-key", app.feedSecrets.read())
    }

    private suspend fun openViewModel() {
        models = ViewModelStore()
        vm = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        awaitUi { !it.loading && it.connected }
    }

    private suspend fun awaitUi(condition: (FeedUiState) -> Boolean): FeedUiState = withTimeout(10_000) { vm.ui.first(condition) }
}

/** No network, production account or Android Keystore operations. */
class V06FlowTestApplication : MyNguliApplication() {
    override val feedSecrets by lazy { TestVault(this) }
    override val chatGptAuth by lazy { UiFixtureChatGptAuth(this) }
    data class StreamCall(val conversation: AiConversation, val question: String)
    val streamCalls = CopyOnWriteArrayList<StreamCall>()
    var streamAction: suspend (FeedState, FeedItem?, AiConversation, String, (String) -> Unit) -> AiAnswer = { _, _, _, _, onText ->
        onText("The explanation is arriving.")
        AiAnswer(ANSWER, listOf(CITATION))
    }
    var searchAction: suspend (FeedState, List<FeedModule>, String) -> List<FeedItem> = { _, _, _ -> emptyList() }
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, provider = object : AiProvider {
            override suspend fun check(connection: AiConnection, secret: String) = Unit
            override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) = AiAnswer(ANSWER, listOf(CITATION))
            override suspend fun streamConversation(state: FeedState, item: FeedItem?, conversation: AiConversation, question: String, secret: String, language: String, onText: (String) -> Unit): AiAnswer {
                streamCalls += StreamCall(conversation, question)
                return streamAction(state, item, conversation, question, onText)
            }
            override suspend fun search(state: FeedState, modules: List<FeedModule>, query: String, secret: String, language: String) = searchAction(state, modules, query)
        }, language = { "en" }, chatGpt = chatGptAuth)
    }
    companion object {
        const val ANSWER = "The completed explanation gives background, the practical change and the remaining uncertainty."
        val CITATION = FeedCitation("Original article", "https://publisher.example/original")
    }
}
