package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mebot.nguli.com.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic synthetic coverage for opening a story-scoped MeBot conversation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FeedTestApplication::class)
class ConversationOpen095ViewModelTest {
    private lateinit var app: FeedTestApplication
    private lateinit var stores: ViewModelStore
    private lateinit var vm: FeedViewModel

    private val module = FeedModule(id = "news", kind = "news")
    private val item = FeedItem(id = "toolkit", moduleId = "news", title = "A local AI toolkit",
        summary = "Synthetic source excerpt.", sourceName = "Example Research",
        sourceUrl = "https://example.com/toolkit", citations = listOf(FeedCitation("Example Research", "https://example.com/toolkit")))
    private val question = "How could I use this in a small project?"

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedSecrets.clear()
        app.answerCalls = 0
        app.feedRepository.erase()
        app.feedSecrets.write("synthetic-conversation-key")
        app.feedRepository.update { FeedState(onboardingComplete = true, personalSeedApplied = true,
            modules = listOf(module), items = listOf(item), connection = AiConnection(mode = "direct", model = "fixture-model"),
            timezone = "UTC", dailyRequestLimit = 20) }
        stores = ViewModelStore()
        vm = ViewModelProvider(stores, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { vm.ui.first { it.state != null && it.connected && !it.loading } }
        Unit
    }

    @After fun tearDown() {
        if (::stores.isInitialized) stores.clear()
        Dispatchers.resetMain()
    }

    private fun savedStateVm(handle: SavedStateHandle): FeedViewModel {
        if (::stores.isInitialized) stores.clear()
        stores = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass != FeedViewModel::class.java) error("Unexpected model: ${modelClass.name}")
                return FeedViewModel(app, handle) as T
            }
        }
        return ViewModelProvider(stores, factory)[FeedViewModel::class.java]
    }

    /** Exercise the same Bundle payload SavedStateHandle exposes to SavedStateRegistry. */
    private fun restoreHandle(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null)

    @Test fun rapidSendIsRejectedUntilTheCapturedItemThreadIsReady() = runBlocking {
        val entered = CompletableDeferred<String?>()
        val release = CompletableDeferred<Unit>()
        vm.beforeConversationOpen = { target -> entered.complete(target); release.await() }

        val opening = vm.openConversation(item.id)
        assertEquals(item.id, withTimeout(5_000) { entered.await() })

        vm.sendConversation(question)
        assertFalse("The ViewModel must reject rapid send independently of button disabled state", vm.ui.value.asking)
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertEquals(0, app.feedRepository.current().requestsToday)

        release.complete(Unit)
        opening.join()
        val ready = withTimeout(10_000) {
            vm.ui.first { state ->
                state.state?.conversations?.find { it.id == state.activeConversationId }?.itemId == item.id
            }
        }
        val selected = ready.state!!.conversations.single()
        assertEquals(item.id, selected.itemId)
        assertEquals(module.id, selected.moduleId)
        assertTrue(selected.turns.isEmpty())

        vm.sendConversation(question)
        val answered = withTimeout(10_000) {
            app.feedRepository.state.first { state ->
                state.conversations.singleOrNull()?.let { thread ->
                    thread.itemId == item.id && thread.turns.lastOrNull()?.role == "assistant"
                } == true
            }
        }
        val thread = answered.conversations.single()
        assertEquals(item.id, thread.itemId)
        assertEquals(module.id, thread.moduleId)
        assertEquals(listOf("user", "assistant"), thread.turns.map { it.role })
        assertEquals(question, thread.turns.first().text)
        assertEquals(app.answerText, thread.turns.last().text)
        assertEquals(1, answered.requestsToday)
        assertEquals(thread.id, vm.ui.value.activeConversationId)
    }

    @Test fun delayedOpenCannotOverrideAConversationSelectedWhileItWasPending() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.beforeConversationOpen = { entered.complete(Unit); release.await() }

        val opening = vm.openConversation(item.id)
        withTimeout(5_000) { entered.await() }

        val newer = AiConversation(id = "newer-selection", title = "Selected elsewhere", createdAtMillis = 1)
        app.feedRepository.update { it.copy(conversations = it.conversations + newer) }
        vm.selectConversation(newer.id).join()
        assertEquals(newer.id, vm.ui.value.activeConversationId)

        release.complete(Unit)
        withTimeout(5_000) { opening.join() }
        assertEquals("A late item open cannot replace the newer selection", newer.id, vm.ui.value.activeConversationId)
        assertEquals(listOf(newer), app.feedRepository.current().conversations)
    }

    @Test fun pendingItemOpenCannotCommitAfterResetOrLogout() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.beforeConversationOpen = { entered.complete(Unit); release.await() }

        val opening = vm.openConversation(item.id)
        withTimeout(5_000) { entered.await() }
        vm.reset().join()
        release.complete(Unit)
        opening.join()
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertNull(vm.ui.value.activeConversationId)

        // A fresh open invalidated by sign-out cannot restore selection after credentials are gone.
        app.feedSecrets.write("synthetic-conversation-key")
        app.feedRepository.update { it.copy(onboardingComplete = true, personalSeedApplied = true,
            modules = listOf(module), items = listOf(item), conversations = emptyList(),
            connection = AiConnection(mode = "direct", model = "fixture-model")) }
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        vm.beforeConversationOpen = { secondEntered.complete(Unit); secondRelease.await() }
        val nextOpen = vm.openConversation(item.id)
        withTimeout(5_000) { secondEntered.await() }
        vm.disconnect().join()
        secondRelease.complete(Unit)
        nextOpen.join()
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertNull(vm.ui.value.activeConversationId)
    }

    @Test fun returningToWorkspaceResumesTheSameCancelledItemTarget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var openCount = 0
        vm.beforeConversationOpen = {
            openCount++
            if (openCount == 1) { entered.complete(Unit); release.await() }
        }

        val firstOpen = vm.openConversation(item.id)
        withTimeout(5_000) { entered.await() }
        vm.cancelConversationOpen() // leaving the AI page invalidates this completion
        release.complete(Unit)
        firstOpen.join()
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertFalse(vm.ui.value.conversationReady)

        // Re-entering the AI page resumes the captured item intent; it does not guess General.
        vm.beforeConversationOpen = {}
        vm.resumeConversationOpenIfNeeded()
        val ready = withTimeout(10_000) {
            vm.ui.first { state ->
                state.conversationReady && !state.conversationOpening &&
                    state.state?.conversations?.find { it.id == state.activeConversationId }?.itemId == item.id
            }
        }
        assertEquals(item.id, ready.state!!.conversations.single().itemId)
        assertEquals(1, app.feedRepository.current().conversations.size)
    }

    @Test fun lockDuringPromptedItemOpenCancelsAutoSendAndUnlockResumesScopeOnly() = runBlocking {
        val entered = CompletableDeferred<String?>()
        val release = CompletableDeferred<Unit>()
        var openCount = 0
        vm.beforeConversationOpen = { target ->
            openCount++
            if (openCount == 1) { entered.complete(target); release.await() }
        }

        val opening = vm.openConversation(item.id, question)
        assertEquals(item.id, withTimeout(5_000) { entered.await() })
        assertTrue(vm.ui.value.conversationOpening)

        // Mirrors MainActivity's App Lock reaction while the pre-open seam is suspended.
        vm.cancelConversationOpen()
        vm.clearAnswer()
        release.complete(Unit)
        opening.join()

        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertEquals(0, app.feedRepository.current().requestsToday)
        assertEquals(0, app.answerCalls)
        assertFalse(vm.ui.value.conversationReady)
        assertFalse(vm.ui.value.asking)
        assertNull(vm.ui.value.question)
        assertNull(vm.ui.value.answer)

        // Unlocking resumes only the captured story scope; it does not replay the prompt.
        vm.beforeConversationOpen = {}
        vm.resumeConversationOpenIfNeeded()
        val ready = withTimeout(10_000) {
            vm.ui.first { state ->
                state.conversationReady && state.state?.conversations
                    ?.find { it.id == state.activeConversationId }?.itemId == item.id
            }
        }
        val thread = ready.state!!.conversations.single()
        assertEquals(module.id, thread.moduleId)
        assertEquals(item.id, thread.itemId)
        assertTrue(thread.turns.isEmpty())
        assertEquals(0, ready.state.requestsToday)
        assertEquals(0, app.answerCalls)
        assertNull(vm.ui.value.question)
        assertFalse(vm.ui.value.asking)
    }

    @Test fun missingNonNullTargetFailsClosedInsteadOfCreatingGeneralThread() = runBlocking {
        vm.openConversation("missing-item").join()

        assertNull(vm.ui.value.activeConversationId)
        assertFalse(vm.ui.value.conversationOpening)
        assertFalse(vm.ui.value.conversationReady)
        assertEquals("invalid_input", vm.ui.value.error)

        vm.sendConversation(question)
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertEquals(0, app.feedRepository.current().requestsToday)
    }

    @Test fun pendingItemIntentSurvivesSavedStateRestoreWithoutOpeningGeneralChat() = runBlocking {
        val originalHandle = SavedStateHandle()
        vm = savedStateVm(originalHandle)
        withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.beforeConversationOpen = { entered.complete(Unit); release.await() }

        vm.openConversation(item.id)
        withTimeout(5_000) { entered.await() }
        assertFalse(vm.ui.value.conversationReady)
        val stateBundle = originalHandle.savedStateProvider().saveState()
        assertTrue(stateBundle.keySet().isNotEmpty())
        assertEquals(FeedViewModel.SELECTION_PENDING_OPEN, originalHandle.get<String>(FeedViewModel.KEY_SELECTION_MODE))

        stores.clear() // Simulate process loss while the scoped open is suspended.
        release.complete(Unit)
        val restoredHandle = restoreHandle(originalHandle)
        assertEquals(item.id, restoredHandle.get<String>(FeedViewModel.KEY_SELECTION_ITEM_ID))
        vm = savedStateVm(restoredHandle)
        withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading } }
        assertFalse(vm.ui.value.conversationReady)
        assertNull(vm.ui.value.activeConversationId)

        vm.beforeConversationOpen = {}
        vm.resumeConversationOpenIfNeeded()
        val ready = withTimeout(10_000) {
            vm.ui.first { it.conversationReady && it.state?.conversations?.find { c -> c.id == it.activeConversationId }?.itemId == item.id }
        }
        assertEquals(listOf(item.id), ready.state!!.conversations.map { it.itemId })
        vm.sendConversation(question)
        val answered = withTimeout(10_000) {
            app.feedRepository.state.first { state -> state.conversations.singleOrNull()?.turns?.lastOrNull()?.role == "assistant" }
        }
        assertEquals(item.id, answered.conversations.single().itemId)
        assertEquals(listOf("user", "assistant"), answered.conversations.single().turns.map { it.role })
    }

    @Test fun activeItemThreadIdentitySurvivesSavedStateRestore() = runBlocking {
        val originalHandle = SavedStateHandle()
        vm = savedStateVm(originalHandle)
        withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading } }
        vm.openConversation(item.id).join()
        val originalId = vm.ui.value.activeConversationId
        assertNotNull(originalId)
        assertEquals("active", originalHandle.get<String>(FeedViewModel.KEY_SELECTION_MODE))
        assertEquals(item.id, originalHandle.get<String>(FeedViewModel.KEY_SELECTION_ITEM_ID))

        val restoredHandle = restoreHandle(originalHandle)
        stores.clear()
        vm = savedStateVm(restoredHandle)
        val restored = withTimeout(10_000) {
            vm.ui.first { it.conversationReady && it.activeConversationId == originalId }
        }
        assertEquals(item.id, restored.state!!.conversations.single().itemId)
        vm.sendConversation(question)
        val answered = withTimeout(10_000) {
            app.feedRepository.state.first { state -> state.conversations.single().turns.lastOrNull()?.role == "assistant" }
        }
        assertEquals(originalId, answered.conversations.single().id)
        assertEquals(item.id, answered.conversations.single().itemId)
        assertFalse(answered.conversations.any { it.itemId == null })
    }

    @Test fun deletingTheSelectedItemThreadFailsClosedUntilExplicitNewSelection() = runBlocking {
        val originalHandle = SavedStateHandle()
        vm = savedStateVm(originalHandle)
        withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading } }
        vm.openConversation(item.id).join()
        val selectedId = vm.ui.value.activeConversationId!!
        vm.deleteConversation(selectedId).join()

        assertNull(vm.ui.value.activeConversationId)
        assertFalse(vm.ui.value.conversationReady)
        assertEquals(FeedViewModel.SELECTION_BLOCKED, originalHandle.get<String>(FeedViewModel.KEY_SELECTION_MODE))
        vm.sendConversation(question)
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertEquals(0, app.feedRepository.current().requestsToday)

        val restoredHandle = restoreHandle(originalHandle)
        vm = savedStateVm(restoredHandle)
        withTimeout(10_000) { vm.ui.first { it.state != null && !it.loading } }
        assertFalse(vm.ui.value.conversationReady)
        vm.sendConversation(question)
        assertTrue(app.feedRepository.current().conversations.isEmpty())
        assertEquals(0, app.feedRepository.current().requestsToday)

        vm.newConversation().join()
        assertTrue(vm.ui.value.conversationReady)
        assertNotNull(vm.ui.value.activeConversationId)
    }
}
