package mebot.nguli.com.jobs

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mebot.nguli.com.core.*
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.feed.*
import mebot.nguli.com.ui.ChatGptJourneyApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = JobsAssistantFlowApplication::class)
class JobsAssistantFlowTest {
    private lateinit var app: JobsAssistantFlowApplication
    private lateinit var vm: FeedViewModel
    private val models = ViewModelStore()
    private val job = JobPosting(id = "fixture:42", title = "Engineer", company = "Example",
        description = "Build integrations.", url = "https://example.com/jobs/42", source = "Example")

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedRepository.erase()
        app.jobsRepository.erase()
        app.feedSecrets.write("synthetic-jobs-key")
        app.repository.update { AppState(settings = Settings(language = "en")) }
        app.feedRepository.update { FeedState(connection = AiConnection(mode = "direct"),
            context = listOf(ContextEntry(id = "unrelated", text = "Unselected private fact", scopes = setOf("technology")))) }
        app.requests.clear(); app.effectiveContexts.clear(); app.streamGate = null; app.streamCancelled = CompletableDeferred()
        vm = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { vm.ui.first { it.state != null && it.connected && !it.loading } }
    }

    @After fun close() { models.clear(); if (::app.isInitialized) app.alarms.clearAll(); Dispatchers.resetMain() }

    private suspend fun answer(request: JobsAssistantRequest): AiConversation {
        val id = JobsAssistant.conversation(job, request.sharedContext).id
        val previous = app.feedRepository.current().conversations.find { it.id == id }?.turns?.size ?: 0
        val reviewed = if (request.sharedContext.isNotEmpty() && request.contextRevision.isEmpty())
            request.copy(contextRevision = currentRevision()) else request
        vm.openJobsConversation(job, reviewed).join()
        val saved = withTimeout(10_000) { app.feedRepository.state.first { state ->
            state.conversations.any { it.id == id && it.turns.size > previous && it.turns.lastOrNull()?.role == "assistant" }
        } }
        withTimeout(10_000) { vm.ui.first { !it.asking && it.activeConversationId == id } }
        return saved.conversations.single { it.id == id }
    }

    private suspend fun currentRevision(): String = JobsContextSuggestions.build(
        app.feedRepository.current(), app.jobsRepository.current().preferences).revision

    @Test fun nonemptyContextWithoutRevisionOrStaleEditingScopeCannotSend(): Unit = runBlocking {
        val before = app.feedRepository.current()
        vm.openJobsConversation(job, JobsAssistantRequest(sharedContext = "Unreviewed selection")).join()
        assertEquals("context_changed", vm.ui.value.error)
        vm.openJobsConversation(job, JobsAssistantRequest(), expectedJobsScope = "obsolete-editor").join()
        assertEquals("context_changed", vm.ui.value.error)
        assertTrue(app.requests.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertEquals(before.conversations, app.feedRepository.current().conversations)
    }

    @Test fun explicitContextIsExactAndDifferentSelectionsNeverReuseOldTurns(): Unit = runBlocking {
        val chosen = "  Reviewed candidate fact\nUzoefu wa kazi.\n"
        val first = answer(JobsAssistantRequest(sharedContext = chosen, question = "Explain requirements"))
        val second = answer(JobsAssistantRequest(sharedContext = "Another reviewed fact", question = "Explain location restrictions"))
        assertNotEquals(first.id, second.id)
        assertEquals(2, app.requests.size)
        assertEquals(chosen, app.requests.first().attachments.single { it.name == "candidate-context.txt" }.text)
        assertTrue(app.requests.last().turns.none { it.text.contains("Explain requirements") })
        assertTrue(app.requests.last().attachments.none { it.text.contains(chosen) })
        assertTrue(app.requests.all { it.contextScope == "jobs" && it.moduleId == null && it.itemId == null })
        assertTrue("Jobs cannot implicitly select unrelated confirmed notes", app.effectiveContexts.all { it.isEmpty() })
        assertFalse(second.attachments.any { it.text.contains("Unselected private fact") })
    }

    @Test fun cancellingAStreamingJobsAnswerCannotCommitLateCompletion(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        app.streamGate = gate
        vm.openJobsConversation(job, JobsAssistantRequest(question = "Explain this role")).join()
        withTimeout(10_000) { vm.ui.first { it.asking && it.answer == "Synthetic" } }
        vm.clearAnswer()
        withTimeout(10_000) { app.streamCancelled.await() }
        gate.complete(Unit)
        assertFalse(vm.ui.value.asking)
        assertTrue(app.feedRepository.current().conversations.single().turns.none { it.role == "assistant" })
        assertEquals(1, app.requests.size)
    }

    @Test fun staleReviewedRevisionFailsBeforeThreadQuotaOrProviderMutation(): Unit = runBlocking {
        val before = app.feedRepository.current()
        vm.openJobsConversation(job, JobsAssistantRequest(sharedContext = "Reviewed fact", contextRevision = "stale-revision")).join()
        assertEquals("context_changed", vm.ui.value.error)
        assertTrue(app.requests.isEmpty())
        val after = app.feedRepository.current()
        assertEquals(before.requestsToday, after.requestsToday)
        assertEquals(before.conversations, after.conversations)
    }

    @Test fun revokedContextWhileQueuedForProviderSlotCannotBeTransmitted(): Unit = runBlocking {
        val note = ContextEntry(id = "chosen-jobs", text = "Skills: Kotlin", scopes = setOf("opportunities"))
        app.feedRepository.update { it.copy(context = it.context + note) }
        val reviewedRevision = currentRevision()
        val entered = List(2) { CompletableDeferred<Unit>() }
        val releases = List(2) { CompletableDeferred<Unit>() }
        val blockers = releases.indices.map { index -> async(Dispatchers.IO) {
            AiActivityRegistry.run("jobs-context-race-$index", "jobs", "answer") {
                entered[index].complete(Unit)
                releases[index].await()
            }
        } }
        try {
            entered.forEach { withTimeout(10_000) { it.await() } }
            val before = app.feedRepository.current().requestsToday
            vm.openJobsConversation(job, JobsAssistantRequest(sharedContext = note.text,
                contextRevision = reviewedRevision, question = "Review this role")).join()
            withTimeout(10_000) { app.feedRepository.state.first { it.requestsToday == before + 1 } }
            assertTrue("The request must still be behind the occupied provider slots", app.requests.isEmpty())
            app.feedRepository.update { it.copy(context = it.context.filterNot { entry -> entry.id == note.id }) }
            assertNotEquals(reviewedRevision, currentRevision())
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
            withTimeout(10_000) { vm.ui.first { !it.asking && it.error == "context_changed" } }
            assertTrue("Revoked candidate context cannot cross the provider boundary", app.requests.isEmpty())
            assertTrue(app.feedRepository.current().conversations.single().turns.none { it.role == "assistant" })
            assertEquals("No retry or second reservation is allowed", before + 1, app.feedRepository.current().requestsToday)
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
    }

    @Test fun sameContextReopenPreservesChosenFileAndExistingHistory(): Unit = runBlocking {
        val request = JobsAssistantRequest(sharedContext = "Reviewed fact", question = "First question")
        val first = answer(request)
        val file = AiAttachment(id = "user-file", name = "cv.txt", text = "Explicitly chosen CV excerpt")
        app.feedRepository.update { state -> state.copy(conversations = state.conversations.map {
            if (it.id == first.id) it.copy(attachments = it.attachments + file) else it
        }) }
        val second = answer(request.copy(question = "Second question"))
        assertEquals(first.id, second.id)
        assertEquals(first.turns, second.turns.take(first.turns.size))
        assertEquals(file.text, app.requests.last().attachments.single { it.id == file.id }.text)
        assertEquals(3, second.attachments.size)
    }

    @Test fun capacityConflictRejectsWithoutDiscardingUserFilesOrUsingQuota(): Unit = runBlocking {
        val shared = "Reviewed fact"
        val candidate = JobsAssistant.conversation(job, shared)
        val existing = candidate.copy(attachments = listOf(candidate.attachments.first(),
            AiAttachment(id = "file-a", name = "a.txt", text = "Chosen A"),
            AiAttachment(id = "file-b", name = "b.txt", text = "Chosen B")))
        app.feedRepository.update { it.copy(conversations = listOf(existing)) }
        val before = app.feedRepository.current()
        vm.openJobsConversation(job, JobsAssistantRequest(sharedContext = shared, contextRevision = currentRevision())).join()
        assertEquals("invalid_input", vm.ui.value.error)
        assertTrue(app.requests.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertEquals(existing, app.feedRepository.current().conversations.single())
    }
}

/** Captures only synthetic requests; the production conversation engine still streams and commits. */
class JobsAssistantFlowApplication : ChatGptJourneyApplication() {
    val requests = CopyOnWriteArrayList<AiConversation>()
    val effectiveContexts = CopyOnWriteArrayList<List<ContextEntry>>()
    @Volatile var streamGate: CompletableDeferred<Unit>? = null
    var streamCancelled = CompletableDeferred<Unit>()
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = emptyList<FeedItem>()
        }, object : AiProvider {
            override suspend fun check(connection: AiConnection, secret: String) = Unit
            override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) = AiAnswer("Synthetic answer")
            override suspend fun streamConversation(state: FeedState, item: FeedItem?, conversation: AiConversation,
                question: String, secret: String, language: String, onText: (String) -> Unit): AiAnswer {
                requests += conversation
                effectiveContexts += ConversationLogic.context(state, conversation)
                onText("Synthetic")
                try { streamGate?.await() } catch (cancelled: CancellationException) {
                    streamCancelled.complete(Unit)
                    throw cancelled
                }
                onText("Synthetic answer")
                return AiAnswer("Synthetic answer")
            }
        })
    }
}
