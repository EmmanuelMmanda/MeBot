package app.mynguli.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mynguli.MainActivity
import app.mynguli.MyNguliApplication
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.*
import app.mynguli.feed.auth.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        waitForTag("onboarding-screen")
    }

    @Test fun browserSignInVerifiedPlanAndAskStayConnectedAcrossRotationAndCancelledAccountAddition() {
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
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
        compose.onNodeWithTag("chatgpt-plan-got-it").performClick()
        compose.waitUntil(15_000) { app.chatGptAuth.state.value.accounts.single().planNoticeAcknowledged }
        compose.onNodeWithTag("chatgpt-model-picker").assertTextContains("Fixture quick model")
        compose.activityRule.scenario.recreate()
        waitForTag("chatgpt-model-picker")
        compose.onNodeWithTag("chatgpt-plan-got-it").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(1, app.chatGptAuth.signInRequests.size)
        assertEquals(connection, state().connection)
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()

        waitForText(text(R.string.feed_you_title))
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("edition-ask").performScrollTo().performClick()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-model").assertTextContains("Fixture quick model")
        compose.waitUntil(15_000) { state().conversations.any { it.turns.lastOrNull()?.role == "assistant" } }
        val conversation = state().conversations.single()
        assertEquals(text(R.string.edition_ask_today_prompt), conversation.turns.first().text)
        assertEquals(ChatGptJourneyApplication.ANSWER, conversation.turns.last().text)
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(ChatGptJourneyApplication.ANSWER))
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals(listOf("chatgpt:subscription-fixture-token"), app.asked)
        assertEquals(listOf(text(R.string.edition_ask_today_prompt)), app.askedQuestions)
        assertTrue(app.askedContexts.single().isEmpty())
        val requestsAfterAnswer = state().requestsToday
        compose.activityRule.scenario.recreate()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText(ChatGptJourneyApplication.ANSWER))
        compose.onNodeWithText(ChatGptJourneyApplication.ANSWER).assertIsDisplayed()
        assertEquals(conversation, state().conversations.single())
        assertEquals(requestsAfterAnswer, state().requestsToday)

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
        compose.onNodeWithTag("connection-continue").performScrollTo().performClick()
        waitForText(text(R.string.feed_you_title))
        compose.waitUntil(15_000) { state().lastAiRefreshMillis > 0 }
        val approved = ContextEntry(id = "search-interest", text = "Useful open-source tools", scopes = setOf("news"))
        val unshared = ContextEntry(id = "private-interest", text = "An unrelated music interest", scopes = setOf("music"))
        val url = "https://example.com/discovered-tool"
        val item = FeedItem("search-tool", "news", "A discovered open-source tool", "A synthetic source-backed search result.",
            sourceName = "Example documentation", sourceUrl = url, citations = listOf(FeedCitation("Example documentation", url)))
        runBlocking { app.feedRepository.update { it.copy(context = listOf(approved, unshared)) } }
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
        compose.onNodeWithTag("search-item-${item.id}").performClick()
        waitForTag("detail-screen")
        compose.waitUntil(15_000) { state().briefs.any { it.itemId == item.id } }
        compose.onNodeWithTag("detail-brief-answer").performScrollTo().assertTextEquals(ChatGptJourneyApplication.ANSWER)
        assertEquals(requestsBeforeSearch + 2, state().requestsToday)
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
        compose.onNodeWithTag("detail-brief-answer").performScrollTo().assertTextEquals(ChatGptJourneyApplication.ANSWER)
        assertEquals(1, app.asked.size)
        assertEquals(requestsBeforeSearch + 2, state().requestsToday)
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
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
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
}

/** No network or Android Keystore operations. All values below are synthetic test fixtures. */
class ChatGptJourneyApplication : MyNguliApplication() {
    override val chatGptAuth by lazy { UiFixtureChatGptAuth(this) }
    override val feedSecrets by lazy { TestVault(this) }
    val checked = CopyOnWriteArrayList<String>()
    val checkedConnections = CopyOnWriteArrayList<AiConnection>()
    val asked = CopyOnWriteArrayList<String>()
    val askedQuestions = CopyOnWriteArrayList<String>()
    val askedContexts = CopyOnWriteArrayList<List<ContextEntry>>()
    val searched = CopyOnWriteArrayList<String>()
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
                override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
                override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
                    asked += "${state.connection.mode}:$secret"
                    askedQuestions += question
                    askedContexts += state.context
                    return AiAnswer(ANSWER, item?.citations.orEmpty())
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
