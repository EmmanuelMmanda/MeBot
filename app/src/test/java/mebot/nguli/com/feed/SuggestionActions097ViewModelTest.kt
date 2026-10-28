package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mebot.nguli.com.core.*
import mebot.nguli.com.security.AppLockController
import mebot.nguli.com.security.LockRecord
import mebot.nguli.com.security.LockStorage
import mebot.nguli.com.ui.ChatGptJourneyApplication
import mebot.nguli.com.ui.UiFixtureChatGptAuth
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MeBot suggestion preparation is separate from the specific Send or Edit action. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Suggestion097TestApplication::class)
class SuggestionActions097ViewModelTest {
    private lateinit var app: ChatGptJourneyApplication
    private lateinit var stores: ViewModelStore
    private lateinit var vm: FeedViewModel

    private val module = FeedModule(id = "tech", kind = "technology", title = "Technology",
        query = "public technology pilot", sources = listOf(
            FeedSource("Example publisher", "https://example.com/feed.xml"),
        ))
    private val note = ContextEntry(id = "tech-note", text = "A confirmed interest in small technology pilots",
        scopes = setOf("technology"))
    private val interaction = AiSuggestedInteraction(label = "Check the source date",
        prompt = "Which publication date does this source show?")
    private val interactions = listOf(
        interaction,
        AiSuggestedInteraction(label = "Examine the claim", prompt = "What evidence supports the principal claim in this source?"),
        AiSuggestedInteraction(label = "Test an assumption", prompt = "Which assumption could materially change the conclusion?"),
        AiSuggestedInteraction(label = "Compare approaches", prompt = "How does the implementation cost compare with alternatives?"),
        AiSuggestedInteraction(label = "Find a limitation", prompt = "What unresolved limitation matters most for a small pilot?"),
    )

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.chatGptAuth.clearAll()
        app.chatGptAuth.restoreSavedAccount()
        app.feedSecrets.clear()
        app.feedSecrets.write("synthetic-suggestion-provider-key")
        app.feedRepository.erase()
        app.repository.update { AppState(settings = Settings(language = "en")) }
        app.suggestionQuestions = interactions.map { it.prompt }
        app.asked.clear(); app.askedQuestions.clear(); app.askedContexts.clear(); app.checked.clear()
        app.suggestedScopes.clear(); app.suggestedQuestionSets.clear(); app.suggestionProviderStates.clear(); app.suggestedAttachments.clear()
        app.suggestionGate = null; app.onSuggestionRequested = null
        app.feedRepository.update { fixtureState() }
        stores = ViewModelStore()
        vm = ViewModelProvider(stores, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { vm.ui.first { it.state != null && it.connected && !it.loading } }
        Unit
    }

    @After fun tearDown() {
        if (::stores.isInitialized) stores.clear()
        if (::app.isInitialized) app.alarms.clearAll()
        Dispatchers.resetMain()
    }

    @Test fun openingOptionsIsReadOnlyAndPrimaryTapSendsOnceToCorrectItemThread() = runBlocking {
        val before = app.feedRepository.current()
        val item = before.items.single()
        val target = requireNotNull(SuggestionTargets097.item(before, item.id, "en"))
        val options = vm.suggestionOptions097(target, "en")
        assertEquals("Only complete provider-authored sets render", 5, options.size)
        val option = options.single { it.questionText == interaction.prompt }
        assertTrue("opening the sheet only renders prepared options", app.askedQuestions.isEmpty())
        assertTrue("opening the sheet does not trigger suggestion inference", app.suggestedQuestionSets.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.feedRepository.current().conversations.isEmpty())

        val firstTap = vm.sendSuggestion(target, option.id, "en")
        val duplicateTap = vm.sendSuggestion(target, option.id, "en")
        firstTap.join(); duplicateTap.join()
        val answered = withTimeout(10_000) {
            app.feedRepository.awaitFeedState { it.conversations.singleOrNull()?.turns?.lastOrNull()?.role == "assistant" }
        }
        val thread = answered.conversations.single()
        assertEquals(item.id, thread.itemId)
        assertEquals(module.id, thread.moduleId)
        assertEquals(listOf("user", "assistant"), thread.turns.map { it.role })
        assertEquals(interaction.prompt, thread.turns.first().text)
        assertEquals(1, app.askedQuestions.count { it == interaction.prompt })
        assertEquals(before.requestsToday + 1, answered.requestsToday)
    }

    @Test fun pencilOnlyCreatesTheExactScopedEditableDraft() = runBlocking {
        val before = app.feedRepository.current()
        val item = before.items.single()
        val target = requireNotNull(SuggestionTargets097.item(before, item.id, "en"))
        val option = vm.suggestionOptions097(target, "en").single { it.questionText == interaction.prompt }

        vm.editSuggestion(target, option.id, "en").join()

        val draft = requireNotNull(vm.ui.value.suggestionDraft)
        val thread = app.feedRepository.current().conversations.single { it.id == draft.conversationId }
        assertEquals(option.questionText, draft.text)
        assertEquals(item.id, thread.itemId)
        assertEquals(module.id, thread.moduleId)
        assertTrue(thread.turns.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun moduleOptionsRequireOneExplicitBudgetedGenerateAndCacheExactlyFiveQuestions() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val opened = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.NEEDS_GENERATION, opened.status)
        assertTrue(opened.options.isEmpty())
        assertTrue("opening never invokes the AI provider", app.suggestedQuestionSets.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release
        val first = requireNotNull(vm.generateSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        val duplicate = requireNotNull(vm.generateSuggestions097(target, "en"))
        assertEquals("duplicate tap joins the same request", first.requestId, duplicate.requestId)
        assertSame(first.job, duplicate.job)
        release.complete(Unit)
        first.job.join()

        val ready = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, ready.status)
        assertEquals(5, ready.options.size)
        assertNotNull(ready.validUntilMillis)
        assertEquals(before.requestsToday + 1, app.feedRepository.current().requestsToday)
        assertEquals(1, app.suggestedQuestionSets.size)
        assertTrue("suggestion generation does not append a chat turn", app.feedRepository.current().conversations.isEmpty())
        assertTrue("generation is not a chat answer/send", app.askedQuestions.isEmpty())
    }

    @Test fun readyRoutePrefetchCoalescesAndAnOpenedSheetJoinsWithoutOwningCancellation() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release

        val prepared = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        assertFalse("route-owned preparation is not canceled by sheet dismissal", prepared.cancelOnDismiss)
        val detailHandoff = requireNotNull(vm.prepareSuggestions097(target, "en"))
        assertEquals("same-target routes adopt one in-flight provider request", prepared.requestId, detailHandoff.requestId)
        assertSame(prepared.job, detailHandoff.job)
        assertNotEquals(prepared.preparationLeaseId, detailHandoff.preparationLeaseId)
        vm.releaseSuggestionPreparation097(prepared)
        assertTrue("the old Home owner cannot cancel a request adopted by Detail", detailHandoff.job.isActive)
        val sheetJoin = requireNotNull(vm.generateSuggestions097(target, "en"))
        assertEquals(prepared.requestId, sheetJoin.requestId)
        assertFalse(sheetJoin.cancelOnDismiss)
        assertEquals(SuggestionSheetStatus097.GENERATING, vm.suggestionSheet097(target, "en").status)

        release.complete(Unit)
        prepared.job.join()

        val ready = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, ready.status)
        assertEquals("the first five choices are ready before the sheet is opened", 5, ready.options.size)
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals(before.requestsToday + 1, app.feedRepository.current().requestsToday)
    }

    @Test fun preparingANewActiveTargetCancelsThePreviousHiddenRequest() = runBlocking {
        val before = app.feedRepository.current()
        val oldTarget = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val newTarget = SuggestionTargets097.general(before, "en")
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.trySend(Unit) }
        app.suggestionGate = release

        val oldRequest = requireNotNull(vm.prepareSuggestions097(oldTarget, "en"))
        withTimeout(5_000) { entered.receive() }
        val activeRequest = requireNotNull(vm.prepareSuggestions097(newTarget, "en"))
        withTimeout(5_000) { entered.receive() }
        assertNotEquals(oldRequest.requestId, activeRequest.requestId)
        assertTrue("a target change cancels the old hidden request", oldRequest.job.isCancelled)
        assertEquals(newTarget, vm.ui.value.suggestionGeneration?.target)

        release.complete(Unit)
        activeRequest.job.join()
        assertTrue(app.feedRepository.current().suggestionSets097.none { it.targetKind == "module" })
        assertEquals(1, app.feedRepository.current().suggestionSets097.count { it.targetKind == "general" })
    }

    @Test fun navigationCleanupPreservesOnlyAnAdoptedRoutePrefetchLease() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release
        val homeLease = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        val detailLease = requireNotNull(vm.prepareSuggestions097(target, "en"))

        vm.cancelConversationOpenForNavigation()
        vm.clearAnswerForNavigation()
        vm.releaseSuggestionPreparation097(homeLease)
        assertTrue("navigation and old Home cleanup cannot cancel Detail's adopted lease", detailLease.job.isActive)

        release.complete(Unit)
        detailLease.job.join()
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals(1, app.feedRepository.current().suggestionSets097.count { it.revision == target.revision })
    }

    @Test fun navigationBridgeIsAcquiredBeforeHomeDisposalAndDetailAdoptsSameRequest() = runBlocking {
        val before = app.feedRepository.update { state ->
            state.copy(items = state.items.map(AiSuggestedInteractions095::clear), suggestionSets097 = emptyList())
        }
        withTimeout(5_000) { vm.ui.first { it.state?.suggestionSets097.isNullOrEmpty() && it.state?.items == before.items } }
        val target = requireNotNull(SuggestionTargets097.item(before, before.items.single().id, "en"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release

        val homeLease = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        val requestsAtDispatch = app.feedRepository.current().requestsToday
        val bridge = requireNotNull(vm.retainActiveSuggestionPreparationForNavigation097(target))
        assertEquals("The navigation bridge adopts the in-flight request ID", homeLease.requestId, bridge.requestId)
        assertSame("The bridge is an owner of the existing provider job", homeLease.job, bridge.job)
        assertNotEquals(homeLease.preparationLeaseId, bridge.preparationLeaseId)
        assertEquals("Adoption does not reserve another request", requestsAtDispatch, app.feedRepository.current().requestsToday)

        // This is the real transition order: the destination is not mounted yet when the old
        // Home route and conversation-navigation cleanup release their owners.
        vm.cancelConversationOpenForNavigation()
        vm.clearAnswerForNavigation()
        vm.releaseSuggestionPreparation097(homeLease)
        assertTrue("The host bridge keeps the request alive after old Home cleanup", bridge.job.isActive)

        val detailLease = requireNotNull(vm.prepareSuggestions097(target, "en"))
        assertEquals("Detail joins the bridged request", bridge.requestId, detailLease.requestId)
        assertSame(bridge.job, detailLease.job)
        vm.releaseSuggestionPreparation097(bridge)
        assertTrue("Releasing host ownership cannot cancel Detail's adopted job", detailLease.job.isActive)

        release.complete(Unit)
        detailLease.job.join()
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals("One request reservation covers Home→Detail", before.requestsToday + 1, app.feedRepository.current().requestsToday)
        assertEquals(1, app.feedRepository.current().suggestionSets097.count { it.targetId == target.targetId && it.revision == target.revision })
    }

    @Test fun navigationBridgeNeverStartsWorkAndOnlyAdoptsTheExactActivePrefetch() = runBlocking {
        val before = app.feedRepository.update { state ->
            state.copy(items = state.items.map(AiSuggestedInteractions095::clear), suggestionSets097 = emptyList())
        }
        withTimeout(5_000) { vm.ui.first { it.state?.suggestionSets097.isNullOrEmpty() && it.state?.items == before.items } }
        val target = requireNotNull(SuggestionTargets097.item(before, before.items.single().id, "en"))
        val otherTarget = requireNotNull(SuggestionTargets097.general(before, "en"))
        assertNull("No existing route request means navigation cannot start one", vm.retainActiveSuggestionPreparationForNavigation097(target))
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.suggestedQuestionSets.isEmpty())

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release
        val active = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        val requestsAtDispatch = app.feedRepository.current().requestsToday

        assertNull("A different target cannot adopt the active request", vm.retainActiveSuggestionPreparationForNavigation097(otherTarget))
        assertTrue("A rejected bridge does not cancel the active target", active.job.isActive)
        assertEquals(requestsAtDispatch, app.feedRepository.current().requestsToday)
        assertTrue("No provider result exists while the gate is held", app.suggestedQuestionSets.isEmpty())

        release.complete(Unit)
        active.job.join()
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals(before.requestsToday + 1, app.feedRepository.current().requestsToday)
    }

    @Test fun backgroundCleanupCancelsBridgedPrefetchButLeavesExplicitGenerationAlone() = runBlocking {
        val before = app.feedRepository.update { state ->
            state.copy(items = state.items.map(AiSuggestedInteractions095::clear), suggestionSets097 = emptyList())
        }
        withTimeout(5_000) { vm.ui.first { it.state?.suggestionSets097.isNullOrEmpty() && it.state?.items == before.items } }
        val target = requireNotNull(SuggestionTargets097.item(before, before.items.single().id, "en"))
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.trySend(Unit) }
        app.suggestionGate = release

        val route = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.receive() }
        val bridge = requireNotNull(vm.retainActiveSuggestionPreparationForNavigation097(target))
        vm.cancelSuggestionPreparationsOnBackground099()
        route.job.join()
        assertTrue("ON_STOP cancels the hidden provider work even with a host bridge", route.job.isCancelled)
        assertNull(vm.ui.value.suggestionGeneration)

        val explicit = requireNotNull(vm.generateSuggestions097(target, "en"))
        withTimeout(5_000) { entered.receive() }
        vm.cancelSuggestionPreparationsOnBackground099()
        assertTrue("Lifecycle cleanup must not cancel explicit sheet work", explicit.job.isActive)
        release.complete(Unit)
        explicit.job.join()
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals(before.requestsToday + 2, app.feedRepository.current().requestsToday)
        assertNotNull(bridge.preparationLeaseId)
    }

    @Test fun conversationTargetSwitchBeforeProviderDispatchRejectsTheOldPreparedRequest() = runBlocking {
        val firstThread = AiConversation(id = "prefetch-active-thread", moduleId = module.id)
        val secondThread = AiConversation(id = "prefetch-other-thread", moduleId = module.id)
        app.feedRepository.update { fixtureState(conversations = listOf(firstThread, secondThread)) }
        vm.selectConversation(firstThread.id).join()
        withTimeout(5_000) { vm.ui.first { it.activeConversationId == firstThread.id && it.conversationReady } }
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.conversation(before, firstThread.id, "en"))

        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("suggestion-active-target-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val handle = requireNotNull(vm.prepareSuggestions097(target, "en"))
            withTimeout(5_000) {
                app.feedRepository.awaitFeedState { it.requestsToday == before.requestsToday + 1 }
            }
            @Suppress("UNCHECKED_CAST")
            val internalUi = privateField("_ui") as MutableStateFlow<FeedUiState>
            internalUi.update { it.copy(activeConversationId = secondThread.id, conversationReady = true) }
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
            handle.job.join()

            assertTrue("the stale conversation is rejected before provider dispatch", app.suggestedQuestionSets.isEmpty())
            assertFalse(app.feedRepository.current().suggestionSets097.any {
                it.targetKind == "conversation" && it.targetId == firstThread.id && it.revision == target.revision
            })
            assertEquals("reservation remains visible even though the stale provider call is skipped",
                before.requestsToday + 1, app.feedRepository.current().requestsToday)
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
    }

    @Test fun loadMoreAppendsNovelQuestionsAndIncompleteRetryKeepsEveryExistingOption() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val firstQuestions = listOf(
            "What evidence in this source supports the central technology pilot claim?",
            "Which groups does the source identify as affected by this technology pilot?",
            "What limitation could change how someone uses this technology pilot?",
            "Which publication date does this source show for the technology pilot?",
            "What context helps compare this technology pilot with current alternatives?",
        )
        val moreQuestions = listOf(
            "Which partner organizations will deploy pilot infrastructure across rural clinics?",
            "How do long term maintenance costs compare with cloud hosting expenses for smaller organizations?",
            "What adoption metrics distinguish trial enthusiasm from sustained user engagement over six months?",
            "Which regulatory approval steps precede clinical software procurement by regional hospitals?",
            "How might unreliable network coverage affect offline device setup and data synchronization?",
        )
        app.suggestionQuestions = firstQuestions
        requireNotNull(vm.generateSuggestions097(target, "en")).job.join()
        assertEquals(5, vm.suggestionSheet097(target, "en").options.size)

        app.suggestionQuestions = moreQuestions
        requireNotNull(vm.loadMoreSuggestions097(target, "en")).job.join()
        val expanded = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, expanded.status)
        assertEquals("Load more appends five provider-authored angles instead of replacing the first batch", 10, expanded.options.size)
        assertEquals(10, expanded.options.map { it.questionText.trim().lowercase() }.distinct().size)

        app.suggestionQuestions = firstQuestions
        requireNotNull(vm.loadMoreSuggestions097(target, "en")).job.join()
        val afterFailure = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, afterFailure.status)
        assertEquals(10, afterFailure.options.size)
        assertEquals("suggestion_incomplete", afterFailure.loadMoreFailureCode)
        val afterFailureState = app.feedRepository.current()
        assertEquals("Only the two successful module-target batches are retained", 2,
            afterFailureState.suggestionSets097.count {
                it.targetKind == "module" && it.targetId == target.targetId && it.revision == target.revision
            })
        assertEquals("The fixture's legacy item cache remains in addition to those batches",
            before.suggestionSets097.size + 2, afterFailureState.suggestionSets097.size)
        assertEquals(before.requestsToday + 3, app.feedRepository.current().requestsToday)
    }

    @Test fun rapidDuplicateDesiredStarIntentsEmitFeedbackOnlyForDurableChanges() = runBlocking {
        val itemId = app.feedRepository.current().items.single().id
        val committedStates = mutableListOf<Boolean>()
        val followWrites = List(2) {
            async(Dispatchers.Default) {
                vm.setStarred099(itemId, true) { committed ->
                    synchronized(committedStates) {
                        committedStates += committed.starredStories.any { it.itemId == itemId }
                    }
                }.join()
            }
        }
        followWrites.forEach { it.await() }
        assertEquals(listOf(true), committedStates)
        assertTrue(app.feedRepository.current().starredStories.any { it.itemId == itemId })

        val unfollowWrites = List(2) {
            async(Dispatchers.Default) {
                vm.setStarred099(itemId, false) { committed ->
                    synchronized(committedStates) {
                        committedStates += committed.starredStories.any { it.itemId == itemId }
                    }
                }.join()
            }
        }
        unfollowWrites.forEach { it.await() }
        assertEquals("Only state changes produce snackbar feedback", listOf(true, false), committedStates)
        assertFalse(app.feedRepository.current().starredStories.any { it.itemId == itemId })
    }

    @Test fun canceledPrefetchMayResumeButProviderFailureDoesNotAutomaticallyRetry() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.onSuggestionRequested = { entered.complete(Unit) }
        app.suggestionGate = release
        val canceled = requireNotNull(vm.prepareSuggestions097(target, "en"))
        withTimeout(5_000) { entered.await() }
        vm.cancelSuggestionGeneration097(canceled.requestId)
        canceled.job.join()

        app.suggestionGate = null
        app.onSuggestionRequested = null
        val resumed = requireNotNull(vm.prepareSuggestions097(target, "en"))
        resumed.job.join()
        assertEquals(1, app.suggestedQuestionSets.size)

        val otherTarget = requireNotNull(SuggestionTargets097.general(app.feedRepository.current(), "en"))
        app.suggestionQuestions = app.suggestionQuestions.take(4)
        val failed = requireNotNull(vm.prepareSuggestions097(otherTarget, "en"))
        failed.job.join()
        assertEquals(SuggestionSheetStatus097.INCOMPLETE, vm.suggestionSheet097(otherTarget, "en").status)
        assertNull("an incomplete provider result is not retried by another preparation lifecycle callback",
            vm.prepareSuggestions097(otherTarget, "en"))
        assertEquals(2, app.suggestedQuestionSets.size)
        assertTrue(app.feedRepository.current().requestsToday >= before.requestsToday + 2)
    }

    @Test fun incompleteProviderSetIsNotPaddedOrCached() = runBlocking {
        app.suggestionQuestions = app.suggestionQuestions.take(4)
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.general(before, "en"))
        val handle = requireNotNull(vm.generateSuggestions097(target, "en"))
        handle.job.join()
        val state = app.feedRepository.current()
        val snapshot = vm.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.INCOMPLETE, snapshot.status)
        assertEquals("suggestion_incomplete", snapshot.failureCode)
        assertTrue(snapshot.options.isEmpty())
        assertFalse("a four-question result is not padded or cached", state.suggestionSets097.any {
            it.targetKind == target.kind.name.lowercase() && it.targetId == target.targetId
        })
        assertEquals(before.requestsToday + 1, state.requestsToday)
        assertEquals(1, app.suggestedScopes.size)
        assertTrue(state.conversations.isEmpty())
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun staleTargetCannotStartSuggestionGeneration() = runBlocking {
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        app.feedRepository.update { current -> current.copy(modules = current.modules.map {
            if (it.id == module.id) it.copy(query = "Changed goal after the sheet opened") else it
        }) }
        assertEquals(SuggestionSheetStatus097.STALE_TARGET, vm.suggestionSheet097(target, "en").status)
        assertNull(vm.generateSuggestions097(target, "en"))
        assertTrue(app.suggestedQuestionSets.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
    }

    @Test fun setup099IgnoresStaleChatGptSuccessWhenTheActiveConnectionIsDifferent() = runBlocking {
        app.feedRepository.update { current -> current.copy(
            setup099 = Setup099State(),
            connection = AiConnection(mode = "direct", model = "gpt-6-luna"),
        ) }

        vm.recordSetup099ChatGptConfirmed("stale-chatgpt-account", setupEligible = true).join()

        val after = app.feedRepository.current()
        assertEquals("A stale success callback cannot arm import for a different provider/account", Setup099State(), after.setup099)
        assertEquals("The active provider is unchanged", "direct", after.connection.mode)
    }

    @Test fun setup099AdvancesOnlyAfterAConfirmedNonblankContextEntryIsSaved() = runBlocking {
        val account = "verified-account-099"
        app.feedRepository.update { current -> current.copy(
            context = listOf(ContextEntry(id = "unconfirmed-note", text = "Preview only", confirmed = false)),
            setup099 = Setup099State(stage = Setup099Stage.IMPORT_CONTEXT,
                firstChatGptAccountKey = account, pendingImportAccountKey = account),
        ) }

        vm.advanceSetup099ToStart().join()
        assertEquals("An unconfirmed note cannot advance setup", Setup099Stage.IMPORT_CONTEXT, app.feedRepository.current().setup099.stage)

        val confirmed = ContextEntry(id = "confirmed-note", text = "A confirmed nonblank interest", confirmed = true)
        app.feedRepository.update { it.copy(context = listOf(confirmed)) }
        vm.advanceSetup099ToStart().join()
        val after = app.feedRepository.current().setup099
        assertEquals(Setup099Stage.START_FEED, after.stage)
        assertEquals("The import route is consumed after confirmed context", "", after.pendingImportAccountKey)
        assertEquals(account, after.importRouteAcknowledgedAccountKey)
    }

    @Test fun setup099FinishAtomicallyCompletesWizardAndOnboarding() = runBlocking {
        app.feedRepository.update { it.copy(setup099 = Setup099State(stage = Setup099Stage.START_FEED)) }

        vm.finishSetup099().join()

        val after = app.feedRepository.current()
        assertTrue(after.onboardingComplete)
        assertEquals(Setup099Stage.COMPLETE, after.setup099.stage)
        assertEquals(Setup099ConnectionEntry.CHATGPT, after.setup099.preferredConnectionEntry099)
    }

    @Test fun providerEntryPreferenceChangesOnlyAfterSuccessfulConnection() = runBlocking {
        app.feedRepository.update { it.copy(setup099 = Setup099State()) }
        app.checkFailure = "unauthorized"
        vm.connect("synthetic-suggestion-provider-key", mode = "direct")
        withTimeout(10_000) { vm.ui.first { !it.connecting && it.error == "unauthorized" } }
        assertEquals("A failed provider check cannot mark a setup preference", Setup099ConnectionEntry.UNKNOWN,
            app.feedRepository.current().setup099.preferredConnectionEntry099)

        app.checkFailure = null
        vm.connect("synthetic-suggestion-provider-key", mode = "direct")
        withTimeout(10_000) {
            app.feedRepository.awaitFeedState {
                it.setup099.preferredConnectionEntry099 == Setup099ConnectionEntry.USER_SELECTED
            }
        }
        assertEquals(Setup099ConnectionEntry.USER_SELECTED, app.feedRepository.current().setup099.preferredConnectionEntry099)
        vm.disconnect().join()
        assertEquals("Disconnecting preserves the remembered successful choice", Setup099ConnectionEntry.USER_SELECTED,
            app.feedRepository.current().setup099.preferredConnectionEntry099)

        vm.useChatGptAccount(UiFixtureChatGptAuth.ACCOUNT_ID, null)
        withTimeout(10_000) {
            app.feedRepository.awaitFeedState {
                it.connection.mode == "chatgpt" && it.connection.accountId == UiFixtureChatGptAuth.ACCOUNT_ID &&
                    it.setup099.preferredConnectionEntry099 == Setup099ConnectionEntry.CHATGPT
            }
        }
        Unit
    }

    @Test fun starFeedbackCallbackOnlyFiresAfterTheFollowIsDurablyCommitted() = runBlocking {
        val itemId = app.feedRepository.current().items.single().id
        var committed: FeedState? = null
        vm.toggleStar(itemId) { committed = it }.join()
        assertTrue("The success callback receives the durable star", committed?.starredStories?.any { it.itemId == itemId } == true)

        var failedCallback = false
        vm.toggleStar("missing-story") { failedCallback = true }.join()
        assertFalse("A failed transaction cannot trigger success feedback", failedCallback)
        assertEquals("The normal error path reports the invalid target", "invalid_input", vm.ui.value.error)
    }

    @Test fun dismissingGenerationWhileProviderSlotIsQueuedPreventsProviderCallAndCacheCommit() = runBlocking {
        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("suggestion-queued-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val before = app.feedRepository.current()
            val target = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
            val handle = requireNotNull(vm.generateSuggestions097(target, "en"))
            withTimeout(10_000) { app.feedRepository.awaitFeedState { it.requestsToday == before.requestsToday + 1 } }
            assertTrue("provider call has not crossed the semaphore boundary", app.suggestedScopes.isEmpty())
            assertEquals(SuggestionSheetStatus097.GENERATING, vm.suggestionSheet097(target, "en").status)
            vm.cancelSuggestionGeneration097(handle.requestId)
            handle.job.join()
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
            val after = app.feedRepository.current()
            assertTrue("dismiss before dispatch prevents any provider request", app.suggestedScopes.isEmpty())
            assertEquals("cancelled generation does not commit cache", before.suggestionSets097, after.suggestionSets097)
            assertTrue("generation never appends a chat turn", after.conversations.isEmpty())
            assertEquals(before.requestsToday + 1, after.requestsToday)
            assertEquals(SuggestionSheetStatus097.NEEDS_GENERATION, vm.suggestionSheet097(target, "en").status)
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
    }

    @Test fun chatSuggestionKeepsUnsentAttachmentPendingInTheExistingThread() = runBlocking {
        val item = app.feedRepository.current().items.single()
        val alreadyUsed = AiAttachment(id = "used-097", name = "previous.txt", text = "Previously sent content", usedAtMillis = 10L)
        val unsent = AiAttachment(id = "pending-097", name = "draft.txt", text = "Private unsent content", usedAtMillis = 0L)
        val thread = AiConversation(id = "attachment-chat-097", moduleId = module.id, itemId = item.id,
            turns = listOf(AiTurn(id = "old-question-097", role = "user", text = "Compare the local pilot costs."),
                AiTurn(id = "assistant-filler-097", role = "assistant", text = "This test update relates to your approved technical interests.")),
            attachments = listOf(alreadyUsed, unsent))
        app.feedRepository.update { fixtureState(conversations = listOf(thread)) }
        withTimeout(10_000) { vm.ui.first { it.state?.conversations?.singleOrNull()?.id == thread.id } }
        vm.selectConversation(thread.id).join()
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.conversation(before, thread.id, "en"))
        app.suggestionQuestions = listOf(
            "What does the source say this toolkit can do during a small technology pilot?",
            "Which stated capability matters most for the local pilot described in this article?",
            "What evidence supports the claimed benefit for teams evaluating this toolkit?",
            "How does the reported implementation cost compare with alternatives for this use case?",
            "Which limitation in this report could make the toolkit unsuitable for a small trial?",
        )
        val requestsBeforeGenerate = before.requestsToday
        val generated = requireNotNull(vm.generateSuggestions097(target, "en"))
        generated.job.join()
        val option = vm.suggestionOptions097(target, "en").first()
        assertEquals("The explicit request prepares exactly five AI questions", 5, vm.suggestionOptions097(target, "en").size)
        assertTrue("item chat questions stay grounded in the actual item", option.questionText.contains("toolkit"))
        assertEquals("generation does not append a conversation turn", thread.turns, app.feedRepository.current().conversations.single().turns)
        assertEquals(requestsBeforeGenerate + 1, app.feedRepository.current().requestsToday)
        assertEquals(1, app.suggestedQuestionSets.size)
        assertEquals(listOf(alreadyUsed), app.suggestedAttachments.single())
        val serializedSuggestionState = FeedJson.encodeToString(app.suggestionProviderStates.single())
        assertFalse("unsent attachment bytes do not reach the provider state", serializedSuggestionState.contains("Private unsent content"))
        assertFalse("pending attachment names do not reach the provider state", serializedSuggestionState.contains("draft.txt"))

        vm.sendSuggestion(target, option.id, "en").join()

        val answered = withTimeout(10_000) {
            app.feedRepository.awaitFeedState { it.conversations.singleOrNull()?.turns?.lastOrNull()?.role == "assistant" }
        }
        val saved = answered.conversations.single()
        assertEquals(thread.id, saved.id)
        assertEquals("Suggestions do not mark an unrelated pending file as used", listOf(alreadyUsed, unsent), saved.attachments)
        assertEquals(listOf("user", "assistant", "user", "assistant"), saved.turns.map { it.role })
        assertTrue(app.askedQuestions.contains(option.questionText))
    }

    @Test fun staleModuleOrConversationSnapshotFailsClosedWithoutProviderOrQuotaUse() = runBlocking {
        val before = app.feedRepository.current()
        val item = before.items.single()
        val moduleTarget = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val moduleState = withPreparedSet(before, moduleTarget)
        app.feedRepository.update { moduleState }
        withTimeout(10_000) { vm.ui.first { it.state?.suggestionSets097?.isNotEmpty() == true } }
        val moduleOption = vm.suggestionOptions097(moduleTarget, "en").first()
        assertEquals(5, vm.suggestionOptions097(moduleTarget, "en").size)
        app.feedRepository.update { state -> state.copy(modules = state.modules.map {
            if (it.id == module.id) it.copy(query = "a newly selected technology scope") else it
        }) }
        vm.sendSuggestion(moduleTarget, moduleOption.id, "en").join()
        assertEquals("suggestion_changed", vm.ui.value.error)
        assertTrue(app.askedQuestions.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.feedRepository.current().conversations.isEmpty())

        // A conversation's full request transcript is part of the revision, even if IDs are unchanged.
        val thread = AiConversation(id = "existing-chat", moduleId = module.id, itemId = item.id,
            turns = listOf(AiTurn(id = "user-one", role = "user", text = "Compare the current pilot options.")))
        val restored = fixtureState().copy(conversations = listOf(thread))
        val restoredTarget = requireNotNull(SuggestionTargets097.conversation(restored, thread.id, "en"))
        app.feedRepository.update { withPreparedSet(restored, restoredTarget) }
        withTimeout(10_000) { vm.ui.first { it.state?.conversations?.singleOrNull()?.id == thread.id } }
        vm.selectConversation(thread.id).join()
        val active = app.feedRepository.current()
        val conversationTarget = requireNotNull(SuggestionTargets097.conversation(active, thread.id, "en"))
        val chatOption = vm.suggestionOptions097(conversationTarget, "en").first()
        app.feedRepository.update { state -> state.copy(conversations = state.conversations.map {
            if (it.id == thread.id) it.copy(turns = listOf(thread.turns.single().copy(text = "A different current chat topic."))) else it
        }) }
        vm.sendSuggestion(conversationTarget, chatOption.id, "en").join()
        assertEquals("suggestion_changed", vm.ui.value.error)
        assertTrue(app.askedQuestions.isEmpty())
        assertEquals(restored.requestsToday, app.feedRepository.current().requestsToday)
        assertEquals("A different current chat topic.", app.feedRepository.current().conversations.single().turns.single().text)
    }

    @Test fun staleItemOrConfirmedContextBeforeThreadCommitFailsWithoutProviderOrQuotaUse() = runBlocking {
        for (change in listOf("item", "context")) {
            val before = fixtureState(itemId = "story-097-$change")
            app.feedRepository.update { before }
            withTimeout(10_000) { vm.ui.first { it.state?.items?.singleOrNull()?.id == before.items.single().id } }
            val item = before.items.single()
            val target = requireNotNull(SuggestionTargets097.item(before, item.id, "en"))
            val option = vm.suggestionOptions097(target, "en").first()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            vm.beforeConversationOpen = { entered.complete(Unit); release.await() }
            val action = vm.sendSuggestion(target, option.id, "en")
            withTimeout(5_000) { entered.await() }
            app.feedRepository.update { state -> when (change) {
                "item" -> state.copy(items = state.items.map { if (it.id == item.id) it.copy(summary = "Changed item fact after the sheet was rendered.") else it })
                else -> state.copy(context = listOf(note.copy(text = "A changed approved module focus.")))
            } }
            release.complete(Unit)
            action.join()
            assertEquals("$change target revision is rejected", "suggestion_changed", vm.ui.value.error)
            assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
            assertTrue(app.askedQuestions.isEmpty())
            assertTrue(app.feedRepository.current().conversations.isEmpty())
            vm.clearMessage()
        }
        vm.beforeConversationOpen = {}
    }

    @Test fun targetDigestsCoverOlderRequestTurnsAttachmentsConfirmedModulesAndEndpointIdentity() {
        val base = fixtureState()
        val item = base.items.single()
        val opportunities = FeedModule(id = "opportunities", kind = "opportunities", title = "Opportunities")
        val scoped = base.copy(modules = base.modules + opportunities,
            context = base.context + ContextEntry(id = "opportunity-note", text = "Confirmed scholarship eligibility priority",
                scopes = setOf("opportunities"), confirmed = true))
        val general = SuggestionTargets097.general(scoped, "en")
        assertFalse("changed confirmed module context invalidates global scope",
            SuggestionTargets097.current(scoped.copy(context = scoped.context.map {
                if (it.id == "opportunity-note") it.copy(text = "A different eligibility priority") else it
            }), general, "en"))
        assertFalse("changed self-hosted endpoint invalidates the open sheet",
            SuggestionTargets097.current(scoped.copy(connection = scoped.connection.copy(serviceUrl = "https://example.net/v1")), general, "en"))
        assertFalse("disabled module is not a valid captured destination",
            SuggestionTargets097.current(scoped.copy(modules = scoped.modules.map {
                if (it.id == opportunities.id) it.copy(enabled = false) else it
            }), general, "en"))

        val itemTarget = requireNotNull(SuggestionTargets097.item(base, item.id, "en"))
        assertFalse("deleted item cannot be rebound to another target",
            SuggestionTargets097.current(base.copy(items = emptyList()), itemTarget, "en"))
        assertFalse("disabled item module invalidates its target",
            SuggestionTargets097.current(base.copy(modules = base.modules.map { it.copy(enabled = false) }), itemTarget, "en"))

        val weatherModule = FeedModule(id = "weather-097", kind = "weather", title = "Weather")
        val weatherItem = item.copy(id = "weather-story-097", moduleId = weatherModule.id, kind = "weather",
            weather = FeedWeather(20.0, 20.0, 0, 0, "Example"))
        val weatherState = base.copy(modules = base.modules + weatherModule, items = listOf(weatherItem))
        assertNull("weather modules do not expose an AI suggestion target",
            SuggestionTargets097.module(weatherState, weatherModule.id, "en"))
        assertNull("weather results do not expose an AI suggestion target",
            SuggestionTargets097.item(weatherState, weatherItem.id, "en"))
        val forgedWeatherTarget = SuggestionTargetSnapshot097(SuggestionTargetKind097.ITEM,
            weatherItem.id, weatherModule.id, weatherItem.id, "a".repeat(64))
        assertFalse("forged weather snapshot remains invalid", SuggestionTargets097.current(weatherState, forgedWeatherTarget, "en"))
        val weatherThread = AiConversation(id = "weather-chat-097", moduleId = weatherModule.id, itemId = weatherItem.id,
            turns = listOf(AiTurn(id = "weather-turn", role = "user", text = "What is today's weather?")))
        assertNull("a conversation linked to weather cannot silently fall back to general suggestions",
            SuggestionTargets097.conversation(weatherState.copy(conversations = listOf(weatherThread)), weatherThread.id, "en"))
        val weatherModuleThread = AiConversation(id = "weather-module-chat-097", moduleId = weatherModule.id,
            turns = listOf(AiTurn(id = "weather-module-turn", role = "user", text = "What changed?")))
        assertNull("an explicitly weather-scoped chat cannot silently become general scope",
            SuggestionTargets097.conversation(weatherState.copy(conversations = listOf(weatherModuleThread)), weatherModuleThread.id, "en"))

        val turns = (0..5).map { index -> AiTurn(id = "history-$index", role = if (index % 2 == 0) "user" else "assistant",
            text = "Same ID and timestamp; request turn $index.", createdAtMillis = index.toLong()) }
        val attachment = AiAttachment(id = "attachment-stable", name = "notes.txt", text = "First bounded attachment body", usedAtMillis = 10L)
        val thread = AiConversation(id = "history-chat", title = "History chat", itemId = item.id, moduleId = module.id,
            turns = turns, attachments = listOf(attachment), createdAtMillis = 20L, updatedAtMillis = 30L)
        val chatState = base.copy(conversations = listOf(thread))
        val chatTarget = requireNotNull(SuggestionTargets097.conversation(chatState, thread.id, "en"))
        val alteredOlderTurn = thread.copy(turns = turns.map { if (it.id == "history-0") it.copy(text = "Replaced older request turn.") else it })
        assertFalse("older text in the exact request history invalidates the sheet",
            SuggestionTargets097.current(chatState.copy(conversations = listOf(alteredOlderTurn)), chatTarget, "en"))
        val alteredAttachment = thread.copy(attachments = listOf(attachment.copy(text = "Replaced attachment content")))
        assertFalse("same attachment ID and timestamp cannot conceal changed request bytes",
            SuggestionTargets097.current(chatState.copy(conversations = listOf(alteredAttachment)), chatTarget, "en"))
    }

    @Test fun navigationWhileSendWaitsForProviderSlotRollsBackUnsentTurnAndThread() = runBlocking {
        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("test-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val before = app.feedRepository.current()
            val item = before.items.single()
            val target = requireNotNull(SuggestionTargets097.item(before, item.id, "en"))
            val option = vm.suggestionOptions097(target, "en").single { it.questionText == interaction.prompt }
            val send = vm.sendSuggestion(target, option.id, "en")
            withTimeout(10_000) {
                app.feedRepository.awaitFeedState { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.questionText
                }
            }
            val queuedAskJob = privateField("suggestionAskJob") as? kotlinx.coroutines.Job
            val rollbackThreadId = privateField("pendingSuggestionThreadId") as? String
            val rollbackOriginal = privateField("pendingSuggestionOriginalThread") as? AiConversation
            val rollbackAppended = privateField("pendingSuggestionAppendedThread") as? AiConversation
            val rollbackEpoch = privateField("suggestionRollbackEpoch")
            val selectionGeneration = privateField("conversationSelectionGeneration")
            assertNotNull("the send must own a queued engine job before navigation cancels it", queuedAskJob)
            assertNotNull("new-thread cleanup must retain its id", rollbackThreadId)
            assertNotNull("new-thread cleanup must retain its empty original", rollbackOriginal)
            assertNotNull("new-thread cleanup must retain its exact appended version", rollbackAppended)
            val rollbackJob = vm.cancelConversationOpen()
            assertTrue("navigation must cancel the captured queued ask job", queuedAskJob!!.isCancelled)
            send.join()
            // The request was conservatively reserved before entering the semaphore, but its
            // unsent user turn and action-created thread must not masquerade as a submitted chat.
            var rollbackPhase = "waiting-for-empty-thread"
            try {
                withTimeout(10_000) {
                    app.feedRepository.awaitFeedState { it.conversations.isEmpty() }
                    rollbackPhase = "empty-thread-observed-waiting-for-cleanup-job"
                    rollbackJob?.join()
                    rollbackPhase = "cleanup-job-completed"
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val stateAtTimeout = kotlinx.coroutines.withTimeoutOrNull(1_000) { app.feedRepository.current() }
                val current = stateAtTimeout?.conversations?.singleOrNull { it.id == rollbackThreadId }
                throw AssertionError("rollback timeout: phase=$rollbackPhase, cleanupJobCompleted=${rollbackJob?.isCompleted}, " +
                    "cleanupJobCancelled=${rollbackJob?.isCancelled}, id=$rollbackThreadId, epoch=$rollbackEpoch, selectionGeneration=$selectionGeneration, " +
                    "originalId=${rollbackOriginal!!.id}, appendedId=${rollbackAppended!!.id}, originalTurns=${rollbackOriginal.turns.size}, " +
                    "appendedTurns=${rollbackAppended!!.turns.size}, currentId=${current?.id}, currentTurns=${current?.turns?.size}, " +
                    "currentMatchesAppended=${current == rollbackAppended}, askCancelled=${queuedAskJob.isCancelled}, " +
                    "requestsToday=${stateAtTimeout?.requestsToday ?: "unavailable"}", timeout)
            }
            assertTrue(app.askedQuestions.isEmpty())
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun navigationWhileSendWaitsForProviderSlotRestoresExistingChatHistory() = runBlocking {
        val item = app.feedRepository.current().items.single()
        val originalThread = AiConversation(id = "existing-chat-097", moduleId = module.id, itemId = item.id,
            turns = listOf(AiTurn(id = "old-user", role = "user", text = "Compare the source's stated costs.")))
        app.feedRepository.update { fixtureState(conversations = listOf(originalThread)) }
        withTimeout(10_000) { vm.ui.first { it.state?.conversations?.singleOrNull()?.id == originalThread.id } }
        vm.selectConversation(originalThread.id).join()
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.conversation(before, originalThread.id, "en"))
        val generated = requireNotNull(vm.generateSuggestions097(target, "en"))
        generated.job.join()
        val option = vm.suggestionOptions097(target, "en").first()
        assertFalse("chat follow-up must be distinct from the prior user turn", option.questionText == originalThread.turns.single().text)

        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("existing-test-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val send = vm.sendSuggestion(target, option.id, "en")
            withTimeout(10_000) {
                app.feedRepository.awaitFeedState { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.questionText
                }
            }
            val queuedAskJob = privateField("suggestionAskJob") as? kotlinx.coroutines.Job
            val rollbackThreadId = privateField("pendingSuggestionThreadId") as? String
            val rollbackOriginal = privateField("pendingSuggestionOriginalThread") as? AiConversation
            val rollbackAppended = privateField("pendingSuggestionAppendedThread") as? AiConversation
            val rollbackEpoch = privateField("suggestionRollbackEpoch")
            val selectionGeneration = privateField("conversationSelectionGeneration")
            assertNotNull("existing-chat send must own a queued ask job before navigation cancels it", queuedAskJob)
            // CONVERSATION targets roll back from the captured original/appended snapshots; the
            // created-thread ID is intentionally null because no new thread was created.
            assertEquals(originalThread, rollbackOriginal)
            assertNotNull("the exact appended existing-thread revision must be retained for rollback", rollbackAppended)
            val rollbackJob = vm.cancelConversationOpen()
            assertTrue("navigation must cancel the captured queued ask job", queuedAskJob!!.isCancelled)
            send.join()
            var rollbackPhase = "waiting-for-original-thread"
            try {
                withTimeout(10_000) {
                    app.feedRepository.awaitFeedState { it.conversations.singleOrNull() == originalThread }
                    rollbackPhase = "original-thread-observed-waiting-for-cleanup-job"
                    rollbackJob?.join()
                    rollbackPhase = "cleanup-job-completed"
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val stateAtTimeout = kotlinx.coroutines.withTimeoutOrNull(1_000) { app.feedRepository.current() }
                val current = stateAtTimeout?.conversations?.singleOrNull { it.id == originalThread.id }
                throw AssertionError("existing-chat rollback timeout: phase=$rollbackPhase, cleanupJobCompleted=${rollbackJob?.isCompleted}, " +
                    "cleanupJobCancelled=${rollbackJob?.isCancelled}, epoch=$rollbackEpoch, selectionGeneration=$selectionGeneration, " +
                    "createdThreadId=$rollbackThreadId, " +
                    "originalId=${rollbackOriginal?.id}, originalTurns=${rollbackOriginal?.turns?.size}, appendedTurns=${rollbackAppended?.turns?.size}, " +
                    "currentId=${current?.id}, currentTurns=${current?.turns?.size}, currentEqualsOriginal=${current == originalThread}, " +
                    "currentEqualsAppended=${current == rollbackAppended}, askCancelled=${queuedAskJob.isCancelled}, " +
                    "requestsToday=${stateAtTimeout?.requestsToday ?: "unavailable"}, providerCalls=${app.askedQuestions.size}", timeout)
            }
            assertTrue(app.askedQuestions.isEmpty())
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun backupImportWithMatchingThreadIdCannotBeChangedByCancelledSuggestionRollback() = runBlocking {
        val item = app.feedRepository.current().items.single()
        val originalThread = AiConversation(id = "backup-collision-097", moduleId = module.id, itemId = item.id,
            turns = listOf(AiTurn(id = "original-user", role = "user", text = "The original local discussion.")))
        app.feedRepository.update { fixtureState(conversations = listOf(originalThread)) }
        withTimeout(10_000) { vm.ui.first { it.state?.conversations?.singleOrNull()?.id == originalThread.id } }
        vm.selectConversation(originalThread.id).join()
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.conversation(before, originalThread.id, "en"))
        val generated = requireNotNull(vm.generateSuggestions097(target, "en"))
        generated.job.join()
        val option = vm.suggestionOptions097(target, "en").first()
        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("backup-test-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val send = vm.sendSuggestion(target, option.id, "en")
            withTimeout(10_000) {
                app.feedRepository.awaitFeedState { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.questionText
                }
            }

            // Imported data deliberately reuses the exact chat ID but carries different history.
            val importedThread = originalThread.copy(title = "Reviewed backup conversation",
                turns = listOf(originalThread.turns.single(), AiTurn(id = "backup-user", role = "user",
                    text = "A separate reviewed backup note.")))
            val backup = fixtureState(conversations = listOf(importedThread))
            val file = File(app.cacheDir, "suggestion-collision-backup.json").apply { writeText(FeedJson.encodeToString(backup)) }
            vm.prepareFeedBackup(Uri.fromFile(file))
            withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importPreview?.kind == "backup" } }
            vm.confirmImport()
            withTimeout(10_000) { vm.ui.first { !it.importWorking && it.importSummary == "backup" } }
            assertEquals(importedThread, app.feedRepository.current().conversations.single())
            send.join()
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
        withTimeout(10_000) { app.feedRepository.awaitFeedState { it.conversations.singleOrNull()?.title == "Reviewed backup conversation" } }
        assertTrue("cancelled pre-dispatch request must never reach the provider", app.askedQuestions.isEmpty())
    }

    @Test fun lockFlipWhileSendWaitsForProviderSlotRejectsDispatchAndRestoresSelection() = runBlocking {
        val pin = "718293"
        val lock = app.appLock
        lock.foreground(true)
        if (!lock.state.value.enabled) assertTrue(lock.setup(pin.toCharArray()))
        if (lock.state.value.locked) assertTrue(lock.unlock(pin.toCharArray()))
        assertFalse(lock.state.value.locked)

        val releases = List(2) { CompletableDeferred<Unit>() }
        val entered = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index ->
            async(Dispatchers.IO) {
                AiActivityRegistry.run("lock-test-slot-$index", "technology", "answer") {
                    entered[index].complete(Unit)
                    releases[index].await()
                }
            }
        }
        try {
            entered.forEach { withTimeout(5_000) { it.await() } }
            val before = app.feedRepository.current()
            val item = before.items.single()
            val target = requireNotNull(SuggestionTargets097.item(before, item.id, "en"))
            val option = vm.suggestionOptions097(target, "en").first()
            val send = vm.sendSuggestion(target, option.id, "en")
            withTimeout(10_000) {
                app.feedRepository.awaitFeedState { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.questionText
                }
            }
            // The Compose lock effect is intentionally absent here: guarded engine validation
            // itself must see the synchronous lock state after the semaphore wait.
            lock.lock()
            assertTrue(lock.state.value.locked)
            releases.forEach { it.complete(Unit) }
            send.join()
            withTimeout(10_000) { app.feedRepository.awaitFeedState { it.conversations.isEmpty() } }
            assertFalse(vm.ui.value.suggestionWorking)
            assertEquals("suggestion_changed", vm.ui.value.error)
            assertTrue(app.askedQuestions.isEmpty())
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
            if (lock.state.value.enabled) {
                if (lock.state.value.locked) lock.unlock(pin.toCharArray())
                lock.disable(pin.toCharArray())
            }
        }
        assertTrue("unlocking must not replay the rejected question", app.askedQuestions.isEmpty())
    }

    @Test fun disconnectInvalidatesProviderBoundSuggestionsWithoutALocalFallback() = runBlocking {
        val beforeDisconnect = app.feedRepository.current()
        val itemId = beforeDisconnect.items.single().id
        val oldTarget = requireNotNull(SuggestionTargets097.item(beforeDisconnect, itemId, "en"))
        vm.disconnect().join()
        val state = app.feedRepository.current()
        val item = state.items.single()
        val target = requireNotNull(SuggestionTargets097.item(state, item.id, "en"))
        val sheet = vm.suggestionSheet097(target, "en")
        assertEquals("Provider identity change invalidates the previous AI set", SuggestionSheetStatus097.UNAVAILABLE, sheet.status)
        assertTrue("no local fallback is fabricated offline", sheet.options.isEmpty())
        assertEquals(SuggestionSheetStatus097.UNAVAILABLE, vm.suggestionSheet097(oldTarget, "en").status)
        assertTrue(vm.suggestionSheet097(oldTarget, "en").options.isEmpty())
        val requestsBefore = state.requestsToday
        assertNull(vm.ui.value.suggestionDraft)
        assertTrue(app.askedQuestions.isEmpty())
        assertEquals(requestsBefore, app.feedRepository.current().requestsToday)
    }

    private fun fixtureState(conversations: List<AiConversation> = emptyList(), itemId: String = "story-097"): FeedState {
        val raw = FeedItem(id = itemId, moduleId = module.id, kind = module.kind,
            title = "A local AI toolkit for a small technology pilot",
            summary = "The primary source describes a bounded pilot and its stated costs.",
            sourceName = "Example publisher", sourceUrl = "https://example.com/story-097",
            publishedAtMillis = 100L, fetchedAtMillis = System.currentTimeMillis())
        val base = FeedState(modules = listOf(module), context = listOf(note), items = listOf(raw),
            connection = AiConnection(mode = "direct", model = "gpt-6-luna"), name = "Fixture reader",
            region = "Tanzania", timezone = "Africa/Dar_es_Salaam", requestsToday = 2,
            dailyRequestLimit = 20, usageDate = FeedLogic.dateAt(System.currentTimeMillis(), "Africa/Dar_es_Salaam"),
            conversations = conversations)
        val cached = raw.copy(suggestedInteractions = interactions,
            suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(raw),
            suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(base, module, "en"),
            suggestedInteractionsGeneratedAtMillis = System.currentTimeMillis())
        val cachedState = base.copy(items = listOf(cached))
        val target = requireNotNull(SuggestionTargets097.item(cachedState, itemId, "en"))
        val set = AiSuggestionSet097("item", itemId, target.revision, interactions.map { it.prompt },
            cached.suggestedInteractionsGeneratedAtMillis)
        return cachedState.copy(suggestionSets097 = listOf(set))
    }

    private fun withPreparedSet(state: FeedState, target: SuggestionTargetSnapshot097,
        questions: List<String> = listOf(
            "What evidence in this item most strongly supports its main claim?",
            "Which assumption would change the conclusion if it proved false?",
            "What practical trade-off matters most before using this approach?",
            "How does this option compare with the closest alternative described here?",
            "What unresolved detail would be most useful to investigate next?",
        )): FeedState {
        val record = AiSuggestionSet097(target.kind.name.lowercase(java.util.Locale.ROOT), target.targetId, target.revision, questions, System.currentTimeMillis())
        return state.copy(suggestionSets097 = state.suggestionSets097.filterNot {
            AiSuggestionCache097.key(it.targetKind, it.targetId) == AiSuggestionCache097.key(record.targetKind, record.targetId)
        } + record)
    }

    private fun privateField(name: String): Any? = FeedViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm)
}

/** App lock controller with deterministic storage/clock for the post-semaphore lock race test. */
class Suggestion097TestApplication : ChatGptJourneyApplication() {
    override val appLock by lazy {
        AppLockController(SuggestionMemoryLockStorage(), elapsed = { 100_000L }, boot = { 7 }, deviceLocked = { false })
    }
}

private class SuggestionMemoryLockStorage : LockStorage {
    private var record: LockRecord? = null
    override fun enabled() = record != null
    override fun read() = record
    override fun write(record: LockRecord) { this.record = record }
    override fun disable() { record = null }
}
