package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.core.*
import mebot.nguli.com.ui.ChatGptJourneyApplication
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic current-state → draft → explicit-send journey. The fixture provider never accesses the network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ChatGptJourneyApplication::class)
class SuggestionDraft095ViewModelTest {
    private lateinit var app: ChatGptJourneyApplication
    private lateinit var stores: ViewModelStore
    private lateinit var vm: FeedViewModel

    private val module = FeedModule(id = "news", kind = "news", title = "News", query = "public technology",
        sources = listOf(FeedSource("Example publisher", "https://example.com/feed.xml")))
    private val note = ContextEntry(id = "news-interest", text = "Confirmed interest in public technology updates", scopes = setOf("news"))
    private val interaction = AiSuggestedInteraction(label = "Check the source date", prompt = "Which publication date does this source show?")

    @Before fun setUp() {
        runBlocking {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            app = ApplicationProvider.getApplicationContext()
            app.chatGptAuth.clearAll()
            app.chatGptAuth.restoreSavedAccount()
            app.feedSecrets.write("synthetic-local-provider-key")
            app.feedRepository.update { state("story-one") }
            app.repository.update { AppState(settings = Settings(language = "en")) }
            app.asked.clear(); app.askedQuestions.clear(); app.askedContexts.clear(); app.checked.clear()
            openViewModel()
            withTimeout(10_000) { vm.ui.first { it.state != null && it.connected } }
        }
    }

    @After fun tearDown() {
        if (::stores.isInitialized) stores.clear()
        if (::app.isInitialized) app.alarms.clearAll()
        Dispatchers.resetMain()
    }

    @Test fun choosingAiQuestionCreatesFreshItemScopedDraftButDoesNotSendOrReserve() = runBlocking {
        val oldThread = AiConversation(id = "prior-thread", title = "Unsent work", turns = listOf(
            AiTurn(id = "prior-user-turn", role = "user", text = "Keep this unsent question."),
        ))
        val before = state("story-one", conversations = listOf(oldThread))
        app.feedRepository.update { before }
        val item = before.items.single()
        val stableId = AiSuggestedInteractions095.suggestionId(item, interaction)
        val requestsBefore = before.requestsToday

        vm.prepareSuggestion(item.id, stableId, "en").join()

        val draft = requireNotNull(vm.ui.value.suggestionDraft) { "selection should prepare an editable draft" }
        val stored = app.feedRepository.current()
        val newThread = stored.conversations.single { it.id == draft.conversationId }
        assertEquals(interaction.prompt, draft.text)
        assertEquals(item.id, newThread.itemId)
        assertEquals(module.id, newThread.moduleId)
        assertTrue(newThread.turns.isEmpty())
        assertEquals(oldThread, stored.conversations.single { it.id == oldThread.id })
        assertEquals(2, stored.conversations.size)
        assertEquals(draft.conversationId, vm.ui.value.activeConversationId)
        assertEquals(requestsBefore, stored.requestsToday)
        assertTrue(app.asked.isEmpty())
        assertTrue(app.askedQuestions.isEmpty())

        // Only the subsequent explicit Send action calls the provider and reserves one request.
        vm.sendConversation(draft.text)
        withTimeout(10_000) {
            while (app.askedQuestions.isEmpty() || app.feedRepository.current().requestsToday < requestsBefore + 1) delay(5)
        }
        assertEquals(listOf(interaction.prompt), app.askedQuestions.toList())
        assertEquals(requestsBefore + 1, app.feedRepository.current().requestsToday)
        assertEquals(listOf(note), app.askedContexts.single())
    }

    @Test fun staleContentContextOrLogoutCannotResolveAnOldAiChipId() = runBlocking {
        listOf("content", "context", "logout").forEach { change ->
            val before = state("stale-$change")
            app.feedRepository.update { before }
            val item = before.items.single()
            val stableId = AiSuggestedInteractions095.suggestionId(item, interaction)
            when (change) {
                "content" -> app.feedRepository.update { current -> current.copy(items = current.items.map {
                    if (it.id == item.id) it.copy(summary = "Changed source content after rendering the chip.") else it
                }) }
                "context" -> app.feedRepository.update { current -> current.copy(context = listOf(
                    ContextEntry(id = "changed-scope", text = "A different confirmed priority", scopes = setOf("news")))) }
                "logout" -> vm.disconnect().join()
            }
            val countBeforeSelection = app.feedRepository.current().conversations.size

            vm.prepareSuggestion(item.id, stableId, "en").join()

            assertNull("$change invalidates stale selection", vm.ui.value.suggestionDraft)
            assertEquals(countBeforeSelection, app.feedRepository.current().conversations.size)
            assertTrue(app.askedQuestions.isEmpty())
        }
    }

    @Test fun disconnectedReaderCanPrefillItemSpecificLocalPromptAndAcknowledgementsAreIdempotent() = runBlocking {
        vm.disconnect().join()
        val item = app.feedRepository.current().items.single()
        val requestsBefore = app.feedRepository.current().requestsToday

        vm.prepareSuggestion(item.id, "local:explain", "en").join()

        val first = requireNotNull(vm.ui.value.suggestionDraft) { "offline fallback should create a local draft" }
        assertTrue(first.text.contains(item.title))
        assertTrue(app.askedQuestions.isEmpty())
        assertEquals(requestsBefore, app.feedRepository.current().requestsToday)
        vm.suggestionDraftApplied("stale-ack")
        assertEquals(first, vm.ui.value.suggestionDraft)

        vm.prepareSuggestion(item.id, "local:explain", "en").join()
        val current = requireNotNull(vm.ui.value.suggestionDraft) { "second local draft should replace the first" }
        assertNotEquals(first.id, current.id)
        vm.suggestionDraftApplied(first.id) // A late effect cannot clear a newer draft.
        assertEquals(current, vm.ui.value.suggestionDraft)
        vm.suggestionDraftApplied(current.id)
        vm.suggestionDraftApplied(current.id)
        assertNull(vm.ui.value.suggestionDraft)
        assertTrue(app.askedQuestions.isEmpty())
        assertEquals(requestsBefore, app.feedRepository.current().requestsToday)
    }

    @Test fun contextImportAndResetClearSuggestionDraftAndDerivedCacheWithoutLosingSourceFacts() = runBlocking {
        var current = app.feedRepository.current()
        val item = current.items.single()
        vm.prepareSuggestion(item.id, AiSuggestedInteractions095.suggestionId(item, interaction), "en").join()
        assertNotNull(vm.ui.value.suggestionDraft)

        val imported = PersonalContextImport(name = current.name, region = current.region, timezone = current.timezone,
            entries = listOf(ContextEntry(id = "new-note", text = "A reviewed context update", scopes = setOf("news"))))
        vm.prepareContextText(FeedJson.encodeToString(imported))
        withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importPreview != null } }
        vm.confirmImport()
        withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importSummary == "context" } }
        current = app.feedRepository.current()
        val afterImport = current.items.single()
        assertNull(vm.ui.value.suggestionDraft)
        assertTrue(afterImport.suggestedInteractions.isEmpty())
        assertEquals(item.title, afterImport.title)
        assertEquals(item.sourceUrl, afterImport.sourceUrl)

        // Re-seed an applicable local cache solely to verify reset removes it and pending UI state.
        val reseeded = state("reset-item")
        app.feedRepository.update { reseeded }
        vm.prepareSuggestion(reseeded.items.single().id,
            AiSuggestedInteractions095.suggestionId(reseeded.items.single(), interaction), "en").join()
        assertNotNull(vm.ui.value.suggestionDraft)
        vm.reset().join()
        assertNull(vm.ui.value.suggestionDraft)
        assertTrue(app.feedRepository.current().items.isEmpty())
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun savedContextAndModuleScopeChangesClearOnlyDerivedSuggestions() = runBlocking {
        val original = app.feedRepository.current()
        val item = original.items.single()
        vm.prepareSuggestion(item.id, AiSuggestedInteractions095.suggestionId(item, interaction), "en").join()
        assertNotNull(vm.ui.value.suggestionDraft)

        vm.saveContext(original.name, original.region, original.timezone,
            listOf(note.copy(text = "A revised confirmed interest")))!!.join()
        assertNull(vm.ui.value.suggestionDraft)
        val afterContext = app.feedRepository.current().items.single()
        assertTrue(afterContext.suggestedInteractions.isEmpty())
        assertEquals(item.title, afterContext.title)
        assertEquals(item.sourceUrl, afterContext.sourceUrl)

        val reseeded = state("module-scope-item")
        app.feedRepository.update { reseeded }
        vm.prepareSuggestion(reseeded.items.single().id,
            AiSuggestedInteractions095.suggestionId(reseeded.items.single(), interaction), "en").join()
        assertNotNull(vm.ui.value.suggestionDraft)
        vm.saveModule(module.copy(query = "a changed news focus"))!!.join()
        assertNull(vm.ui.value.suggestionDraft)
        val afterModule = app.feedRepository.current().items.single()
        assertTrue(afterModule.suggestedInteractions.isEmpty())
        assertEquals(reseeded.items.single().title, afterModule.title)
        assertEquals(reseeded.items.single().sourceUrl, afterModule.sourceUrl)
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun reviewedFullBackupNeverRestoresSuggestionCacheOrPendingDraft() = runBlocking {
        val original = app.feedRepository.current()
        val item = original.items.single()
        vm.prepareSuggestion(item.id, AiSuggestedInteractions095.suggestionId(item, interaction), "en").join()
        assertNotNull(vm.ui.value.suggestionDraft)

        val file = File(app.cacheDir, "suggestion-backup-synthetic.json").apply {
            writeText(FeedJson.encodeToString(original))
        }
        vm.prepareFeedBackup(android.net.Uri.fromFile(file))
        withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importPreview?.kind == "backup" } }
        vm.confirmImport()
        withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importSummary == "backup" } }

        val restored = app.feedRepository.current()
        assertNull(vm.ui.value.suggestionDraft)
        assertEquals(item.title, restored.items.single().title)
        assertTrue(restored.items.single().suggestedInteractions.isEmpty())
        assertEquals(original.savedIds, restored.savedIds)
        assertTrue(app.askedQuestions.isEmpty())
    }

    private suspend fun openViewModel() {
        stores = ViewModelStore()
        vm = ViewModelProvider(stores, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { vm.ui.first { !it.loading } }
    }

    private fun state(itemId: String, conversations: List<AiConversation> = emptyList()): FeedState {
        val raw = FeedItem(id = itemId, moduleId = module.id, kind = module.kind,
            title = "A verified public technology update", summary = "The original source announces one clear change.",
            sourceName = "Example publisher", sourceUrl = "https://example.com/$itemId", publishedAtMillis = System.currentTimeMillis(),
            fetchedAtMillis = System.currentTimeMillis())
        val base = FeedState(modules = listOf(module), context = listOf(note), items = listOf(raw),
            connection = AiConnection(mode = "direct", model = "gpt-6-luna"), name = "Fixture reader",
            region = "Tanzania", timezone = "Africa/Dar_es_Salaam", requestsToday = 3,
            dailyRequestLimit = 20, usageDate = FeedLogic.dateAt(System.currentTimeMillis(), "Africa/Dar_es_Salaam"),
            conversations = conversations)
        val cached = raw.copy(
            suggestedInteractions = listOf(interaction),
            suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(raw),
            suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(base, module, "en"),
            suggestedInteractionsGeneratedAtMillis = System.currentTimeMillis(),
        )
        return base.copy(items = listOf(cached))
    }
}
