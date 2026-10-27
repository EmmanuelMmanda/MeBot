package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MeBot 0.9.7 suggestion sheet actions are read-only until a specific Send or Edit action. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Suggestion097TestApplication::class)
class SuggestionActions097ViewModelTest {
    private lateinit var app: ChatGptJourneyApplication
    private lateinit var stores: ViewModelStore
    private lateinit var vm: FeedViewModel
    private lateinit var mainTestDispatcher: TestDispatcher

    private val module = FeedModule(id = "tech", kind = "technology", title = "Technology",
        query = "public technology pilot", sources = listOf(
            FeedSource("Example publisher", "https://example.com/feed.xml"),
        ))
    private val note = ContextEntry(id = "tech-note", text = "A confirmed interest in small technology pilots",
        scopes = setOf("technology"))
    private val interaction = AiSuggestedInteraction(label = "Check the source date",
        prompt = "Which publication date does this source show?")

    @Before fun setUp(): Unit = runBlocking {
        mainTestDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainTestDispatcher)
        app = ApplicationProvider.getApplicationContext()
        app.chatGptAuth.clearAll()
        app.chatGptAuth.restoreSavedAccount()
        app.feedSecrets.clear()
        app.feedSecrets.write("synthetic-suggestion-provider-key")
        app.feedRepository.erase()
        app.repository.update { AppState(settings = Settings(language = "en")) }
        app.asked.clear(); app.askedQuestions.clear(); app.askedContexts.clear(); app.checked.clear()
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
        val option = vm.suggestionOptions097(target, "en").single { it.source == SuggestionOptionSource097.CACHED_AI }
        assertEquals(interaction.prompt, option.displayText)
        assertTrue("opening the sheet only renders prepared options", app.askedQuestions.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.feedRepository.current().conversations.isEmpty())

        val firstTap = vm.sendSuggestion(target, option.id, "en")
        val duplicateTap = vm.sendSuggestion(target, option.id, "en")
        firstTap.join(); duplicateTap.join()
        val answered = withTimeout(10_000) {
            app.feedRepository.state.first { it.conversations.singleOrNull()?.turns?.lastOrNull()?.role == "assistant" }
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
        val option = vm.suggestionOptions097(target, "en").single { it.id == AiSuggestedInteractions095.suggestionId(item, interaction) }

        vm.editSuggestion(target, option.id, "en").join()

        val draft = requireNotNull(vm.ui.value.suggestionDraft)
        val thread = app.feedRepository.current().conversations.single { it.id == draft.conversationId }
        assertEquals(option.displayText, draft.text)
        assertEquals(item.id, thread.itemId)
        assertEquals(module.id, thread.moduleId)
        assertTrue(thread.turns.isEmpty())
        assertEquals(before.requestsToday, app.feedRepository.current().requestsToday)
        assertTrue(app.askedQuestions.isEmpty())
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
        val option = vm.suggestionOptions097(target, "en").first()
        assertTrue("item chat follow-up stays grounded in the actual item", option.displayText.contains(item.title))
        assertFalse("assistant boilerplate is not used as a pseudo-topic", option.displayText.contains("approved technical interests"))

        vm.sendSuggestion(target, option.id, "en").join()

        val answered = withTimeout(10_000) {
            app.feedRepository.state.first { it.conversations.singleOrNull()?.turns?.lastOrNull()?.role == "assistant" }
        }
        val saved = answered.conversations.single()
        assertEquals(thread.id, saved.id)
        assertEquals("Suggestions do not mark an unrelated pending file as used", listOf(alreadyUsed, unsent), saved.attachments)
        assertEquals(listOf("user", "assistant", "user", "assistant"), saved.turns.map { it.role })
        assertTrue(app.askedQuestions.contains(option.displayText))
    }

    @Test fun staleModuleOrConversationSnapshotFailsClosedWithoutProviderOrQuotaUse() = runBlocking {
        val before = app.feedRepository.current()
        val item = before.items.single()
        val moduleTarget = requireNotNull(SuggestionTargets097.module(before, module.id, "en"))
        val moduleOption = vm.suggestionOptions097(moduleTarget, "en").first()
        assertTrue("module fallback is grounded in the configured query", moduleOption.displayText.contains(module.query))
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
        app.feedRepository.update { restored }
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
        val rollbackTrace = java.util.concurrent.CopyOnWriteArrayList<SuggestionRollbackTrace097>()
        vm.suggestionRollbackTraceForTest = rollbackTrace::add
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
            val option = vm.suggestionOptions097(target, "en").single { it.id == AiSuggestedInteractions095.suggestionId(item, interaction) }
            val send = vm.sendSuggestion(target, option.id, "en")
            withTimeout(10_000) {
                app.feedRepository.state.first { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.displayText
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
            vm.cancelConversationOpen()
            assertTrue("navigation must cancel the captured queued ask job", queuedAskJob!!.isCancelled)
            send.join()
            // The request was conservatively reserved before entering the semaphore, but its
            // unsent user turn and action-created thread must not masquerade as a submitted chat.
            try {
                withTimeout(10_000) { app.feedRepository.state.first { it.conversations.isEmpty() } }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val beforeDrive = boundedFeedSnapshot()
                val current = beforeDrive?.conversations?.singleOrNull { it.id == rollbackThreadId }
                val beforeDriveTrace = rollbackTrace.takeLast(8).joinToString()
                mainTestDispatcher.scheduler.runCurrent()
                val afterDrive = boundedFeedSnapshot()
                throw AssertionError("rollback timeout: id=$rollbackThreadId, epoch=$rollbackEpoch, selectionGeneration=$selectionGeneration, " +
                    "originalId=${rollbackOriginal!!.id}, appendedId=${rollbackAppended!!.id}, originalTurns=${rollbackOriginal.turns.size}, " +
                    "appendedTurns=${rollbackAppended!!.turns.size}, currentId=${current?.id}, currentTurns=${current?.turns?.size}, " +
                    "currentMatchesAppended=${current == rollbackAppended}, askCancelled=${queuedAskJob.isCancelled}, " +
                    "requestsToday=${beforeDrive?.requestsToday ?: "unavailable"}, conversations=${beforeDrive?.conversations?.joinToString { "${it.id}:${it.turns.size}:${it.attachments.size}" } ?: "unavailable"}, " +
                    "traceBeforeRunCurrent=$beforeDriveTrace, stateBeforeRunCurrent=${summarizeFeedSnapshot(beforeDrive, rollbackThreadId)}, " +
                    "stateAfterRunCurrent=${summarizeFeedSnapshot(afterDrive, rollbackThreadId)}, traceAfterRunCurrent=${rollbackTrace.takeLast(8).joinToString()}", timeout)
            }
            println("new-thread rollback trace: ${rollbackTrace.takeLast(8).joinToString()}")
            assertTrue(app.askedQuestions.isEmpty())
        } finally {
            releases.forEach { it.complete(Unit) }
            blockers.forEach { it.await() }
        }
        assertTrue(app.askedQuestions.isEmpty())
    }

    @Test fun navigationWhileSendWaitsForProviderSlotRestoresExistingChatHistory() = runBlocking {
        val rollbackTrace = java.util.concurrent.CopyOnWriteArrayList<SuggestionRollbackTrace097>()
        vm.suggestionRollbackTraceForTest = rollbackTrace::add
        val item = app.feedRepository.current().items.single()
        val originalThread = AiConversation(id = "existing-chat-097", moduleId = module.id, itemId = item.id,
            turns = listOf(AiTurn(id = "old-user", role = "user", text = "Compare the source's stated costs.")))
        app.feedRepository.update { fixtureState(conversations = listOf(originalThread)) }
        withTimeout(10_000) { vm.ui.first { it.state?.conversations?.singleOrNull()?.id == originalThread.id } }
        vm.selectConversation(originalThread.id).join()
        val before = app.feedRepository.current()
        val target = requireNotNull(SuggestionTargets097.conversation(before, originalThread.id, "en"))
        val option = vm.suggestionOptions097(target, "en").first()
        assertFalse("chat follow-up must be distinct from the prior user turn", option.displayText == originalThread.turns.single().text)

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
                app.feedRepository.state.first { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.displayText
                }
            }
            val queuedAskJob = privateField("suggestionAskJob") as? kotlinx.coroutines.Job
            val rollbackOriginal = privateField("pendingSuggestionOriginalThread") as? AiConversation
            val rollbackAppended = privateField("pendingSuggestionAppendedThread") as? AiConversation
            val rollbackEpoch = privateField("suggestionRollbackEpoch")
            val selectionGeneration = privateField("conversationSelectionGeneration")
            assertNotNull("existing-chat send must own a queued ask job", queuedAskJob)
            assertEquals(originalThread.id, rollbackOriginal?.id)
            assertEquals(originalThread.id, rollbackAppended?.id)
            vm.cancelConversationOpen()
            assertTrue("navigation must cancel the captured existing-chat ask job", queuedAskJob!!.isCancelled)
            send.join()
            try {
                withTimeout(10_000) { app.feedRepository.state.first { it.conversations.singleOrNull() == originalThread } }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val beforeDrive = boundedFeedSnapshot()
                val current = beforeDrive?.conversations?.singleOrNull { it.id == originalThread.id }
                val beforeDriveTrace = rollbackTrace.takeLast(8).joinToString()
                mainTestDispatcher.scheduler.runCurrent()
                val afterDrive = boundedFeedSnapshot()
                throw AssertionError("existing-chat rollback timeout: epoch=$rollbackEpoch, selectionGeneration=$selectionGeneration, " +
                    "originalId=${rollbackOriginal?.id}, appendedId=${rollbackAppended?.id}, originalTurns=${rollbackOriginal?.turns?.size}, " +
                    "appendedTurns=${rollbackAppended?.turns?.size}, persistedOriginalId=${originalThread.id}, " +
                    "currentId=${current?.id}, currentTurns=${current?.turns?.size}, currentMatchesOriginal=${current == originalThread}, " +
                    "currentMatchesAppended=${current == rollbackAppended}, askCancelled=${queuedAskJob.isCancelled}, " +
                    "requestsToday=${beforeDrive?.requestsToday ?: "unavailable"}, conversations=${beforeDrive?.conversations?.joinToString { "${it.id}:${it.turns.size}:${it.attachments.size}" } ?: "unavailable"}, " +
                    "traceBeforeRunCurrent=$beforeDriveTrace, stateBeforeRunCurrent=${summarizeFeedSnapshot(beforeDrive, originalThread.id)}, " +
                    "stateAfterRunCurrent=${summarizeFeedSnapshot(afterDrive, originalThread.id)}, traceAfterRunCurrent=${rollbackTrace.takeLast(8).joinToString()}", timeout)
            }
            println("existing-chat rollback trace: ${rollbackTrace.takeLast(8).joinToString()}")
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
                app.feedRepository.state.first { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.displayText
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
        withTimeout(10_000) { app.feedRepository.state.first { it.conversations.singleOrNull()?.title == "Reviewed backup conversation" } }
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
                app.feedRepository.state.first { state ->
                    state.requestsToday == before.requestsToday + 1 &&
                        state.conversations.singleOrNull()?.turns?.lastOrNull()?.text == option.displayText
                }
            }
            // The Compose lock effect is intentionally absent here: guarded engine validation
            // itself must see the synchronous lock state after the semaphore wait.
            lock.lock()
            assertTrue(lock.state.value.locked)
            releases.forEach { it.complete(Unit) }
            send.join()
            withTimeout(10_000) { app.feedRepository.state.first { it.conversations.isEmpty() } }
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

    @Test fun offlineOptionsRemainLocalGroundedAndCanBeEditedWithoutAiClaim() = runBlocking {
        vm.disconnect().join()
        val state = app.feedRepository.current()
        val item = state.items.single()
        val target = requireNotNull(SuggestionTargets097.item(state, item.id, "en"))
        val option = vm.suggestionOptions097(target, "en").first()
        assertEquals(SuggestionOptionSource097.LOCAL, option.source)
        assertTrue(option.displayText.contains(item.title))
        val requestsBefore = state.requestsToday

        vm.editSuggestion(target, option.id, "en").join()

        assertEquals(option.displayText, vm.ui.value.suggestionDraft?.text)
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
        val cached = raw.copy(suggestedInteractions = listOf(interaction),
            suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(raw),
            suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(base, module, "en"),
            suggestedInteractionsGeneratedAtMillis = System.currentTimeMillis())
        return base.copy(items = listOf(cached))
    }

    private fun privateField(name: String): Any? = FeedViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm)

    private suspend fun boundedFeedSnapshot(): FeedState? =
        kotlinx.coroutines.withTimeoutOrNull(1_000) { app.feedRepository.current() }

    private fun summarizeFeedSnapshot(state: FeedState?, targetId: String?): String {
        if (state == null) return "unavailable"
        val target = state.conversations.singleOrNull { it.id == targetId }
        return "requests=${state.requestsToday}, target=${target?.id}:${target?.turns?.size}:${target?.attachments?.size}, " +
            "conversations=${state.conversations.take(ConversationLogic.maxConversations).joinToString { "${it.id}:${it.turns.size}:${it.attachments.size}" }}"
    }
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
