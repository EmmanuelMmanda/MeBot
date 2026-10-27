package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedTestApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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
    fun englishFullShellSuggestionsAreStoryGroundedAndOnlyPrefill() =
        runSuggestionJourney("en", "095-full-shell-en")

    @Test
    @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliFullShellSuggestionsAreStoryGroundedAndOnlyPrefill() =
        runSuggestionJourney("sw", "095-full-shell-sw")

    /**
     * Full-shell journey uses only the deterministic test provider and a clearly
     * synthetic cached suggestion. Selecting it must create an editable draft,
     * never a hidden inference; publisher/source access stays available.
     */
    private fun runSuggestionJourney(language: String, capturePrefix: String) {
        app.sourceItems = stories()
        if (language == "sw") {
            runBlocking {
                app.repository.update { it.copy(settings = it.settings.copy(language = "sw")) }
            }
        }
        start()
        waitForState { it.items.size == 3 && it.lastRefreshMillis > 0 }
        connectTestProvider()
        cacheGroundedSuggestion("toolkit", language)

        tab("feed")
        waitForTag("feed-screen")
        val feedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java]
        compose.waitUntil(15_000) { feedViewModel.ui.value.state?.items?.find { it.id == "toolkit" }?.suggestedInteractions?.isNotEmpty() == true }
        screenshot("$capturePrefix-feed-top")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-story-suggestion-chip-0"))
        compose.onNodeWithTag("feed-screen").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("feed-story-suggestion-chip-0").assertIsDisplayed()
        compose.onNodeWithText(if (language == "sw") "Uliza kinachobadilika" else "Ask what changes").assertExists()
        compose.onNodeWithText("Example Research", substring = true).assertExists()
        screenshot("$capturePrefix-feed")
        val requestsBeforeFeedSuggestion = state().requestsToday
        compose.onNodeWithTag("feed-story-suggestion-chip-0").performClick()
        waitForTag("workspace-question")
        val expectedPrompt = if (language == "sw")
            "Ni mabadiliko gani yanayoletwa na taarifa hii kwa watu wanaotumia zana?"
        else "What changes does this update bring for people using the toolkit?"
        awaitPreparedDraft(feedViewModel, "toolkit", expectedPrompt, "$capturePrefix-feed-draft-not-ready")
        compose.onNodeWithTag("workspace-question").assertTextContains(expectedPrompt)
        assertEquals("Choosing a feed suggestion must not send an AI request", requestsBeforeFeedSuggestion, state().requestsToday)
        screenshot("$capturePrefix-mebot-prefilled")

        tab("feed")
        compose.onNodeWithTag("item-toolkit").performScrollTo().performClick()
        waitForTag("detail-save")
        waitForState { it.briefs.any { brief -> brief.itemId == "toolkit" } }
        assertTrue(compose.onAllNodesWithText("Example Research", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("detail-screen").performScrollToNode(hasTestTag("detail-question-0"))
        compose.onNodeWithTag("detail-screen").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("detail-question-0").assertIsDisplayed()
        screenshot("$capturePrefix-detail")
        val requestsBeforeDetailSuggestion = state().requestsToday
        compose.onNodeWithTag("detail-question-0").performClick()
        waitForTag("workspace-question")
        awaitPreparedDraft(feedViewModel, "toolkit", expectedPrompt, "$capturePrefix-detail-draft-not-ready")
        compose.onNodeWithTag("workspace-question").assertTextContains(expectedPrompt)
        assertEquals("Choosing a detail suggestion must not send an AI request", requestsBeforeDetailSuggestion, state().requestsToday)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("detail-screen")
        // Detail's per-story scroll position is saveable; returning from MeBot
        // restores the suggestion's lower scroll position, so bring the source
        // action back into view before asserting it remains available.
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("feed-screen")

        // A fresh general MeBot thread has its own current-story rail rather than
        // stale module-wide starters. Selecting there also only prepares text.
        tab("ai")
        waitForTag("workspace-new")
        compose.onNodeWithTag("workspace-new").performClick()
        waitForTag("workspace-start-suggestions")
        val rootSuggestionStory = FeedLogic.edition(state(), System.currentTimeMillis())
            .first { it.weather == null && it.kind !in setOf("weather", "horoscope") }
        val expectedRootPrompt = if (language == "sw")
            "Nieleze “${rootSuggestionStory.title}” kwa lugha rahisi, kwa kutumia chanzo chake."
        else "Explain “${rootSuggestionStory.title}” in simple terms, using its source."
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag("workspace-start-suggestions"))
        compose.onNodeWithTag("workspace-start-suggestions-story-0").assertIsDisplayed()
        // Bind the expectation to the actual visible first story, before selecting
        // its chip, so an ordering/routing bug cannot pass by inspecting the result.
        compose.onNodeWithText(rootSuggestionStory.title).assertIsDisplayed()
        compose.onNodeWithTag("workspace-start-suggestions-question-0-0").assertIsDisplayed()
        screenshot("$capturePrefix-mebot-story-rail")
        val requestsBeforeWorkspaceSuggestion = state().requestsToday
        compose.onNodeWithTag("workspace-start-suggestions-question-0-0").performClick()
        waitForTag("workspace-question")
        awaitPreparedDraft(feedViewModel, rootSuggestionStory.id, expectedRootPrompt, "$capturePrefix-root-draft-not-ready")
        compose.onNodeWithTag("workspace-question").assertTextContains(expectedRootPrompt)
        assertEquals(rootSuggestionStory.id, feedViewModel.ui.value.state?.conversations
            ?.find { it.id == feedViewModel.ui.value.activeConversationId }?.itemId)
        assertEquals("Choosing a MeBot suggestion must not send an AI request", requestsBeforeWorkspaceSuggestion, state().requestsToday)
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
        val output = File("build/outputs/screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 0)
    }
}
