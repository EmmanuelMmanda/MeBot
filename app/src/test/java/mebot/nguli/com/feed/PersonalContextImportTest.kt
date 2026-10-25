package mebot.nguli.com.feed

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.alarm.AlarmStore
import mebot.nguli.com.core.*
import mebot.nguli.com.ui.ChatGptJourneyApplication
import mebot.nguli.com.ui.UiFixtureChatGptAuth
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.shadows.ShadowAlarmManager

/** Real file-URI import through ContentResolver, ViewModel and durable stores; all data is synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ChatGptJourneyApplication::class)
class PersonalContextImportTest {
    private lateinit var app: ChatGptJourneyApplication
    private lateinit var viewModels: ViewModelStore
    private lateinit var viewModel: FeedViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.chatGptAuth.clearAll()
        app.chatGptAuth.restoreSavedAccount()
        app.chatGptAuth.activateAccount(UiFixtureChatGptAuth.ACCOUNT_ID)
        app.feedSecrets.write("existing-synthetic-api-credential")
        app.feedRepository.update { existingFeed() }
        app.repository.update { AppState(
            profile = Profile(name = "Local utility profile", intention = "Keep existing choices", onboardingComplete = true),
            settings = Settings(language = "sw", theme = "dark", defaultFocusMinutes = 25),
            alarms = listOf(AlarmPlan(id = "existing-math-alarm", label = "Existing alarm", hour = 6, minute = 25,
                dismissMode = AlarmDismissMode.MATH, mathQuestionCount = 4, maxSnoozes = 2)),
        ) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app.alarms.sync(app.repository.current().alarms)
        openViewModel()
    }

    @After fun tearDown() {
        if (::viewModels.isInitialized) viewModels.clear()
        if (::app.isInitialized) app.alarms.clearAll()
        Dispatchers.resetMain()
    }

    @Test fun contextOnlyImportPreservesConfigurationCredentialsSavedContentAndScheduledAlarms() = runBlocking {
        val before = app.feedRepository.current()
        val utilities = app.repository.current()
        val scheduled = AlarmStore(app).scheduled
        val auth = app.chatGptAuth.state.value
        val credential = app.feedSecrets.read()
        val context = PersonalContextImport(
            name = "Fixture reader",
            region = "Example region",
            timezone = "Europe/London",
            entries = listOf(
                ContextEntry(id = "new-learning-note", text = "Learn accessible mobile interfaces", category = "learning",
                    scopes = setOf("learning", "technology"), confirmed = true, updatedAtMillis = 100),
                ContextEntry(id = "unconfirmed-note", text = "A preference still awaiting review", category = "interest",
                    scopes = setOf("music"), confirmed = false, updatedAtMillis = 101),
            ),
            modules = emptyList(),
        )

        val result = importText(FeedJson.encodeToString(context))
        assertEquals("imported", result.notice)
        assertNull(result.error)
        val after = app.feedRepository.current()
        assertEquals(context.name, after.name)
        assertEquals(context.region, after.region)
        assertEquals(context.timezone, after.timezone)
        assertEquals(context.entries.map { it.copy(origin = "imported") }, after.context)
        assertEquals(before.modules, after.modules)
        assertEquals(before.savedIds, after.savedIds)
        assertEquals(before.hiddenIds, after.hiddenIds)
        assertEquals(before.connection, after.connection)
        assertEquals(before.items.map { it.copy(why = "", relevance = 0) }, after.items)
        assertEquals(0L, after.lastRefreshMillis)
        assertEquals(0L, after.lastAiRefreshMillis)
        // All fields outside imported personalization and its stale ranking stay intact.
        assertEquals(before, after.copy(name = before.name, region = before.region, timezone = before.timezone,
            context = before.context, items = before.items, lastRefreshMillis = before.lastRefreshMillis,
            lastAiRefreshMillis = before.lastAiRefreshMillis))
        assertEquals(utilities, app.repository.current())
        assertEquals(scheduled, AlarmStore(app).scheduled)
        assertEquals(utilities.alarms, AlarmStore(app).plans)
        assertEquals(auth, app.chatGptAuth.state.value)
        assertEquals(credential, app.feedSecrets.read())
        assertEquals("subscription-fixture-token", app.chatGptAuth.accessToken(before.connection.accountId))
        assertTrue(app.checked.isEmpty())
        assertTrue(app.asked.isEmpty())

        // The actual stored file and a recreated ViewModel agree after import.
        assertEquals(after, FeedJson.decodeFromString<FeedState>(File(app.filesDir, "mebot/feed.json").readText()))
        viewModels.clear()
        openViewModel()
        assertEquals(after, viewModel.ui.value.state)
        assertTrue(viewModel.ui.value.connected)
        assertEquals(scheduled, AlarmStore(app).scheduled)
    }

    @Test fun malformedOrInvalidContextCannotPartiallyReplaceExistingData() = runBlocking {
        val question = "Explain the saved fixture"
        viewModel.ask(null, question)
        val answered = withTimeout(10_000) { viewModel.ui.first { it.answer != null && !it.asking } }
        assertEquals(ChatGptJourneyApplication.ANSWER, answered.answer)
        val before = app.feedRepository.current()
        val utilities = app.repository.current()
        val scheduled = AlarmStore(app).scheduled
        val auth = app.chatGptAuth.state.value
        val bytes = File(app.filesDir, "mebot/feed.json").readBytes()
        val invalidInputs = listOf(
            "{\"format\":\"mebot-context-v1\",\"name\":\"Unexpected replacement\",\"unknownField\":true}",
            FeedJson.encodeToString(PersonalContextImport(format = "unsupported-context-version", name = "Unexpected replacement")),
            FeedJson.encodeToString(PersonalContextImport(name = "Unexpected replacement", entries = listOf(
                ContextEntry(id = "invalid-scope", text = "Must not be saved", scopes = setOf("unknown-module"), updatedAtMillis = 100),
            ))),
        )
        invalidInputs.forEach { input ->
            val result = importText(input)
            assertNotNull(result.error)
            assertNull(result.notice)
            assertEquals(before, app.feedRepository.current())
            assertArrayEquals(bytes, File(app.filesDir, "mebot/feed.json").readBytes())
            assertEquals(utilities, app.repository.current())
            assertEquals(scheduled, AlarmStore(app).scheduled)
            assertEquals(auth, app.chatGptAuth.state.value)
            assertEquals("existing-synthetic-api-credential", app.feedSecrets.read())
            assertTrue(viewModel.ui.value.connected)
            assertEquals(answered.answer, viewModel.ui.value.answer)
            assertEquals(question, viewModel.ui.value.question)
        }
        assertTrue(app.checked.isEmpty())
        assertEquals(listOf(question), app.askedQuestions)
    }

    @Test fun pastedContextRequiresReviewAndPreservesExistingModulesAndCredentials() = runBlocking {
        val before = app.feedRepository.current()
        val imported = PersonalContextImport(name = "Pasted profile", timezone = "UTC", entries = listOf(
            ContextEntry(id = "pasted-note", text = "Prefer practical accessibility resources", scopes = setOf("learning"))))
        val text = "```json\n${FeedJson.encodeToString(imported)}\n```"
        viewModel.prepareContextText(text)
        withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.importPreview != null } }
        assertEquals(before, app.feedRepository.current())
        assertEquals(1, viewModel.ui.value.importPreview!!.entries)
        viewModel.cancelImport()
        assertNull(viewModel.ui.value.importPreview)
        assertEquals(before, app.feedRepository.current())

        viewModel.prepareContextText(text)
        withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.importPreview != null } }
        viewModel.confirmImport()
        withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.importSummary == "context" } }
        val after = app.feedRepository.current()
        assertEquals(imported.name, after.name)
        assertEquals(imported.entries.map { it.copy(origin = "imported") }, after.context)
        assertEquals(before.modules, after.modules)
        assertEquals(before.savedIds, after.savedIds)
        assertEquals(before.connection, after.connection)
        assertEquals("existing-synthetic-api-credential", app.feedSecrets.read())
        assertTrue(app.asked.isEmpty())
    }

    @Test fun invalidPastesAndCancelledPreviewsCannotChangeDurableData() = runBlocking {
        val before = app.feedRepository.current()
        for (text in listOf("not JSON", "x".repeat(512 * 1024 + 1))) {
            viewModel.prepareContextText(text)
            withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.error != null } }
            assertNull(viewModel.ui.value.importPreview)
            assertEquals(before, app.feedRepository.current())
        }
        viewModel.prepareContextText(FeedJson.encodeToString(PersonalContextImport(name = "Old preview")))
        viewModel.cancelImport()
        viewModel.prepareContextText(FeedJson.encodeToString(PersonalContextImport(name = "Current preview", entries = listOf(
            ContextEntry(id = "current-note", text = "A reviewed new preference", scopes = setOf("learning"))))))
        withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.importPreview?.entries == 1 } }
        viewModel.confirmImport()
        withTimeout(10_000) { viewModel.ui.first { !it.importWorking && it.importSummary == "context" } }
        assertEquals("Current preview", app.feedRepository.current().name)
        assertEquals(before.modules, app.feedRepository.current().modules)
    }

    private suspend fun openViewModel() {
        viewModels = ViewModelStore()
        viewModel = ViewModelProvider(viewModels, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { viewModel.ui.first { !it.loading } }
    }

    private suspend fun importText(text: String): FeedUiState {
        viewModel.clearMessage()
        val file = File(app.cacheDir, "synthetic-context-import.json").apply { writeText(text) }
        viewModel.importContext(Uri.fromFile(file))
        return withTimeout(10_000) { viewModel.ui.first { it.notice == "imported" || it.error != null } }
    }

    private fun existingFeed() = FeedState(
        onboardingComplete = true, personalSeedApplied = true,
        name = "Original reader", region = "Original region", timezone = "UTC",
        modules = ModuleCatalog.defaults.map { module -> when (module.kind) {
            "news" -> module.copy(title = "My existing news", query = "Verified transport changes", enabled = true,
                sources = listOf(FeedSource("Existing source", "https://example.com/transport.xml")), alerts = false)
            "music" -> module.copy(title = "My paused music", query = "Instrumental recordings", enabled = false)
            else -> module
        } } + FeedModule(id = "custom-watch", kind = "watch", title = "Existing custom module", query = "Accessible interface research",
            sources = listOf(FeedSource("Custom source", "https://example.com/custom.xml")), alerts = true),
        context = listOf(ContextEntry(id = "old-context", text = "An old preference", scopes = setOf("news"), updatedAtMillis = 1)),
        items = listOf(
            FeedItem(id = "saved-item", moduleId = "news", title = "Saved fixture", summary = "A cached article to retain.",
                sourceName = "Example source", sourceUrl = "https://example.com/saved", why = "An old explanation", relevance = 89,
                fetchedAtMillis = 100, media = FeedMedia("https://example.com/image.jpg", "https://example.com/saved", "Example credit")),
            FeedItem(id = "hidden-item", moduleId = "custom-watch", kind = "watch", title = "Hidden fixture", summary = "Another cached article.",
                sourceUrl = "https://example.com/hidden", fetchedAtMillis = 101),
        ),
        savedIds = setOf("saved-item"), hiddenIds = setOf("hidden-item"),
        connection = AiConnection(mode = "chatgpt", model = "fixture-careful", accountId = UiFixtureChatGptAuth.ACCOUNT_ID),
        lastRefreshMillis = 100, lastAiRefreshMillis = 99, notifications = true, quietStartHour = 21, quietEndHour = 6,
        refreshHours = 4, dailyRequestLimit = 20, usageDate = "2026-01-01", requestsToday = 3,
    )
}
