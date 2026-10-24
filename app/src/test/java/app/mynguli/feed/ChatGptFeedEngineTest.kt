package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.*
import app.mynguli.feed.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatGptFeedEngineTest {
    @get:Rule val directory = TemporaryFolder()
    private val jobs = SupervisorJob()
    private lateinit var repository: FeedRepository
    private lateinit var vault: TestVault
    private lateinit var auth: TestAuth
    private val provider = RecordingProvider()
    private val news = FeedModule(id = "news", kind = "news")

    @Before fun setup(): Unit = runBlocking {
        val files = directory.newFolder("files")
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getFilesDir(): File = files
        }
        repository = FeedRepository(context, CoroutineScope(Dispatchers.IO + jobs))
        vault = TestVault(context).apply { value = "synthetic-api-key" }
        auth = TestAuth(context)
        repository.update { it.copy(modules = listOf(news)) }
    }

    @After fun close() = runBlocking { jobs.cancelAndJoin() }

    @Test fun completedConnectionCheckCommitsDiscoveredAccountAndCountsOneAttempt() = runBlocking {
        engine().connectChatGpt("account-a")
        val state = repository.current()
        assertEquals(AiConnection("chatgpt", "catalog/model:one", accountId = "account-a"), state.connection)
        assertEquals(1, state.requestsToday)
        assertEquals(listOf("synthetic-subscription-a"), provider.checkSecrets)
        assertEquals("synthetic-api-key", vault.value)
    }

    @Test fun failedAccountCheckKeepsPreviousConnectionAndItsKey() = runBlocking {
        val previous = repository.current().connection
        provider.checkAction = { throw FeedFailure("chatgpt_limit") }
        val error = runCatching { engine().connectChatGpt("account-a") }.exceptionOrNull()
        assertEquals("chatgpt_limit", (error as FeedFailure).code)
        assertEquals(previous, repository.current().connection)
        assertEquals("synthetic-api-key", vault.value)
    }

    @Test fun inferenceFailureKeepsTheSavedAccountAndExposesOnlySafeConnectionDetails() = runBlocking {
        val diagnostic = ChatGptDiagnostic("inference", 200, "req_fixture", "completed_without_output")
        provider.checkAction = { throw FeedFailure("invalid_response", diagnostic) }
        val previous = repository.current().connection
        val error = runCatching { engine().connectChatGpt("account-a") }.exceptionOrNull()
        assertEquals("invalid_response", (error as FeedFailure).code)
        assertEquals(diagnostic, auth.state.value.lastDiagnostic)
        assertTrue(auth.state.value.accounts.first { it.id == "account-a" }.connected)
        assertEquals(previous, repository.current().connection)
        assertTrue(auth.invalidated.isEmpty())
    }

    @Test fun modelOutsideAccountCatalogIsRejectedBeforeInferenceOrUsage() = runBlocking {
        val error = runCatching { engine().connectChatGpt("account-a", "not-in-this-account") }.exceptionOrNull()
        assertEquals("chatgpt_models", (error as FeedFailure).code)
        assertEquals(0, repository.current().requestsToday)
        assertTrue(provider.checkSecrets.isEmpty())
    }

    @Test fun cancelledConnectionCannotReplaceAnExistingSelection() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        provider.checkAction = { entered.complete(Unit); awaitCancellation() }
        val previous = repository.current().connection
        val task = launch { engine().connectChatGpt("account-a") }
        withTimeout(5000) { entered.await() }
        task.cancelAndJoin()
        assertEquals(previous, repository.current().connection)
        assertEquals("synthetic-api-key", vault.value)
    }

    @Test fun selectedSubscriptionNeverUsesAnExistingApiKey() = runBlocking {
        select()
        engine().ask(null, "Explain this update")
        assertEquals(listOf("synthetic-subscription-a"), provider.askSecrets)
        assertEquals("synthetic-api-key", vault.value)
    }

    @Test fun expiredSubscriptionDoesNotFallBackToStoredApiKey() = runBlocking {
        select()
        auth.tokenError = "chatgpt_reauth"
        val error = runCatching { engine().ask(null, "Explain") }.exceptionOrNull()
        assertEquals("chatgpt_reauth", (error as FeedFailure).code)
        assertTrue(provider.askSecrets.isEmpty())
        assertEquals(0, repository.current().requestsToday)
    }

    @Test fun planLimitPausesAutomaticAiWhilePublicSourcesContinue() = runBlocking {
        select()
        val withSource = news.copy(sources = listOf(FeedSource("Fixture", "https://example.com/rss")))
        repository.update { it.copy(modules = listOf(withSource)) }
        provider.askAction = { throw FeedFailure("chatgpt_limit") }
        val engine = engine()
        runCatching { engine.ask(null, "Explain") }
        assertEquals("chatgpt_limit", repository.current().aiPauseCode)
        val result = engine.refresh()
        assertEquals("chatgpt_limit", result.error)
        assertEquals(listOf("public-story"), repository.current().items.map { it.id })
        assertTrue(provider.feedSecrets.isEmpty())
        assertEquals(1, repository.current().requestsToday)
        engine.refresh(allowPlanRetry = true)
        assertEquals("", repository.current().aiPauseCode)
        assertEquals(listOf("synthetic-subscription-a"), provider.feedSecrets)
    }

    @Test fun staleFeedFromAnotherAccountCannotCommitAfterASwitch() = runBlocking {
        select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.feedAction = { entered.complete(Unit); release.await(); listOf(article("obsolete")) }
        val engine = engine()
        val task = async { engine.refresh() }
        withTimeout(5000) { entered.await() }
        engine.connectChatGpt("account-b")
        release.complete(Unit)
        task.await()
        assertEquals("account-b", repository.current().connection.accountId)
        assertFalse(repository.current().items.any { it.id == "obsolete" })
        assertEquals(0L, repository.current().lastAiRefreshMillis)
    }

    @Test fun staleUnauthorizedResponseCannotDisconnectANewSelection() = runBlocking {
        select()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.askAction = { entered.complete(Unit); release.await(); throw FeedFailure("chatgpt_reauth") }
        val engine = engine()
        val task = async { runCatching { engine.ask(null, "Explain") } }
        withTimeout(5000) { entered.await() }
        engine.connectChatGpt("account-b")
        release.complete(Unit)
        task.await()
        assertEquals("account-b", repository.current().connection.accountId)
        assertTrue(auth.invalidated.isEmpty())
        assertEquals("", repository.current().aiPauseCode)
    }

    @Test fun disconnectingSubscriptionKeepsApiCredentialInactiveAndPreservesSavedReading() = runBlocking {
        select()
        repository.update { it.copy(items = listOf(article("saved")), savedIds = setOf("saved")) }
        val engine = engine()
        engine.disconnect()
        assertEquals(listOf("account-a"), auth.signedOut)
        assertEquals("chatgpt", repository.current().connection.mode)
        assertEquals("synthetic-api-key", vault.value)
        assertEquals(setOf("saved"), repository.current().savedIds)
        runCatching { engine.ask(null, "Explain") }
        assertTrue(provider.askSecrets.isEmpty())
    }

    @Test fun resetClearsAllCredentialKinds() = runBlocking {
        engine().clearAllCredentials()
        assertNull(vault.value)
        assertTrue(auth.cleared)
    }

    @Test fun eachModuleObtainsACurrentSubscriptionToken() = runBlocking {
        select()
        repository.update { it.copy(modules = listOf(news, FeedModule(id = "watch", kind = "watch"))) }
        var sequence = 0
        auth.tokenOverride = { "synthetic-rotating-token-${++sequence}" }
        engine().refresh()
        assertEquals(2, provider.feedSecrets.size)
        assertEquals(2, provider.feedSecrets.distinct().size)
    }

    @Test fun staleUnauthorizedResponseAfterTokenRotationDoesNotPauseValidSession() = runBlocking {
        select()
        auth.invalidateResult = false
        provider.askAction = { throw FeedFailure("chatgpt_reauth") }
        val failure = runCatching { engine().ask(null, "Explain") }.exceptionOrNull() as FeedFailure
        assertEquals("chatgpt_interrupted", failure.code)
        assertEquals("", repository.current().aiPauseCode)
    }

    @Test fun terminalRefreshFailureKeepsTheRequiredSignInRecovery() = runBlocking {
        select()
        var calls = 0
        auth.invalidateResult = false
        auth.tokenOverride = {
            if (++calls > 1) {
                auth.state.value = auth.state.value.copy(accounts = auth.state.value.accounts.map { it.copy(connected = false) })
                throw ChatGptAuthException("chatgpt_reauth")
            }
            "synthetic-previous-token"
        }
        val result = engine().refresh()
        assertEquals("chatgpt_reauth", result.error)
        assertEquals("chatgpt_reauth", repository.current().aiPauseCode)
        assertTrue(provider.feedSecrets.isEmpty())
    }

    private suspend fun select() = repository.update { it.copy(connection = AiConnection("chatgpt", "catalog/model:one", accountId = "account-a")) }
    private fun engine() = FeedEngine(repository, vault, object : SourceReader {
        override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = listOf(article("public-story"))
    }, provider, chatGpt = auth)
    private fun article(id: String) = FeedItem(id, "news", "Fixture article", "Synthetic source content.", sourceUrl = "https://example.com/$id")

    private class TestVault(context: Context) : SecretVault(context) {
        var value: String? = null
        override fun read() = value
        override fun write(secret: String) { value = secret }
        override fun clear() { value = null }
    }

    private class TestAuth(context: Context) : ChatGptAuthManager(context) {
        override val state = MutableStateFlow(ChatGptAuthState(accounts = listOf(
            ChatGptAccount("account-a", "Fixture A", "a@example.com", true, true),
            ChatGptAccount("account-b", "Fixture B", "b@example.com", true, true),
        )))
        var tokenError: String? = null
        var tokenOverride: (() -> String)? = null
        var invalidateResult = true
        var cleared = false
        val signedOut = mutableListOf<String>()
        val invalidated = mutableListOf<String>()
        override suspend fun initialize() = Unit
        override suspend fun accessToken(id: String): String {
            tokenError?.let { throw ChatGptAuthException(it) }
            if (id in signedOut) throw ChatGptAuthException("chatgpt_reauth")
            return tokenOverride?.invoke() ?: "synthetic-subscription-${id.takeLast(1)}"
        }
        override suspend fun models(id: String, refresh: Boolean) = listOf(ChatGptModel("catalog/model:one", "Account model"))
        override suspend fun recordInferenceDiagnostic(id: String, expectedAccessToken: String, diagnostic: ChatGptDiagnostic) {
            assertEquals("synthetic-subscription-${id.takeLast(1)}", expectedAccessToken)
            state.value = state.value.copy(lastDiagnostic = diagnostic)
        }
        override suspend fun signOut(id: String): Boolean { signedOut += id; return true }
        override suspend fun invalidateSession(id: String, expectedAccessToken: String?): Boolean { invalidated += id; return invalidateResult }
        override suspend fun clearAll() { cleared = true }
    }

    private class RecordingProvider : AiProvider {
        val checkSecrets = mutableListOf<String>()
        val askSecrets = mutableListOf<String>()
        val feedSecrets = mutableListOf<String>()
        var checkAction: suspend () -> Unit = {}
        var askAction: suspend () -> AiAnswer = { AiAnswer("A completed fixture answer.") }
        var feedAction: suspend () -> List<FeedItem> = { emptyList() }
        override suspend fun check(connection: AiConnection, secret: String) { checkSecrets += secret; checkAction() }
        override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
            askSecrets += secret; return askAction()
        }
        override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String): List<FeedItem> {
            feedSecrets += secret; return feedAction()
        }
    }
}
