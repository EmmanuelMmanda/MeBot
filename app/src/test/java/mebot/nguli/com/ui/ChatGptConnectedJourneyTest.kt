package mebot.nguli.com.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import mebot.nguli.com.feed.auth.*
import mebot.nguli.com.ui.feed.ContextSetup
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Actual Activity → ViewModel → Engine → repository journey; only external auth/model calls are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = ChatGptJourneyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatGptConnectedJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatGptJourneyApplication

    @Before fun resetInstallation() {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.clearAllCredentials()
            app.repository.erase()
            app.feedRepository.erase()
        }
        app.alarms.clearAll()
        app.checked.clear()
        app.checkedConnections.clear()
        app.asked.clear()
        app.askedQuestions.clear()
        app.askedContexts.clear()
        app.searched.clear()
        app.searchItems = emptyList()
        app.checkGate = null
        app.checkFailure = null
        app.providerDiscoveries.clear()
        waitForTag("setup-099-onboarding")
    }

    @Test fun verifiedNewUserConnectionImportsOnlyChosenContextOnceAndSurvivesRecreation() {
        app.checkGate = CompletableDeferred()
        val original = state()
        assertEquals(Setup099Stage.INTRO, original.setup099.stage)
        assertEquals(AiConnection(), original.connection)
        compose.onNodeWithTag("setup099-connect").performScrollTo().performClick()
        waitForTag("connection-screen")
        compose.waitUntil(10_000) { state().setup099.stage == Setup099Stage.CONNECT_CHATGPT }
        assertEquals(Setup099Stage.CONNECT_CHATGPT, state().setup099.stage)
        compose.onNodeWithTag("chatgpt-sign-in").performScrollTo().performClick()
        waitForTag("chatgpt-cancel")
        compose.waitUntil(15_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        assertEquals("direct", state().connection.mode)

        app.chatGptAuth.completeSignIn()
        compose.waitUntil(15_000) { app.checked.size == 1 }
        val viewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        assertFalse("Sign-in alone is not a configured connection", viewModel.ui.value.connected)
        assertEquals("direct", state().connection.mode)
        assertEquals("", state().setup099.firstChatGptAccountKey)
        assertEquals("", state().setup099.pendingImportAccountKey)
        assertTrue(compose.onAllNodesWithTag("context-import-screen").fetchSemanticsNodes().isEmpty())

        app.checkGate!!.complete(Unit)
        waitForTag("context-import-screen")
        compose.waitUntil(15_000) {
            val setup = state().setup099
            setup.firstChatGptAccountKey == UiFixtureChatGptAuth.ACCOUNT_ID &&
                setup.pendingImportAccountKey.isEmpty() &&
                setup.importRouteAcknowledgedAccountKey == UiFixtureChatGptAuth.ACCOUNT_ID
        }
        assertEquals("chatgpt", state().connection.mode)
        assertEquals("fixture-quick", state().connection.model)
        acknowledgeChatGptPlanNotice()
        compose.waitUntil(10_000) { app.chatGptAuth.state.value.accounts.single().planNoticeAcknowledged }
        compose.onNodeWithTag("context-import-chatgpt-verified").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.setup099_connected_model, "Fixture quick model"))
            .assertIsDisplayed()
        compose.onNodeWithTag("context-starter-prompt").performScrollTo().performClick()
        compose.onNodeWithTag("context-open-chatgpt").performScrollTo().assertIsDisplayed()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(ContextSetup.prompt(compose.activity), clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertFalse(ContextSetup.prompt(compose.activity).contains("Keep explanations concise"))

        compose.activityRule.scenario.recreate()
        waitForTag("context-import-screen")
        assertTrue(compose.onAllNodesWithTag("chatgpt-plan-got-it").fetchSemanticsNodes().isEmpty())
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, state().setup099.importRouteAcknowledgedAccountKey)
        assertEquals("", state().setup099.pendingImportAccountKey)
        compose.onNodeWithTag("context-import-chatgpt-verified").assertIsDisplayed()

        val contextJson = ContextSetup.example("Keep explanations concise and useful")
        compose.onNodeWithTag("context-paste-input").performScrollTo().performTextInput(contextJson)
        compose.onNodeWithTag("context-paste-review").performScrollTo().performClick()
        waitForTag("import-review")
        assertEquals("Previewing context must not save it early", original.context, state().context)
        compose.onNodeWithTag("import-confirm").performClick()
        waitForTag("context-edit-preference-1")
        compose.waitUntil(15_000) { state().setup099.stage == Setup099Stage.START_FEED }
        assertEquals("Keep explanations concise and useful", state().context.single { it.id == "preference-1" }.text)
        compose.onNodeWithTag("setup099-next-feed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup099-next-feed-button").performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.waitUntil(15_000) { state().onboardingComplete && state().setup099.stage == Setup099Stage.COMPLETE }
        compose.activityRule.scenario.recreate()
        waitForTag("feed-screen")
        assertTrue(compose.onAllNodesWithTag("context-import-screen").fetchSemanticsNodes().isEmpty())
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, state().setup099.importRouteAcknowledgedAccountKey)
        assertEquals(AiConnection(mode = "chatgpt", model = "fixture-quick", accountId = UiFixtureChatGptAuth.ACCOUNT_ID), state().connection)
    }

    @Test fun upgradeUserExplicitlyAddsChatGptWithoutReplacingExistingProviderUntilVerified() {
        app.checkGate = CompletableDeferred()
        val existing = AiConnection(mode = "direct", model = "existing-model")
        val item = FeedItem(id = "upgrade-item", moduleId = "news", title = "Keep this saved story", summary = "Existing data",
            sourceName = "Example publisher", sourceUrl = "https://example.com/story")
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true)) }
            app.feedRepository.update { FeedState(
                onboardingComplete = true,
                connection = existing,
                items = listOf(item),
                savedIds = setOf(item.id),
            ) }
        }
        waitForTag("feed-screen")
        assertEquals(Setup099Stage.INTRO, state().setup099.stage)

        // Existing users are not routed into setup on launch; they can opt in by opening Connect.
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("connection-reconnect-provider-099")
        compose.waitUntil(10_000) { state().setup099.stage == Setup099Stage.CONNECT_CHATGPT }
        assertEquals(existing, state().connection)
        assertEquals(setOf(item.id), state().savedIds)

        // Keep the existing provider visibly primary while exposing ChatGPT as an explicit alternative.
        compose.onNodeWithTag("connection-reconnect-provider-099").performClick()
        compose.onNodeWithTag("chatgpt-alternative-primary-099").assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-sign-in-alternative-099").performScrollTo().performClick()
        waitForTag("chatgpt-cancel")
        compose.waitUntil(15_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        app.chatGptAuth.completeSignIn()
        compose.waitUntil(15_000) { app.checked.size == 1 }
        assertEquals("An unverified login cannot replace the current provider", existing, state().connection)
        assertEquals(Setup099Stage.CONNECT_CHATGPT, state().setup099.stage)
        assertEquals("", state().setup099.firstChatGptAccountKey)
        assertEquals("", state().setup099.pendingImportAccountKey)

        app.checkGate!!.complete(Unit)
        waitForTag("context-import-screen")
        compose.waitUntil(15_000) {
            state().setup099.importRouteAcknowledgedAccountKey == UiFixtureChatGptAuth.ACCOUNT_ID
        }
        assertEquals(AiConnection(mode = "chatgpt", model = "fixture-quick", accountId = UiFixtureChatGptAuth.ACCOUNT_ID), state().connection)
        assertEquals("Only the explicit provider choice and verified model changes the selected connection",
            "chatgpt", state().connection.mode)
        assertEquals(setOf(item.id), state().savedIds)
        compose.onNodeWithTag("context-import-chatgpt-verified").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForTag("context-import-screen")
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, state().setup099.importRouteAcknowledgedAccountKey)
        assertEquals("", state().setup099.pendingImportAccountKey)
    }

    @Test fun savedChatGptAlternativeRemainsAccessibleAndDoesNotReplaceProviderUntilVerified() {
        app.checkGate = CompletableDeferred()
        val accountId = "saved-alt-TEST02"
        val existing = AiConnection(mode = "direct", model = "existing-model")
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true)) }
            app.feedRepository.update { FeedState(onboardingComplete = true, connection = existing) }
        }
        app.chatGptAuth.restoreSavedAccount(accountId, includeModels = false)
        waitForTag("feed-screen")

        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("connection-reconnect-provider-099")
        assertEquals(existing, state().connection)
        assertEquals(Setup099ConnectionEntry.UNKNOWN, state().setup099.preferredConnectionEntry099)

        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-use-$accountId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-use-$accountId").assertTextContains(text(R.string.edition_check_connection))
        compose.onNodeWithTag("chatgpt-use-$accountId").performClick()
        waitForTag("chatgpt-cancel")
        compose.waitUntil(15_000) { app.checkedConnections.any { it.mode == "chatgpt" } }
        assertEquals("The current provider remains selected until the explicit account passes verification",
            existing, state().connection)

        app.checkGate!!.complete(Unit)
        compose.waitUntil(15_000) {
            state().connection.mode == "chatgpt" && state().connection.accountId == accountId
        }
        waitForTag("context-import-screen")
        compose.waitUntil(10_000) {
            state().setup099.preferredConnectionEntry099 == Setup099ConnectionEntry.CHATGPT
        }
    }

    @Test fun guidedManualContextSaveStaysOnNotesAndShowsDurableNextFeedAction() {
        app.checkGate = CompletableDeferred()
        compose.onNodeWithTag("setup099-connect").performScrollTo().performClick()
        waitForTag("connection-screen")
        compose.onNodeWithTag("chatgpt-sign-in").performScrollTo().performClick()
        waitForTag("chatgpt-cancel")
        compose.waitUntil(15_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        app.chatGptAuth.completeSignIn()
        compose.waitUntil(15_000) { app.checked.size == 1 }
        app.checkGate!!.complete(Unit)
        waitForTag("context-import-screen")
        compose.waitUntil(15_000) { state().setup099.importRouteAcknowledgedAccountKey == UiFixtureChatGptAuth.ACCOUNT_ID }
        acknowledgeChatGptPlanNotice()

        compose.onNodeWithTag("context-manual-entry").performScrollTo()
        centerNodeInScrollViewport("context-import-screen", "context-manual-entry")
        compose.onNodeWithTag("context-manual-entry").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        try {
            waitForTag("context-edit")
        } catch (timeout: ComposeTimeoutException) {
            val pixels = captureDiagnosticWindows("manual-context")
            throw AssertionError(
                "Manual context action did not open the notes editor. " +
                    "Setup stage=${state().setup099.stage}; acknowledged account=" +
                    "${state().setup099.importRouteAcknowledgedAccountKey}; pending account=" +
                    "${state().setup099.pendingImportAccountKey}; top dialog=" + latestDialogDescription() + ".\n" +
                    "Captured window pixels: $pixels\n" + compose.onRoot().printToString(),
                timeout,
            )
        }
        val contextEditor = compose.onNodeWithTag("context-edit").performScrollTo().assertIsDisplayed().assertIsEnabled()
        contextEditor.performTextReplacement("I prefer practical examples and concise explanations.")
        assertEquals("The manual profile draft must contain the exact chosen text before Save",
            "I prefer practical examples and concise explanations.",
            contextEditor.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertTrue("Drafting manual notes must not persist before Save", state().context.none { it.text.isNotBlank() })
        compose.onNodeWithTag("context-save").performScrollTo()
        centerNodeInScrollViewport("context-screen", "context-save")
        val saveButton = compose.onNodeWithTag("context-save").assertIsDisplayed().assertIsEnabled()
        val saveBounds = saveButton.fetchSemanticsNode().boundsInRoot
        val feedVm = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        // Use an actual pointer tap after centering the visible button. The saved-instance flow has
        // already exposed click delivery bugs for controls clipped at a scroll viewport edge.
        saveButton.performTouchInput { click() }
        try {
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                feedVm.ui.value.contextSaving || state().context.any {
                    it.confirmed && it.text == "I prefer practical examples and concise explanations."
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            val editorText = contextEditor.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            val contextInvalidShown = compose.onAllNodesWithText(text(R.string.feed_context_invalid))
                .fetchSemanticsNodes().isNotEmpty()
            val timezoneInvalidShown = compose.onAllNodesWithText(text(R.string.profile_timezone_invalid_085))
                .fetchSemanticsNodes().isNotEmpty()
            throw AssertionError(
                "Centered Save click did not start a context write: bounds=$saveBounds, draftLength=${editorText.length}, " +
                    "contextSaving=${feedVm.ui.value.contextSaving}, contextError=${feedVm.ui.value.error}, " +
                    "contextValidationError=$contextInvalidShown, timezoneValidationError=$timezoneInvalidShown.\n" +
                    "PixelCopy=${captureDiagnosticWindows("manual-save")}.\n" + compose.onRoot().printToString(),
                timeout,
            )
        }
        try {
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                val saved = state()
                saved.setup099.stage == Setup099Stage.START_FEED &&
                    saved.context.any { it.confirmed && it.text == "I prefer practical examples and concise explanations." }
            }
        } catch (timeout: ComposeTimeoutException) {
            val saved = state()
            val currentUi = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java].ui.value
            throw AssertionError(
                "Manual context save did not reach the durable guided next step: stage=${saved.setup099.stage}, " +
                    "exactConfirmedContext=${saved.context.any { it.confirmed && it.text == "I prefer practical examples and concise explanations." }}, " +
                    "entries=${saved.context.map { it.id to (it.confirmed to it.text.length) }}, " +
                    "contextSaving=${currentUi.contextSaving}, error=${currentUi.error}.",
                timeout,
            )
        }
        // The guided path deliberately remains on the notes screen after saving, with both the
        // durable note and an explicit next action visible.
        compose.onNodeWithTag("context-edit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup099-next-feed").performScrollTo().assertIsDisplayed()
        assertTrue(state().context.any { it.id == PersonalProfile085.PROFILE_ID && it.global && it.confirmed })
        compose.onNodeWithTag("setup099-next-feed-button").performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.waitUntil(15_000) { state().onboardingComplete && state().setup099.stage == Setup099Stage.COMPLETE }
        assertTrue(state().context.any { it.text == "I prefer practical examples and concise explanations." })
    }

    @Test fun browserSignInVerifiedPlanAndAskStayConnectedAcrossRotationAndCancelledAccountAddition() {
        compose.onNodeWithTag("setup099-continue-public").performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("chatgpt-sign-in")
        app.checkGate = CompletableDeferred()
        compose.onNodeWithTag("chatgpt-sign-in").performClick()
        waitForTag("chatgpt-cancel")
        compose.waitUntil(15_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val browser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, browser.action)
        assertEquals("https", browser.data!!.scheme)
        assertEquals("auth.openai.com", browser.data!!.host)
        assertEquals("/api/accounts/authorize", browser.data!!.path)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(listOf<String?>(null), app.chatGptAuth.signInRequests)
        assertEquals("direct", state().connection.mode)
        compose.activityRule.scenario.recreate()
        waitForTag("chatgpt-cancel")
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(1, app.chatGptAuth.signInRequests.size)

        app.chatGptAuth.completeSignIn()
        compose.waitUntil(15_000) { app.checked.size == 1 }
        compose.onNodeWithText(text(R.string.feed_connected)).assertDoesNotExist()
        assertEquals("direct", state().connection.mode)
        app.checkGate!!.complete(Unit)
        waitForTag("chatgpt-plan-got-it")
        val connection = state().connection
        assertEquals("chatgpt", connection.mode)
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, connection.accountId)
        assertEquals("fixture-quick", connection.model)
        assertEquals("chatgpt:subscription-fixture-token", app.checked.single())
        assertNull(app.feedSecrets.read())
        acknowledgeChatGptPlanNotice()
        compose.onNodeWithTag("chatgpt-model-picker").assertTextContains("Fixture quick model")
        compose.activityRule.scenario.recreate()
        waitForTag("chatgpt-model-picker")
        compose.onNodeWithTag("chatgpt-plan-got-it").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(1, app.chatGptAuth.signInRequests.size)
        assertEquals(connection, state().connection)
        val feedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        val requestsBeforeConnectionContinue = state().requestsToday
        val lastAiRefreshBeforeConnectionContinue = state().lastAiRefreshMillis
        val discoveryCallsBeforeConnectionContinue = app.providerDiscoveries.size
        assertEquals("Only the plan connection check has run before Continue", 1, requestsBeforeConnectionContinue)
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()

        waitForText(text(R.string.feed_you_title))
        compose.waitUntil(15_000) {
            // The Continue action deliberately refreshes after changing the connected plan.
            // waitUntil alone may not drain the main-looper continuation from that refresh.
            compose.waitForIdle()
            val refreshed = state()
            !feedViewModel.ui.value.refreshing &&
                refreshed.lastAiRefreshMillis > lastAiRefreshBeforeConnectionContinue &&
                app.providerDiscoveries.size > discoveryCallsBeforeConnectionContinue
        }
        assertEquals("Plan connection refresh reserves its one enabled news discovery request",
            requestsBeforeConnectionContinue + 1, state().requestsToday)
        assertEquals(listOf("news"), app.providerDiscoveries.drop(discoveryCallsBeforeConnectionContinue))

        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        // EditionQuestion switches to a Connect action when the persisted connection is not
        // currently reflected in FeedUiState. Assert the connected copy before exercising its
        // chat route so that a stale connection state is diagnosed separately from navigation.
        compose.onNodeWithTag("edition-ask").assertTextContains(text(R.string.edition_ask_today))
        val requestsBeforeEmptyFeedPrompt = state().requestsToday
        val asksBeforeEmptyFeedPrompt = app.asked.toList()
        val questionsBeforeEmptyFeedPrompt = app.askedQuestions.toList()
        // Keep the card wholly inside the feed viewport, then deliver a real pointer click. The
        // card sits immediately above the bottom nav; a semantics-only performClick can hit its
        // clipped edge in this host and fail to exercise the Surface's pointer handler.
        val editionAsk = compose.onNodeWithTag("edition-ask")
        editionAsk.performScrollTo()
        centerNodeInScrollViewport("feed-screen", "edition-ask")
        editionAsk.assertIsDisplayed().assertHasClickAction().performTouchInput { click() }
        try {
            waitForTag("workspace-question")
        } catch (timeout: ComposeTimeoutException) {
            val pixels = captureDiagnosticWindows("edition-ask-pointer")
            // The pointer failure is the primary result. Invoke the same production semantic
            // action once afterward only to distinguish a broken runtime callback from pointer
            // delivery at this edge; never treat this diagnostic action as a passing route.
            val semanticCallbackRouted = runCatching {
                compose.onNodeWithTag("edition-ask")
                    .performSemanticsAction(SemanticsActions.OnClick) { onClick -> onClick() }
                compose.waitUntil(1_500) { compose.onAllNodesWithTag("workspace-question").fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            throw AssertionError(
                "Connected EditionQuestion did not expose the AI composer. " +
                    "Connection=${state().connection.mode}; request count=${state().requestsToday}; " +
                    "pointer route timed out; direct semantics diagnostic routed=$semanticCallbackRouted; " +
                    "top dialog=${latestDialogDescription()}; captured window pixels=$pixels.\n" +
                    compose.onRoot().printToString(),
                timeout,
            )
        }
        compose.onNodeWithTag("workspace-model").assertTextContains("Fixture quick model")
        assertEquals("Opening the empty-feed prompt must not reserve a request", requestsBeforeEmptyFeedPrompt, state().requestsToday)
        assertEquals("Opening the empty-feed prompt must not infer", asksBeforeEmptyFeedPrompt, app.asked.toList())
        assertEquals("Opening the empty-feed prompt must not submit a question", questionsBeforeEmptyFeedPrompt, app.askedQuestions.toList())
        val suggestionsBeforeAnswer = app.suggestedQuestionSets.size
        compose.onNodeWithTag("workspace-question").performTextInput(text(R.string.edition_ask_today_prompt))
        compose.onNodeWithTag("workspace-send").performClick()
        compose.waitUntil(15_000) { state().conversations.any { it.turns.lastOrNull()?.role == "assistant" } }
        val conversation = state().conversations.single()
        assertEquals(text(R.string.edition_ask_today_prompt), conversation.turns.first().text)
        assertEquals(ChatGptJourneyApplication.ANSWER, conversation.turns.last().text)
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(ChatGptJourneyApplication.ANSWER))
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals(listOf("chatgpt:subscription-fixture-token"), app.asked)
        assertEquals(listOf(text(R.string.edition_ask_today_prompt)), app.askedQuestions)
        assertTrue(app.askedContexts.single().isEmpty())
        // The assistant turn triggers a separate automatic suggestion prefetch. Let it settle
        // before measuring whether Activity recreation issues any additional model requests.
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            app.suggestedQuestionSets.size > suggestionsBeforeAnswer && !feedViewModel.ui.value.suggestionWorking
        }
        val requestsAfterAnswer = state().requestsToday
        val suggestionsAfterAnswer = app.suggestedQuestionSets.size
        compose.activityRule.scenario.recreate()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(ChatGptJourneyApplication.ANSWER))
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals(conversation, state().conversations.single())
        assertEquals("Restoring the completed conversation must not issue another request", requestsAfterAnswer, state().requestsToday)
        assertEquals("Restoring the completed conversation must not prefetch suggestions again", suggestionsAfterAnswer, app.suggestedQuestionSets.size)
        assertEquals("Restoring the completed conversation must not repeat the user's question", 1, app.asked.size)

        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("chatgpt-model-picker")
        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-add-account").performScrollTo().performClick()
        waitForTag("chatgpt-cancel")
        compose.onNodeWithTag("chatgpt-cancel").performScrollTo().performClick()
        waitForTag("chatgpt-model-picker")
        compose.onNodeWithTag("connection-continue").assertTextContains(text(R.string.feed_back_feed))
        assertEquals(connection, state().connection)
        assertEquals(1, app.checked.size)
        assertTrue(app.chatGptAuth.state.value.accounts.single().connected)
        assertNull(app.feedSecrets.read())
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
    }

    @Test fun feedSearchOpensASourcedResultWithAnAutomaticPlanExplanationAndReusesItAfterRotation() {
        app.chatGptAuth.restoreSavedAccount()
        openConnectionFromOnboarding()
        compose.onNodeWithTag("chatgpt-retry-account").performClick()
        waitForTag("chatgpt-model-picker")
        val feedViewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        val discoveriesBeforeContinue = app.providerDiscoveries.size
        val refreshBeforeContinue = feedViewModel.ui.value.state?.lastAiRefreshMillis ?: 0L
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()
        waitForText(text(R.string.feed_you_title))
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            val refreshed = feedViewModel.ui.value
            (refreshed.state?.lastAiRefreshMillis ?: 0L) > refreshBeforeContinue &&
                !refreshed.refreshing && app.providerDiscoveries.size > discoveriesBeforeContinue
        }
        val approved = ContextEntry(id = "search-interest", text = "Useful open-source tools", scopes = setOf("news"))
        val unshared = ContextEntry(id = "private-interest", text = "An unrelated music interest", scopes = setOf("music"))
        val url = "https://example.com/discovered-tool"
        val item = FeedItem("search-tool", "news", "A discovered open-source tool", "A synthetic source-backed search result.",
            sourceName = "Example documentation", sourceUrl = url, citations = listOf(FeedCitation("Example documentation", url)))
        // Keep the Compose test dispatcher live while DataStore serializes this fixture update.
        // Blocking here can prevent a pending refresh/Main continuation from making progress.
        val feedRepository = app.feedRepository
        val contextUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val contextUpdate = contextUpdateScope.async {
            withTimeout(10_000) {
                feedRepository.update { it.copy(context = listOf(approved, unshared)) }
            }
        }
        try {
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                contextUpdate.isCompleted
            }
            val persisted = runBlocking { contextUpdate.await() }
            assertEquals("The fixture context must be durably stored before the search begins",
                listOf(approved, unshared), persisted.context)
        } finally {
            contextUpdate.cancel()
            contextUpdateScope.cancel()
            runBlocking { withTimeoutOrNull(1_000) { contextUpdate.join() } }
        }
        app.searchItems = listOf(item)
        val requestsBeforeSearch = state().requestsToday
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-search")
        compose.onNodeWithTag("feed-search").performScrollTo().performClick()
        waitForTag("search-query")
        compose.onNodeWithTag("search-query").performTextInput("Useful open-source tools")
        compose.onNodeWithTag("search-filter-news").performClick()
        compose.onNodeWithTag("search-submit").performClick()
        compose.waitUntil(15_000) { state().items.any { it.id == item.id } }
        compose.waitForIdle()
        compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-item-${item.id}"))
        compose.onNodeWithTag("search-item-${item.id}").assertIsDisplayed()
        assertEquals(listOf("chatgpt:subscription-fixture-token:Useful open-source tools"), app.searched)
        assertEquals(requestsBeforeSearch + 1, state().requestsToday)
        assertEquals(item, state().items.single { it.id == item.id })
        val suggestionsBeforeDetail = app.suggestedScopes.size
        compose.onNodeWithTag("search-item-${item.id}").performClick()
        waitForTag("detail-screen")
        val detailSuggestionTarget = requireNotNull(SuggestionTargets097.item(state(), item.id, "en"))
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            state().briefs.any { it.itemId == item.id } &&
                state().requestsToday == requestsBeforeSearch + 3 &&
                app.suggestedScopes.size == suggestionsBeforeDetail + 1 &&
                state().suggestionSets097.any {
                    it.targetKind == "item" && it.targetId == item.id && it.revision == detailSuggestionTarget.revision
                }
        }
        compose.onNodeWithTag("detail-brief-answer").performScrollTo()
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals("Search, automatic brief, and one prepared suggestion batch are independently budgeted",
            requestsBeforeSearch + 3, state().requestsToday)
        assertEquals(listOf("${item.id}:${item.kind}"), app.suggestedScopes.drop(suggestionsBeforeDetail))
        assertEquals(listOf("chatgpt:subscription-fixture-token"), app.asked)
        assertTrue(app.askedQuestions.single().startsWith("Explain this item"))
        assertEquals(listOf(approved), app.askedContexts.single())
        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-citation-0").performScrollTo().assertIsDisplayed()
        while (shadowOf(compose.activity).nextStartedActivity != null) { /* discard earlier browser intents */ }
        compose.onNodeWithTag("detail-citation-0").performClick()
        assertEquals(url, shadowOf(compose.activity).nextStartedActivity.dataString)

        compose.activityRule.scenario.recreate()
        waitForTag("detail-screen")
        compose.onNodeWithTag("detail-tab-overview").performScrollTo().performClick()
        waitForTag("detail-brief-answer")
        compose.onNodeWithTag("detail-brief-answer").performScrollTo()
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals(1, app.asked.size)
        assertEquals(requestsBeforeSearch + 3, state().requestsToday)
        assertEquals(suggestionsBeforeDetail + 1, app.suggestedScopes.size)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForTag("search-query")
        compose.onNodeWithTag("search-query").assertTextContains("Useful open-source tools")
        compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-item-${item.id}"))
        compose.onNodeWithTag("search-item-${item.id}").assertIsDisplayed()
        assertEquals(1, app.searched.size)
    }

    @Test fun storedPlanSessionRecoversFromCatalogFailureWithoutBrowserOrAnotherSignIn() {
        app.chatGptAuth.restoreSavedAccount(includeModels = false)
        app.chatGptAuth.modelFailure = "chatgpt_models"
        openConnectionFromOnboarding()
        compose.onNodeWithTag("chatgpt-retry-account").performClick()
        waitForText(text(R.string.chatgpt_models_empty))
        assertEquals(listOf(UiFixtureChatGptAuth.ACCOUNT_ID), app.chatGptAuth.modelsCalls)
        assertEquals(0, app.checked.size)
        assertEquals(0, state().requestsToday)
        assertEquals("direct", state().connection.mode)
        assertTrue(app.chatGptAuth.state.value.accounts.single().connected)
        compose.onNodeWithText(text(R.string.chatgpt_error_security)).assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-sign-in").assertDoesNotExist()
        assertTrue(app.chatGptAuth.signInRequests.isEmpty())
        assertNull(shadowOf(compose.activity).nextStartedActivity)

        app.chatGptAuth.modelFailure = null
        app.checkGate = CompletableDeferred()
        compose.onNodeWithTag("chatgpt-retry-account").performClick()
        compose.waitUntil(15_000) { app.checked.size == 1 }
        compose.onNodeWithText(text(R.string.feed_connected)).assertDoesNotExist()
        assertEquals("direct", state().connection.mode)
        assertEquals(1, state().requestsToday)
        app.checkGate!!.complete(Unit)
        waitForTag("chatgpt-model-picker")
        val committed = state().connection
        assertEquals("chatgpt", committed.mode)
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, committed.accountId)
        assertEquals("fixture-quick", committed.model)
        assertEquals(listOf(committed), app.checkedConnections)
        compose.activityRule.scenario.recreate()
        waitForTag("chatgpt-model-picker")
        assertEquals(committed, state().connection)
        assertEquals(1, app.checked.size)
        assertEquals(1, state().requestsToday)
        assertTrue(app.chatGptAuth.signInRequests.isEmpty())
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertNull(app.feedSecrets.read())
    }

    @Test fun savedAccountModelDraftSurvivesRotationAndFailedCheckPreservesPreviousConnection() {
        app.chatGptAuth.restoreSavedAccount()
        val other = app.chatGptAuth.restoreSavedAccount(id = "fixture-account-OTHER")
        openConnectionFromOnboarding()
        compose.onNodeWithTag("chatgpt-retry-account").performClick()
        waitForTag("chatgpt-model-picker")
        val previous = state().connection
        assertEquals(UiFixtureChatGptAuth.ACCOUNT_ID, previous.accountId)
        assertEquals(1, app.checked.size)

        compose.onNodeWithTag("chatgpt-model-picker-${other.id}").performScrollTo().performClick()
        compose.onNodeWithText("Fixture careful model").performClick()
        compose.activityRule.scenario.recreate()
        waitForTag("chatgpt-model-picker-${other.id}")
        compose.onNodeWithTag("chatgpt-model-picker-${other.id}").assertTextContains("Fixture careful model")
        // Recreating the Activity restarts its temporary Connected snackbar. Let
        // that overlay finish before touching the account action near the bottom.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        app.checkFailure = "chatgpt_capability"
        compose.onNodeWithTag("chatgpt-use-${other.id}").assertIsEnabled().performScrollTo().performClick()
        waitForCheckedConnections(2)
        waitForText(text(R.string.chatgpt_error_capability))
        assertEquals(previous, state().connection)
        assertEquals(2, app.checkedConnections.size)
        assertEquals(other.id, app.checkedConnections.last().accountId)
        assertEquals("fixture-careful", app.checkedConnections.last().model)
        assertEquals(2, state().requestsToday)
        assertTrue(app.chatGptAuth.state.value.accounts.all { it.connected })

        app.checkFailure = null
        compose.onNodeWithTag("chatgpt-use-${other.id}").performScrollTo().performClick()
        compose.waitUntil(15_000) { state().connection.accountId == other.id }
        compose.onNodeWithTag("chatgpt-model-picker").assertTextContains("Fixture careful model")
        assertEquals("fixture-careful", state().connection.model)
        assertEquals(3, app.checkedConnections.size)
        assertEquals(3, state().requestsToday)
        assertTrue(app.chatGptAuth.signInRequests.isEmpty())
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertNull(app.feedSecrets.read())
    }

    private fun openConnectionFromOnboarding() {
        compose.onNodeWithTag("setup099-continue-public").performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("chatgpt-retry-account")
    }

    private fun state() = runBlocking { app.feedRepository.current() }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForCheckedConnections(count: Int) {
        try {
            compose.waitUntil(15_000) { app.checkedConnections.size >= count }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected $count connection checks; got ${app.checkedConnections.size}. " +
                "Current connection: ${state().connection}.\n" + compose.onRoot().printToString(), timeout)
        }
    }

    /** Wait for the actual native Compose dialog as well as its confirmation semantics before tapping. */
    private fun acknowledgeChatGptPlanNotice() {
        val accountId = state().connection.accountId
        val feedVm = androidx.lifecycle.ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        val acknowledged = app.chatGptAuth.state.value.accounts
            .firstOrNull { it.id == accountId }?.planNoticeAcknowledged == true
        if (acknowledged) return
        compose.waitUntil(10_000) {
            val current = feedVm.ui.value
            val active = current.chatGpt.accounts.firstOrNull { it.id == accountId }
            val noticeShouldBeShown = current.connected && current.state?.connection?.mode == "chatgpt" &&
                active?.connected == true && active.planEnabled && !active.planNoticeAcknowledged
            !noticeShouldBeShown || compose.onAllNodesWithTag("chatgpt-plan-got-it").fetchSemanticsNodes().isNotEmpty()
        }
        val current = feedVm.ui.value
        val active = current.chatGpt.accounts.firstOrNull { it.id == accountId }
        val noticeShouldBeShown = current.connected && current.state?.connection?.mode == "chatgpt" &&
            active?.connected == true && active.planEnabled && !active.planNoticeAcknowledged
        if (!noticeShouldBeShown) return
        val planDialog = checkNotNull(ShadowDialog.getLatestDialog()) { "Expected the ChatGPT plan notice window" }
        assertTrue("The ChatGPT plan notice must be the current visible dialog", planDialog.isShowing)
        compose.onNodeWithTag("chatgpt-plan-got-it").assertIsDisplayed().performClick()
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                feedVm.ui.value.chatGpt.accounts.firstOrNull { it.id == accountId }?.planNoticeAcknowledged == true
            }
        } catch (timeout: ComposeTimeoutException) {
            val rawAcknowledged = app.chatGptAuth.state.value.accounts
                .firstOrNull { it.id == accountId }?.planNoticeAcknowledged
            val viewModel = feedVm.ui.value
            val uiAcknowledged = viewModel.chatGpt.accounts.firstOrNull { it.id == accountId }?.planNoticeAcknowledged
            throw AssertionError(
                "ChatGPT plan acknowledgement did not reach FeedViewModel for account=$accountId; " +
                    "authState=$rawAcknowledged, viewModelState=$uiAcknowledged, " +
                    "currentAccount=${viewModel.state?.connection?.accountId}, connected=${viewModel.connected}.",
                timeout,
            )
        }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !planDialog.isShowing && compose.onAllNodesWithTag("chatgpt-plan-got-it").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun latestDialogDescription(): String = ShadowDialog.getLatestDialog()?.let { dialog ->
        "${dialog.javaClass.simpleName}(showing=${dialog.isShowing})"
    } ?: "none"

    /** Failure-only activity/dialog PixelCopy so window occlusion can be reviewed alongside semantics. */
    private fun captureDiagnosticWindows(name: String): String = runCatching<String> {
        compose.waitForIdle()
        val activityWindow = compose.activity.window
        val activityPixels = copyWindowPixels(activityWindow)
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        if (dialog?.window != null) {
            val overlay = copyWindowPixels(dialog.window!!)
            val location = IntArray(2)
            dialog.window!!.decorView.getLocationOnScreen(location)
            Canvas(activityPixels).drawBitmap(overlay, location[0].toFloat(), location[1].toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            overlay.recycle()
        }
        val file = File(compose.activity.cacheDir, "setup099-$name.png")
        file.outputStream().use { check(activityPixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        activityPixels.recycle()
        file.absolutePath
    }.getOrElse { "capture unavailable (${it.javaClass.simpleName})" }

    private fun copyWindowPixels(window: android.view.Window): Bitmap {
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnIdle { PixelCopy.request(window, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(2_000) { status != -1 }
        check(status == PixelCopy.SUCCESS) { "PixelCopy returned status $status" }
        return bitmap
    }

    /** Scrolls a real target toward the viewport center; scroll actions clamp naturally at bounds. */
    private fun centerNodeInScrollViewport(viewportTag: String, targetTag: String) {
        val viewportNode = compose.onNodeWithTag(viewportTag)
        val viewport = viewportNode.fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag(targetTag).fetchSemanticsNode().boundsInRoot
        val deltaY = ((target.top + target.bottom) - (viewport.top + viewport.bottom)) / 2f
        if (kotlin.math.abs(deltaY) > 1f) {
            viewportNode.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, deltaY) }
        }
        compose.waitForIdle()
        val moved = compose.onNodeWithTag(targetTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$targetTag should be fully inside $viewportTag after controlled scrolling: " +
            "target=${moved.top}..${moved.bottom}, viewport=${viewport.top}..${viewport.bottom}",
            moved.top >= viewport.top && moved.bottom <= viewport.bottom)
    }
}

/** No network or Android Keystore operations. All values below are synthetic test fixtures. */
open class ChatGptJourneyApplication : MeBotApplication() {
    override fun onCreate() { super.onCreate(); androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    override val chatGptAuth by lazy { UiFixtureChatGptAuth(this) }
    override val feedSecrets by lazy { TestVault(this) }
    val checked = CopyOnWriteArrayList<String>()
    val checkedConnections = CopyOnWriteArrayList<AiConnection>()
    val providerDiscoveries = CopyOnWriteArrayList<String>()
    val asked = CopyOnWriteArrayList<String>()
    val askedQuestions = CopyOnWriteArrayList<String>()
    val askedContexts = CopyOnWriteArrayList<List<ContextEntry>>()
    val searched = CopyOnWriteArrayList<String>()
    val suggestedScopes = CopyOnWriteArrayList<String>()
    val suggestedQuestionSets = CopyOnWriteArrayList<List<String>>()
    val suggestionProviderStates = CopyOnWriteArrayList<FeedState>()
    val suggestedAttachments = CopyOnWriteArrayList<List<AiAttachment>>()
    @Volatile var suggestionQuestions: List<String> = listOf(
        "What evidence in this item most strongly supports its main claim?",
        "Which assumption would change the conclusion if it proved false?",
        "What practical trade-off matters most before using this approach?",
        "How does this option compare with the closest alternative described here?",
        "What unresolved detail would be most useful to investigate next?",
    )
    @Volatile var suggestionGate: CompletableDeferred<Unit>? = null
    @Volatile var onSuggestionRequested: (() -> Unit)? = null
    @Volatile var searchItems: List<FeedItem> = emptyList()
    @Volatile var checkGate: CompletableDeferred<Unit>? = null
    @Volatile var checkFailure: String? = null
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets,
            sources = object : SourceReader {
                override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = emptyList<FeedItem>()
            },
            provider = object : AiProvider {
                override suspend fun check(connection: AiConnection, secret: String) {
                    checked += "${connection.mode}:$secret"
                    checkedConnections += connection
                    checkGate?.await()
                    checkFailure?.let { throw FeedFailure(it) }
                }
                override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String): List<FeedItem> {
                    providerDiscoveries += module.id
                    return emptyList()
                }
                override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
                    asked += "${state.connection.mode}:$secret"
                    askedQuestions += question
                    askedContexts += state.context
                    return AiAnswer(ANSWER, item?.citations.orEmpty())
                }
                override suspend fun suggest(
                    state: FeedState,
                    item: FeedItem?,
                    conversation: AiConversation?,
                    module: FeedModule?,
                    secret: String,
                    language: String,
                ): List<String> {
                    onSuggestionRequested?.invoke()
                    suggestionGate?.await()
                    suggestedScopes += listOfNotNull(item?.id, conversation?.id, module?.id).joinToString(":").ifBlank { "general" }
                    suggestionProviderStates += state
                    suggestedAttachments += conversation?.attachments.orEmpty()
                    return suggestionQuestions.toList().also { suggestedQuestionSets += it }
                }
                override suspend fun search(state: FeedState, modules: List<FeedModule>, query: String, secret: String, language: String): List<FeedItem> {
                    searched += "${state.connection.mode}:$secret:$query"
                    return searchItems.filter { item -> modules.any { it.id == item.moduleId } }
                }
            }, chatGpt = chatGptAuth)
    }
    companion object { const val ANSWER = "This fixture answer confirms the subscription connection was used." }
}

class UiFixtureChatGptAuth(context: Context) : ChatGptAuthManager(context) {
    private val mutable = MutableStateFlow(ChatGptAuthState())
    override val state: StateFlow<ChatGptAuthState> = mutable
    val signInRequests = CopyOnWriteArrayList<String?>()
    val modelsCalls = CopyOnWriteArrayList<String>()
    @Volatile var modelFailure: String? = null
    @Volatile private var pending: CompletableDeferred<ChatGptAccount>? = null
    override suspend fun initialize() = Unit
    override suspend fun beginSignIn(accountId: String?): String {
        cancelSignIn()
        signInRequests += accountId
        pending = CompletableDeferred()
        mutable.update { it.copy(phase = "waiting") }
        return "https://auth.openai.com/api/accounts/authorize?client_id=fixture-client&state=fixture-state"
    }
    override suspend fun awaitSignIn(): ChatGptAccount = requireNotNull(pending).await()
    override fun cancelSignIn() {
        pending?.cancel()
        pending = null
        mutable.update { it.copy(phase = "idle") }
    }
    fun completeSignIn() {
        val account = ChatGptAccount(ACCOUNT_ID, "Example account · TEST01", "fixture@example.com", connected = true, planEnabled = true,
            models = listOf(ChatGptModel("fixture-quick", "Fixture quick model"), ChatGptModel("fixture-careful", "Fixture careful model")))
        mutable.update { it.copy(accounts = listOf(account), phase = "idle") }
        requireNotNull(pending).complete(account)
    }
    fun restoreSavedAccount(id: String = ACCOUNT_ID, includeModels: Boolean = true): ChatGptAccount {
        val account = ChatGptAccount(id, "Example saved account · ${id.takeLast(6)}", "fixture@example.com", connected = true, planEnabled = true,
            models = if (includeModels) availableModels else emptyList(), planNoticeAcknowledged = true)
        mutable.update { it.copy(accounts = it.accounts.filterNot { a -> a.id == id } + account) }
        return account
    }
    override suspend fun models(id: String, refresh: Boolean): List<ChatGptModel> {
        modelsCalls += id
        modelFailure?.let { code ->
            val diagnostic = ChatGptDiagnostic("models", 200, "req_fixture_catalog", "other_json")
            mutable.update { it.copy(lastDiagnostic = diagnostic) }
            throw ChatGptAuthException(code, diagnostic)
        }
        require(mutable.value.accounts.any { it.id == id })
        mutable.update { it.copy(accounts = it.accounts.map { a -> if (a.id == id) a.copy(models = availableModels) else a }, lastDiagnostic = null) }
        return availableModels
    }
    override suspend fun accessToken(id: String): String {
        check(mutable.value.accounts.single { it.id == id }.connected)
        return "subscription-fixture-token"
    }
    override suspend fun activateAccount(id: String) { mutable.update { it.copy(activeAccountId = id) } }
    override suspend fun deactivateAccount() { mutable.update { it.copy(activeAccountId = null) } }
    override suspend fun acknowledgePlanNotice(id: String) {
        mutable.update { it.copy(accounts = it.accounts.map { a -> if (a.id == id) a.copy(planNoticeAcknowledged = true) else a }) }
    }
    override suspend fun invalidateSession(id: String, expectedAccessToken: String?): Boolean {
        mutable.update { it.copy(accounts = it.accounts.map { a -> if (a.id == id) a.copy(connected = false) else a }) }
        return true
    }
    override suspend fun signOut(id: String): Boolean { invalidateSession(id, null); return true }
    override suspend fun clearAll() { cancelSignIn(); mutable.value = ChatGptAuthState(); signInRequests.clear(); modelsCalls.clear(); modelFailure = null }
    companion object {
        const val ACCOUNT_ID = "fixture-account-TEST01"
        private val availableModels = listOf(ChatGptModel("fixture-quick", "Fixture quick model"), ChatGptModel("fixture-careful", "Fixture careful model"))
    }
}
