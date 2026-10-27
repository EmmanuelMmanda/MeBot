package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.SuggestionOptionSource097
import mebot.nguli.com.feed.SuggestionTargets097
import mebot.nguli.com.feed.FeedTestApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Complete native UI journeys with the production repository, engine, and
 * ViewModel. Only the external transports and keystore are test doubles.
 * News, identity, and credentials below are synthetic test fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotFeedJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedTestApplication

    @Before fun resetInstallation() {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.erase()
            app.feedRepository.erase()
        }
        app.alarms.clearAll()
        app.sourceItems = emptyList()
        app.sourceFailure = null
        app.answerText = "This test update relates to your approved technical interests."
        waitForTag("onboarding-screen")
    }

    @Test fun firstVisitRefreshSaveReopenAndOfflineRetryKeepTheSameStory() {
        app.sourceItems = stories()
        screenshot("20-mebot-feed-onboarding")
        start()
        waitForState { it.items.size == 3 && it.lastRefreshMillis > 0 }
        assertEquals("", state().name)
        assertTrue(state().context.isEmpty())
        assertFalse(state().notifications)
        runBlocking { app.feedRepository.update { it.copy(name = "Alex") } }
        waitForText(text(R.string.feed_greeting, "Alex"))
        screenshot("21-mebot-personal-feed")

        compose.onNodeWithTag("save-toolkit").performScrollTo().performClick()
        waitForState { "toolkit" in it.savedIds }
        tab("saved")
        waitForText(text(R.string.library_title))
        compose.onNodeWithTag("item-toolkit").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.library_title))
        assertEquals(setOf("toolkit"), state().savedIds)
        screenshot("22-mebot-saved")
        compose.onNodeWithTag("item-toolkit").performClick()
        waitForTag("detail-save")
        compose.onNodeWithText(stories().first().summary).assertExists()
        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-citation-0").performScrollTo().assertIsDisplayed()
        while (shadowOf(compose.activity).nextStartedActivity != null) { }
        compose.onNodeWithTag("detail-citation-0").performClick()
        assertEquals("https://example.com/toolkit", shadowOf(compose.activity).nextStartedActivity.dataString)
        compose.onNodeWithTag("detail-save").performScrollTo()
        screenshot("23-mebot-update-detail")
        back()
        waitForText(text(R.string.library_title))

        tab("feed")
        app.sourceFailure = "offline"
        compose.onNodeWithTag("feed-refresh").performScrollTo().performClick()
        waitForText(text(R.string.feed_error_offline))
        assertEquals(3, state().items.size)
        assertEquals(setOf("toolkit"), state().savedIds)
        // Reading history can rank this saved story below the lazy viewport.
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-toolkit"))
        compose.onNodeWithTag("item-toolkit").assertIsDisplayed()
        app.sourceFailure = null
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-refresh"))
        compose.onNodeWithTag("feed-refresh").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.feed_error_offline)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals(3, state().items.map { it.id }.distinct().size)
        assertEquals(setOf("toolkit"), state().savedIds)
    }

    @Test fun moduleDraftSurvivesRotationAndPausingItPreservesSavedContent() {
        val installation = app
        val repository = app.feedRepository
        val observations = mutableListOf<String>()
        fun observe(label: String, value: FeedState = state()) {
            assertSame("Application changed at $label", installation, app)
            assertSame("Repository changed at $label", repository, app.feedRepository)
            val observation = "$label: modules=${value.modules.map { it.id to it.enabled }} saved=${value.savedIds} " +
                "activity=${System.identityHashCode(compose.activity)} app=${System.identityHashCode(app)} repo=${System.identityHashCode(app.feedRepository)}"
            if (observations.lastOrNull() != observation) {
                if (observations.size == 32) observations.removeAt(0)
                observations += observation
            }
        }
        start()
        observe("after-onboarding")
        tab("modules")
        screenshot("24-mebot-modules")
        // The first empty refresh can cover the bottom edge where scrollTo places Add.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text(R.string.feed_no_results)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("module-add").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        waitForTag("module-name")
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        waitForText(text(R.string.feed_module_name_required))
        assertEquals(ModuleCatalog.defaults.size, state().modules.size)
        compose.onNodeWithTag("module-name").performScrollTo().performTextInput("Research watch")
        compose.onNodeWithTag("module-topic").performScrollTo().performTextInput("Open source tools for local AI")
        compose.activityRule.scenario.recreate()
        waitForTag("module-name")
        observe("after-editor-recreation")
        compose.onNodeWithTag("module-name").assertTextContains("Research watch")
        compose.onNodeWithTag("module-topic").assertTextContains("Open source tools for local AI")
        screenshot("25-mebot-add-watch")
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        waitForState { it.modules.any { module -> module.title == "Research watch" } }
        val module = state().modules.single { it.title == "Research watch" }
        observe("saved-module-${module.id}")
        waitForTag("module-${module.id}")
        val article = stories().first().copy(id = "watch-result", moduleId = module.id, kind = "watch")
        val fixture = runBlocking { app.feedRepository.update { it.copy(items = listOf(article), savedIds = setOf(article.id)) } }
        observe("fixture-update-return", fixture)
        observe("fixture-immediate-read")
        observe("before-toggle")
        compose.onNodeWithTag("module-toggle-${module.id}").performScrollTo().performClick()
        fun diagnosed(failure: Throwable): AssertionError {
            val persistedIds = runCatching {
                val raw = File(installation.filesDir, "mebot/feed.json").readText()
                mebot.nguli.com.feed.FeedJson.decodeFromString<FeedState>(raw).modules.map { it.id to it.enabled }
            }.fold({ it.toString() }, { "unreadable:${it.javaClass.simpleName}" })
            val ui = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java].ui.value
            return AssertionError("Module ${module.id} absent or pause failed; persisted modules=$persistedIds; " +
                "VM modules=${ui.state?.modules?.map { it.id to it.enabled }} error=${ui.error} saving=${ui.moduleSavingId}\n" +
                observations.joinToString("\n"), failure)
        }
        try {
            waitForState { current ->
                observe("toggle-poll", current)
                current.modules.single { entry -> entry.id == module.id }.enabled.not()
            }
        } catch (failure: Exception) {
            throw diagnosed(failure)
        } catch (failure: AssertionError) {
            throw diagnosed(failure)
        }
        compose.activityRule.scenario.recreate()
        waitForTag("module-toggle-${module.id}")
        compose.onNodeWithTag("module-toggle-${module.id}").performScrollTo().assertIsOff()
        tab("feed")
        compose.onNodeWithTag("item-watch-result").assertDoesNotExist()
        tab("saved")
        compose.onNodeWithTag("item-watch-result").performScrollTo().assertIsDisplayed()
        assertEquals(setOf(article.id), state().savedIds)
        println("Module pause diagnostic checkpoints:\n" + observations.joinToString("\n"))
    }

    @Test fun editingContextPreservesExistingScopesAndChangesTheReopenedGreeting() {
        start()
        val scoped = ContextEntry(id = "scoped-interest", text = "For business updates, focus on stock management and customer service in Tanzania.", scopes = setOf("business"), origin = "imported", updatedAtMillis = 1)
        val changedText = "I am a Tanzanian entrepreneur running a neighbourhood shop, a food business and a delivery service. I want practical ideas and concise explanations with local examples."
        runBlocking { app.feedRepository.update { it.copy(context = listOf(scoped), items = listOf(stories().first().copy(why = "An old explanation", relevance = 90))) } }
        tab("you")
        clickText(R.string.preferences_title_083)
        waitForTag("context-name")
        compose.onNodeWithTag("context-name").performScrollTo().performTextInput("Alex")
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement(changedText)
        compose.onNodeWithTag("context-edit-scoped-interest").assertTextContains(scoped.text)
        compose.activityRule.scenario.recreate()
        waitForTag("context-name")
        compose.onNodeWithTag("context-name").assertTextContains("Alex")
        compose.onNodeWithTag("context-edit").assertTextContains(changedText)
        compose.onNodeWithTag("context-edit-scoped-interest").assertTextContains(scoped.text)
        compose.onNode(hasText(text(R.string.preferences_title_083)) and !hasSetTextAction()).performScrollTo()
        screenshot("28-mebot-context")
        screenshot("v085-personal-page")
        compose.onNodeWithTag("context-global-use").performScrollTo().assertExists()
        compose.onNodeWithTag("context-scopes-${PersonalProfile085.PROFILE_ID}").assertDoesNotExist()
        compose.onNodeWithTag("context-scopes-scoped-interest").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-edit-scoped-interest").assertIsDisplayed()
        screenshot("v085-context-notes")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        waitForState { it.name == "Alex" && it.context.size == 2 }
        waitForTag("nav-you")
        assertEquals(scoped, state().context.single { it.id == scoped.id })
        val profile = state().context.single { it.id == PersonalProfile085.PROFILE_ID }
        assertEquals(changedText, profile.text)
        assertTrue(profile.global)
        assertTrue(profile.scopes.isEmpty())
        assertTrue(state().items.single().why.isEmpty())
        assertEquals(0, state().items.single().relevance)
        compose.activityRule.scenario.recreate()
        waitForTag("nav-feed")
        tab("feed")
        waitForText(text(R.string.feed_greeting, "Alex"))
    }

    @Test fun connectionFailureCanBeCorrectedAndGroundedAskSurvivesRotation() {
        app.sourceItems = stories()
        start()
        waitForState { it.items.size == 3 }
        tab("you")
        clickText(R.string.feed_connection)
        waitForTag("chatgpt-sign-in")
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        screenshot("34-mebot-chatgpt-native-connection")
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        waitForTag("connection-key")
        compose.onNodeWithTag("connection-key").performTextInput("invalid-test-credential")
        compose.onNodeWithTag("connection-connect").performScrollTo().performClick()
        waitForText(text(R.string.feed_error_unauthorized))
        assertNull(app.feedSecrets.read())
        compose.onNodeWithTag("connection-key").performScrollTo().performTextReplacement("valid-test-credential")
        compose.onNodeWithTag("connection-connect").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.feedSecrets.read() == "valid-test-credential" }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("connection-key").fetchSemanticsNodes().isEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()
        waitForText(text(R.string.feed_you_title))
        tab("feed")
        waitForTag("feed-screen")
        waitForState { it.lastAiRefreshMillis > 0 }
        val requestsBeforeQuestion = state().requestsToday
        compose.onNodeWithTag("item-toolkit").performScrollTo().performClick()
        waitForState { it.briefs.any { brief -> brief.itemId == "toolkit" } }
        compose.onNodeWithTag("detail-brief-answer").performScrollTo().assertTextEquals(app.answerText)
        assertEquals(requestsBeforeQuestion + 1, state().requestsToday)
        val feedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java]
        assertSame("Saved-state factory keeps the Activity's production Application", app, feedViewModel.getApplication())
        compose.onNodeWithTag("detail-ask").performScrollTo().performClick()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-send").assertIsNotEnabled()
        try {
            compose.waitUntil(15_000) {
                // waitUntil advances the Compose test clock but does not drain Robolectric's paused
                // Android main looper; the open coroutine returns from DataStore on that looper.
                compose.waitForIdle()
                val latest = feedViewModel.ui.value
                !latest.conversationOpening && latest.conversationReady && latest.state?.conversations
                    ?.find { it.id == latest.activeConversationId }?.itemId == "toolkit"
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val latest = feedViewModel.ui.value
            val persistedThreads = runBlocking { app.feedRepository.current().conversations.map { thread ->
                "${thread.id}:${thread.moduleId}/${thread.itemId}:${thread.turns.map { it.role }}"
            } }
            val renderedStory = compose.onAllNodesWithText(stories().first().title).fetchSemanticsNodes().isNotEmpty()
            val renderedOpening = compose.onAllNodesWithTag("workspace-opening").fetchSemanticsNodes().isNotEmpty()
            throw AssertionError("Scoped-open readiness timed out; active=${latest.activeConversationId}, " +
                "opening=${latest.conversationOpening}, ready=${latest.conversationReady}, error=${latest.error}, " +
                "renderedStory=${renderedStory}, renderedOpening=${renderedOpening}, persistedThreads=${persistedThreads}", timeout)
        }
        val selectedThreadId = feedViewModel.ui.value.activeConversationId
        compose.onNodeWithTag("workspace-question").performTextInput("How could I use this in a small project?")
        compose.activityRule.scenario.recreate()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-question").assertTextContains("How could I use this in a small project?")
        val restoredFeedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java]
        assertSame("Activity recreation must retain Application identity", app, restoredFeedViewModel.getApplication())
        compose.waitUntil(15_000) {
            val latest = restoredFeedViewModel.ui.value
            latest.activeConversationId == selectedThreadId && latest.conversationReady && !latest.conversationOpening
        }
        compose.onNodeWithTag("workspace-opening").assertDoesNotExist()
        compose.onNodeWithTag("workspace-send").assertIsEnabled().performClick()
        waitForState { it.conversations.any { thread -> thread.itemId == "toolkit" && thread.turns.lastOrNull()?.role == "assistant" } }
        val conversation = state().conversations.single { it.itemId == "toolkit" }
        assertEquals("The same selected thread survives a normal Activity recreation", selectedThreadId, conversation.id)
        assertEquals(1, state().conversations.size)
        assertTrue(state().conversations.none { it.itemId == null })
        assertEquals("news", conversation.moduleId)
        assertEquals(listOf("user", "assistant"), conversation.turns.map { it.role })
        assertEquals("How could I use this in a small project?", conversation.turns.first().text)
        assertEquals(app.answerText, conversation.turns.last().text)
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(app.answerText))
        compose.onNodeWithText(app.answerText).assertIsDisplayed()
        val sourceTag = "turn-source-${conversation.turns.last().id}-0"
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag(sourceTag))
        compose.onNodeWithTag(sourceTag).assertIsDisplayed()
        while (shadowOf(compose.activity).nextStartedActivity != null) { }
        compose.onNodeWithTag(sourceTag).performClick()
        assertEquals("https://example.com/toolkit", shadowOf(compose.activity).nextStartedActivity.dataString)
        assertEquals(requestsBeforeQuestion + 2, state().requestsToday)
        screenshot("26-mebot-ask")
        compose.activityRule.scenario.recreate()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(app.answerText))
        compose.onNodeWithText(app.answerText).assertIsDisplayed()
        assertEquals(conversation, state().conversations.single { it.id == conversation.id })
        assertEquals(requestsBeforeQuestion + 2, state().requestsToday)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun englishFullShellSuggestionSheetsKeepSendAndEditExplicit() =
        runSuggestionJourney("en", "097-full-shell-en")

    @Test
    @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliFullShellSuggestionSheetsKeepSendAndEditExplicit() =
        runSuggestionJourney("sw", "097-full-shell-sw")

    @Test fun disconnectedTechnologySuggestionsUseTheRealLocalOptionsInEnglish() =
        runLocalTechnologySuggestionCapture("en", "097-local-technology-en")

    @Test
    @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun disconnectedTechnologySuggestionsAreLocalizedAndUsefulInSwahili() =
        runLocalTechnologySuggestionCapture("sw", "097-local-technology-sw")

    private fun runLocalTechnologySuggestionCapture(language: String, capturePrefix: String) {
        if (language == "sw") runBlocking {
            app.repository.update { it.copy(settings = it.settings.copy(language = "sw")) }
        }
        start()
        val now = System.currentTimeMillis()
        val topic = if (language == "sw") "Zana ya AI ya ndani kwa timu za utafiti" else "A local AI toolkit for research teams"
        val syntheticStory = FeedItem(
            id = "toolkit", moduleId = "technology", kind = "technology", title = topic,
            summary = if (language == "sw") "Taarifa ya mfano ya kupima mapendekezo ya ndani." else "Synthetic fixture for the local suggestions preview.",
            sourceName = "Example Research", sourceUrl = "https://example.com/research-toolkit",
            publishedAtMillis = now, fetchedAtMillis = now, relevance = 100,
        )
        runBlocking {
            app.feedRepository.update { current ->
                current.copy(
                    modules = current.modules.map { it.copy(enabled = it.id == "technology") },
                    items = listOf(syntheticStory),
                    lastRefreshMillis = now,
                    connection = AiConnection(),
                )
            }
        }
        val viewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        waitForState { it.items.singleOrNull()?.id == syntheticStory.id }
        compose.waitUntil(15_000) { viewModel.ui.value.state?.items?.singleOrNull()?.id == syntheticStory.id }
        assertFalse("The local sheet fixture is disconnected", viewModel.ui.value.connected)

        val currentState = state()
        val target = requireNotNull(SuggestionTargets097.item(currentState, syntheticStory.id, language))
        val options = viewModel.suggestionOptions097(target, language)
        assertEquals("The technology fallback comes from the real VM and stays bounded", 2, options.size)
        assertTrue("Both options are honestly identified as local", options.all { it.source == SuggestionOptionSource097.LOCAL })
        assertTrue(options[0].displayText.contains(syntheticStory.title))
        assertTrue(options[1].displayText.contains(syntheticStory.title))
        assertFalse(options.any { it.displayText.contains("approved interests", ignoreCase = true) })
        val requestsBefore = state().requestsToday
        val providerCallsBefore = app.answerCalls

        screenshot("$capturePrefix-feed-top")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-suggestions-toolkit-open"))
        compose.onNodeWithTag("feed-suggestions-toolkit-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("feed-suggestions-toolkit-source-0").assertTextEquals(text(R.string.suggestions_source_local))
        compose.onNodeWithTag("feed-suggestions-toolkit-source-1").assertTextEquals(text(R.string.suggestions_source_local))
        compose.onNodeWithTag("feed-suggestions-toolkit-question-0").assertTextEquals(options[0].displayText)
        compose.onNodeWithTag("feed-suggestions-toolkit-question-1").assertTextEquals(options[1].displayText)
        compose.onNodeWithTag("feed-suggestions-toolkit-offline-note").assertIsDisplayed()
        compose.onNodeWithTag("feed-suggestions-toolkit-send-0").assertIsNotEnabled()
        compose.onNodeWithTag("feed-suggestions-toolkit-edit-0").assertIsEnabled()
        screenshot("$capturePrefix-feed-sheet")
        compose.onNodeWithTag("feed-suggestions-toolkit-close").performClick()
        assertEquals("Opening and cancelling local options is UI-only", requestsBefore, state().requestsToday)
        assertEquals("Opening and cancelling local options never reaches the provider", providerCallsBefore, app.answerCalls)
    }

    /**
     * Full-shell journey uses only the deterministic test provider and a clearly
     * synthetic cached suggestion. Opening/cancelling is UI-only; explicit Send
     * creates a story-scoped request; the pencil prepares an unsent draft.
     */
    private fun runSuggestionJourney(language: String, capturePrefix: String) {
        app.sourceItems = stories()
        // The rendered response timestamp must not reveal the test runner's local timezone.
        // Set an explicit synthetic profile timezone before onboarding preserves defaults.
        runBlocking { app.feedRepository.update { it.copy(timezone = "UTC") } }
        if (language == "sw") {
            // Localized, explicitly synthetic answer from the deterministic test provider.
            // This keeps the full-shell Swahili capture honest without painting over app UI.
            app.answerText = "Mfano wa jibu la jaribio: Linganisha gharama na muda wa usanidi kabla ya kuchagua zana."
            runBlocking {
                app.repository.update { it.copy(settings = it.settings.copy(language = "sw")) }
            }
        }
        start()
        assertEquals("Screenshot profile timezone is an explicit synthetic fixture", "UTC", state().timezone)
        waitForState { it.items.size == 3 && it.lastRefreshMillis > 0 }
        connectTestProvider()
        cacheGroundedSuggestion("toolkit", language)

        tab("feed")
        waitForTag("feed-screen")
        val feedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java]
        compose.waitUntil(15_000) { feedViewModel.ui.value.state?.items?.find { it.id == "toolkit" }?.suggestedInteractions?.isNotEmpty() == true }
        screenshot("$capturePrefix-feed-top")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-suggestions-toolkit-open"))
        compose.onNodeWithTag("feed-suggestions-toolkit-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("feed-suggestions-toolkit-question-0").assertTextContains(
            if (language == "sw") "mabadiliko" else "What changes",
            substring = true,
        )
        compose.onNodeWithTag("feed-suggestions-toolkit-source-0").assertTextEquals(text(R.string.suggestions_source_cached))
        compose.onNodeWithText("Example Research", substring = true).assertExists()
        screenshot("$capturePrefix-feed-sheet")
        val requestsBeforeFeedSuggestion = state().requestsToday
        compose.onNodeWithTag("feed-suggestions-toolkit-close").performClick()
        assertEquals("Opening and cancelling the sheet is UI-only", requestsBeforeFeedSuggestion, state().requestsToday)
        val providerCallsBeforeSend = app.answerCalls
        compose.onNodeWithTag("feed-suggestions-toolkit-open").performClick()
        compose.onNodeWithTag("feed-suggestions-toolkit-send-0").performClick()
        waitForTag("workspace-question")
        val expectedPrompt = if (language == "sw")
            "Ni mabadiliko gani yanayoletwa na taarifa hii kwa watu wanaotumia zana?"
        else "What changes does this update bring for people using the toolkit?"
        waitForState { current -> current.conversations.any { conversation ->
            conversation.itemId == "toolkit" && conversation.turns.any { it.role == "user" && it.text == expectedPrompt }
        } }
        waitForState { it.requestsToday >= requestsBeforeFeedSuggestion + 1 }
        val scopedThread = state().conversations.single { it.itemId == "toolkit" }
        assertEquals("A primary feed Send is explicitly item-scoped", "toolkit", scopedThread.itemId)
        // Do not open the next sheet while the previous action is still draining. A user
        // should not be able to mistake an active request for a sheet-opening side effect.
        compose.waitUntil(15_000) {
            val current = state()
            val currentUi = feedViewModel.ui.value
            val currentThread = current.conversations.singleOrNull { it.id == scopedThread.id }
            current.requestsToday == requestsBeforeFeedSuggestion + 1 &&
                app.answerCalls == providerCallsBeforeSend + 1 &&
                !currentUi.asking && !currentUi.suggestionWorking &&
                currentThread?.turns?.lastOrNull { it.role == "assistant" } != null
        }
        compose.waitForIdle()
        assertEquals("One primary Send reserves exactly one request", requestsBeforeFeedSuggestion + 1, state().requestsToday)
        assertEquals("One primary Send calls the deterministic provider exactly once", providerCallsBeforeSend + 1, app.answerCalls)
        screenshot("$capturePrefix-mebot-after-send")

        // The bottom icon in MeBot targets the exact current item conversation;
        // with the first question now present, its next step is a distinct local question.
        val chatTarget = mebot.nguli.com.feed.SuggestionTargets097.conversation(state(), scopedThread.id, language)!!
        val feedViewModelOptions = feedViewModel.suggestionOptions097(chatTarget, language)
        val nextChatOption = feedViewModelOptions.first()
        assertTrue("The already-sent prompt must not be offered again", nextChatOption.displayText != expectedPrompt)
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-question-0").assertTextContains(nextChatOption.displayText.take(24), substring = true)
        screenshot("$capturePrefix-chat-sheet")
        val requestsBeforeChatEdit = state().requestsToday
        compose.onNodeWithTag("workspace-suggestions-edit-0").performClick()
        awaitConversationDraft(feedViewModel, scopedThread.id, nextChatOption.displayText, "$capturePrefix-chat-draft-not-ready")
        assertEquals("Pencil stays in this exact thread and never sends", scopedThread.id, feedViewModel.ui.value.activeConversationId)
        assertEquals(requestsBeforeChatEdit, state().requestsToday)
        val requestsBeforeDraftSheet = state().requestsToday
        compose.onNodeWithTag("workspace-suggestions-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("workspace-suggestions-question-0").assertIsDisplayed()
        screenshot("$capturePrefix-chat-sheet-draft")
        compose.onNodeWithTag("workspace-suggestions-close").performClick()
        assertEquals("Opening suggestions preserves the unsent composer draft", requestsBeforeDraftSheet, state().requestsToday)
        compose.onNodeWithTag("workspace-question").assertTextEquals(nextChatOption.displayText)
        screenshot("$capturePrefix-mebot-prefilled")

        tab("feed")
        compose.onNodeWithTag("item-toolkit").performScrollTo().performClick()
        waitForTag("detail-save")
        waitForState { it.briefs.any { brief -> brief.itemId == "toolkit" } }
        assertTrue(compose.onAllNodesWithText("Example Research", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("detail-screen").performScrollToNode(hasTestTag("detail-suggestions-toolkit-open"))
        compose.onNodeWithTag("detail-suggestions-toolkit-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail-suggestions-toolkit-question-0").assertIsDisplayed()
        screenshot("$capturePrefix-detail-sheet")
        val requestsBeforeDetailSuggestion = state().requestsToday
        compose.onNodeWithTag("detail-suggestions-toolkit-edit-0").performClick()
        waitForTag("workspace-question")
        waitForText(text(R.string.suggestions_replace_title))
        compose.onNodeWithTag("workspace-question").assertTextContains(nextChatOption.displayText)
        compose.onNodeWithTag("workspace-suggestion-keep").performClick()
        assertEquals("Choosing a detail suggestion must not send an AI request", requestsBeforeDetailSuggestion, state().requestsToday)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("detail-screen")
        // Detail's per-story scroll position is saveable; returning from MeBot
        // restores the suggestion's lower scroll position, so bring the source
        // action back into view before asserting it remains available.
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("feed-screen")
    }

    /** Wait for a pencil edit to populate the active conversation without appending a turn. */
    private fun awaitConversationDraft(
        feedViewModel: mebot.nguli.com.feed.FeedViewModel,
        conversationId: String,
        expected: String,
        failureScreenshot: String,
    ) {
        try {
            compose.waitUntil(15_000) {
                val latest = feedViewModel.ui.value
                val active = latest.state?.conversations?.singleOrNull { it.id == latest.activeConversationId }
                val editable = runCatching {
                    compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config
                        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text
                }.getOrNull()
                latest.activeConversationId == conversationId && active?.turns?.any { it.role == "user" && it.text == expected } == false && editable == expected
            }
        } catch (failure: Throwable) {
            screenshot(failureScreenshot)
            throw AssertionError("Pencil edit did not populate conversation $conversationId with an unsent draft", failure)
        }
    }

    /** Wait for both the durable item-scoped thread and the composer application event. */
    private fun awaitPreparedDraft(
        feedViewModel: mebot.nguli.com.feed.FeedViewModel,
        itemId: String,
        expected: String,
        failureScreenshot: String,
    ) {
        try {
            compose.waitUntil(15_000) {
                val latest = feedViewModel.ui.value
                val activeId = latest.activeConversationId
                val thread = latest.state?.conversations?.find { it.id == activeId }
                val editable = runCatching {
                    compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config
                        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text
                }.getOrNull()
                thread?.itemId == itemId && thread.turns.isEmpty() && editable == expected
            }
        } catch (failure: Throwable) {
            screenshot(failureScreenshot)
            val latest = feedViewModel.ui.value
            val activeId = latest.activeConversationId
            val active = latest.state?.conversations?.find { it.id == activeId }
            val editable = runCatching {
                compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config
                    .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text
            }.getOrNull()
            throw AssertionError(
                "Prepared suggestion was not applied: expectedItem=$itemId active=$activeId " +
                    "activeItem=${active?.itemId} turns=${active?.turns?.size} " +
                    "pendingDraft=${latest.suggestionDraft} editable=$editable error=${latest.error}", failure,
            )
        }
    }

    private fun connectTestProvider() {
        tab("you")
        clickText(R.string.feed_connection)
        waitForTag("chatgpt-sign-in")
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        waitForTag("connection-key")
        compose.onNodeWithTag("connection-key").performTextInput("valid-test-credential")
        compose.onNodeWithTag("connection-connect").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.feedSecrets.read() == "valid-test-credential" }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("connection-key").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(15_000) {
            androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java].ui.value.connected
        }
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()
        waitForText(text(R.string.feed_you_title))
    }

    private fun cacheGroundedSuggestion(itemId: String, language: String) {
        val now = System.currentTimeMillis()
        val label = if (language == "sw") "Uliza kinachobadilika" else "Ask what changes"
        val prompt = if (language == "sw")
            "Ni mabadiliko gani yanayoletwa na taarifa hii kwa watu wanaotumia zana?"
        else "What changes does this update bring for people using the toolkit?"
        runBlocking {
            app.feedRepository.update { current ->
                val item = current.items.single { it.id == itemId }
                val module = current.modules.single { it.id == item.moduleId }
                require(module.enabled)
                val unverified = item.copy(
                    suggestedInteractions = listOf(AiSuggestedInteraction(label = label, prompt = prompt)),
                    suggestedInteractionsGeneratedAtMillis = now,
                )
                val cached = unverified.copy(
                    suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(unverified),
                    suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(current, module, language),
                )
                current.copy(items = current.items.map { if (it.id == itemId) cached else it })
            }
        }
    }

    @Test fun replacingAKeyDoesNotSaveTheTypedSecretAcrossRotation() {
        start()
        tab("you")
        clickText(R.string.feed_connection)
        waitForTag("chatgpt-sign-in")
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        waitForTag("connection-key")
        compose.onNodeWithTag("connection-key").performTextInput("never-save-this-test-key")
        compose.activityRule.scenario.recreate()
        waitForTag("connection-key")
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsNotEnabled()
        assertNull(app.feedSecrets.read())
        assertFalse(File(app.filesDir, "mebot/feed.json").readText().contains("never-save-this-test-key"))
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun compactDarkSwahiliFeedHasReachableSaveAndModuleActions() {
        runBlocking {
            app.repository.update { it.copy(profile = it.profile.copy(onboardingComplete = true), settings = it.settings.copy(language = "sw", theme = "dark", accent = "sage")) }
            app.feedRepository.update { it.copy(onboardingComplete = true, name = "Alex", items = stories(), lastRefreshMillis = System.currentTimeMillis()) }
        }
        waitForTag("feed-screen")
        compose.activityRule.scenario.recreate()
        waitForTag("feed-screen")
        screenshot("27-mebot-feed-small-swahili-dark")
        compose.onNodeWithTag("save-toolkit").performScrollTo().performClick()
        waitForState { "toolkit" in it.savedIds }
        tab("modules")
        compose.onNodeWithTag("module-add").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("module-alarm").performScrollTo().assertIsDisplayed()
        assertTrue(state().onboardingComplete)
    }

    private fun start() {
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        waitForTag("feed-screen")
        waitForState { it.onboardingComplete }
    }
    private fun state() = runBlocking { app.feedRepository.current() }
    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
    private fun tab(name: String) { compose.onNodeWithTag("nav-$name").performClick() }
    private fun back() { compose.onNodeWithContentDescription(text(R.string.back)).performClick() }
    private fun clickText(resource: Int) { compose.onNodeWithText(text(resource)).performScrollTo().performClick() }
    private fun waitForTag(tag: String) {
        try { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("Expected tag $tag was not found:\n${compose.onRoot().printToString()}", failure)
        }
    }
    private fun waitForText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForState(predicate: (FeedState) -> Boolean) {
        try { compose.waitUntil(15_000) { predicate(state()) } }
        catch (failure: ComposeTimeoutException) { throw AssertionError("Feed state condition failed: ${state()}\n${compose.onRoot().printToString()}", failure) }
    }
    private fun stories(): List<FeedItem> {
        val now = System.currentTimeMillis()
        return listOf(
            FeedItem(id = "toolkit", moduleId = "news", title = "A local AI toolkit for your next project",
                summary = "A small, open source toolkit makes it easier to try language models on your own device.",
                sourceName = "Example Research", sourceUrl = "https://example.com/toolkit",
                publishedAtMillis = now - 45 * 60_000, fetchedAtMillis = now,
                citations = listOf(FeedCitation("Example Research", "https://example.com/toolkit"))),
            FeedItem(id = "design", moduleId = "news", title = "Designing calmer daily routines",
                summary = "A practical look at useful defaults, fewer decisions, and interfaces that respect your attention.",
                sourceName = "Example Design", sourceUrl = "https://example.com/design",
                publishedAtMillis = now - 2 * 60 * 60_000, fetchedAtMillis = now),
            FeedItem(id = "android", moduleId = "news", title = "Small improvements for Android builders",
                summary = "Tools and patterns to make everyday app work more reliable.",
                sourceName = "Example Developer", sourceUrl = "https://example.com/android",
                publishedAtMillis = now - 3 * 60 * 60_000, fetchedAtMillis = now),
        )
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        // Compose ModalBottomSheet lives in a platform Dialog window, so PixelCopy of the
        // activity alone omits it. Capture that actual native Compose root and composite it
        // over the full-shell activity frame for reviewable end-to-end evidence.
        val sheetTag = when {
            name.endsWith("-feed-sheet") -> "feed-suggestions-toolkit-sheet"
            name.endsWith("-chat-sheet") || name.endsWith("-chat-sheet-draft") -> "workspace-suggestions-sheet"
            name.endsWith("-detail-sheet") -> "detail-suggestions-toolkit-sheet"
            else -> null
        }
        if (sheetTag != null) {
            compose.onNodeWithTag(sheetTag).assertIsDisplayed()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "No native bottom-sheet Dialog to capture" }
            assertTrue("The captured sheet Dialog must be visible", dialog.isShowing)
            val dialogWindow = requireNotNull(dialog.window)
            val modal = compose.runOnIdle {
                val view = dialogWindow.decorView
                view.invalidate()
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    var result: Int? = null
                    PixelCopy.request(dialogWindow, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                    assertEquals(PixelCopy.SUCCESS, result)
                }
            }
            val location = IntArray(2)
            dialogWindow.decorView.getLocationOnScreen(location)
            Canvas(bitmap).drawBitmap(modal, location[0].toFloat(), location[1].toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            modal.recycle()
        }
        val output = File("build/outputs/screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 0)
    }
}
