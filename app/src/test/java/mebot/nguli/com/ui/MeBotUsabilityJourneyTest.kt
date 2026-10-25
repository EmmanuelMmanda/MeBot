package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real navigation, persistence and refresh coordination; every external transport is a fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = MeBotUsabilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotUsabilityJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as MeBotUsabilityTestApplication

    @Before fun openExistingInstallation() {
        compose.waitForIdle()
        app.sourceItems = emptyList()
        app.sourceGate = null
        app.sourceEntered = null
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update {
                AppState(profile = it.profile.copy(onboardingComplete = true),
                    settings = it.settings.copy(language = "en", theme = "light"))
            }
            app.feedRepository.update {
                val now = System.currentTimeMillis()
                FeedState(readingPreferences = ReadingPreferences(autoLoadMore = false), onboardingComplete = true, personalSeedApplied = true,
                    timezone = "Africa/Dar_es_Salaam", modules = fixtureModules(),
                    items = stories(now), lastRefreshMillis = now)
            }
        }
        app.alarms.clearAll()
        waitForTag("feed-screen")
        compose.waitForIdle()
        app.sourceCalls.set(0)
        app.aiCalls.set(0)
    }

    @After fun releasePendingSource() { app.sourceGate?.complete(Unit) }

    @Test fun selectedFeedReturnsFromTheLastPageAndCoalescesRepeatedRefreshTaps() {
        val initialCount = state().items.size
        assertEquals(21, initialCount)
        // Exercise every local page, including the terminal page without a load-more action.
        for (expected in listOf(5, 10, 15, 20, 21)) {
            scrollFeedTo("feed-page-progress")
            compose.onNodeWithTag("feed-page-progress")
                .assertTextEquals(text(R.string.edition_page_count, expected, initialCount))
            if (expected < initialCount) compose.onNodeWithTag("feed-show-more").performClick()
        }
        compose.onNodeWithTag("feed-show-more").assertDoesNotExist()
        scrollFeedTo("edition-modules")
        compose.onNodeWithTag("feed-refresh").assertIsNotDisplayed()

        val fresh = story("fresh-update", "A new update after your last visit", System.currentTimeMillis())
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        app.sourceItems = listOf(fresh)
        app.sourceGate = gate
        app.sourceEntered = entered
        try {
            tab("feed")
            try {
                compose.waitUntil(15_000) {
                    // Synchronize Robolectric's main loop while the refresh crosses DataStore
                    // and IO dispatchers; polling only an external Deferred does not drain it.
                    compose.onAllNodesWithTag("feed-refresh").fetchSemanticsNodes()
                    entered.isCompleted
                }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Source did not start: calls=${app.sourceCalls.get()}, " +
                    "edition=${state().editionSequence}, modules=${state().modules.filter { it.enabled }.map { it.id }}\n" +
                    compose.onRoot().printToString(), failure)
            }
            compose.onNodeWithTag("feed-refresh").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("feed-search").assertIsDisplayed()
            compose.onNodeWithTag("filter-all").assertIsSelected().assertIsDisplayed()
            repeat(3) { tab("feed") }
            compose.waitForIdle()
            assertEquals("Repeated selected-tab taps share the pending refresh", 1, app.sourceCalls.get())
            assertEquals("Only the accepted manual refresh advances the edition", 1L, state().editionSequence)
        } finally {
            gate.complete(Unit)
        }

        waitForState { it.items.any { item -> item.id == fresh.id } }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("feed-refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("feed-refresh").assertIsDisplayed()
        assertEquals(1, app.sourceCalls.get())
        assertEquals(0, app.aiCalls.get())
        assertEquals(0, state().requestsToday)
        screenshot("58-mebot-feed-polish-v061")
        scrollFeedTo("feed-page-progress")
        compose.onNodeWithTag("feed-page-progress")
            .assertTextEquals(text(R.string.edition_page_count, 5, initialCount + 1))
        compose.onNodeWithTag("feed-show-more").assertIsDisplayed()
    }

    @Test fun refreshingAllKeepsTheInactiveNewsFilterLoadedToTenStories() {
        compose.onNodeWithTag("filter-news").performScrollTo().performClick()
        scrollFeedTo("feed-page-progress")
        compose.onNodeWithTag("feed-page-progress")
            .assertTextEquals(text(R.string.edition_page_count, 5, 20))
        compose.onNodeWithTag("feed-show-more").performClick()
        scrollFeedTo("feed-page-progress")
        compose.onNodeWithTag("feed-page-progress")
            .assertTextEquals(text(R.string.edition_page_count, 10, 20))

        compose.onNodeWithTag("feed-screen").performScrollToIndex(0)
        compose.onNodeWithTag("filter-all").performScrollTo().performClick()
        scrollFeedTo("edition-modules")
        tab("feed")
        compose.waitUntil(15_000) { app.sourceCalls.get() == 1 }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("feed-refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("feed-refresh").assertIsDisplayed()
        compose.onNodeWithTag("filter-all").assertIsSelected()

        compose.onNodeWithTag("filter-news").performScrollTo().performClick()
        scrollFeedTo("feed-page-progress")
        compose.onNodeWithTag("feed-page-progress")
            .assertTextEquals(text(R.string.edition_page_count, 10, 20))
        assertEquals(1, app.sourceCalls.get())
        assertEquals(0, app.aiCalls.get())
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun catalogDiscoveryFilterRestoreAndFaqContextRoundTripStayReachableOnNarrowScreens() {
        compose.onNodeWithTag("feed-all-modules").performClick()
        waitForTag("module-search")
        compose.onNodeWithText(text(R.string.modules_filter_all)).assertIsSelected()
        compose.onNodeWithTag("module-news").assertExists()
        compose.onNodeWithTag("module-weather").assertExists()
        compose.onNodeWithTag("module-search").performTextInput("weather")
        compose.onNodeWithTag("module-news").assertDoesNotExist()
        clickText(R.string.modules_filter_disabled)
        compose.onNodeWithTag("module-toggle-weather").performScrollTo().performClick()
        waitForState { it.modules.single { module -> module.id == "weather" }.enabled }
        compose.onNodeWithTag("module-weather").assertDoesNotExist()

        tab("feed")
        // Enabling a module invalidates freshness. Its first return to Feed refreshes once.
        compose.waitUntil(15_000) { app.sourceCalls.get() == 1 }
        waitForState { it.lastRefreshMillis > 0 }
        scrollFeedTo("edition-modules")
        compose.onNodeWithTag("edition-modules").performClick()
        waitForTag("module-search")
        compose.onNodeWithText(text(R.string.modules_filter_disabled)).assertIsSelected()
        compose.onNodeWithTag("module-news").assertDoesNotExist()
        compose.onNodeWithTag("module-weather").assertDoesNotExist()
        compose.onNodeWithTag("module-horoscope").assertExists()

        tab("feed")
        compose.onNodeWithTag("feed-screen").performScrollToIndex(0)
        compose.onNodeWithTag("filter-travel").performScrollTo().performClick()
        compose.onNodeWithTag("filter-travel").assertIsSelected().assertIsDisplayed()
        tab("you")
        tab("feed")
        compose.onNodeWithTag("filter-travel").assertIsSelected().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForTag("filter-travel")
        compose.onNodeWithTag("filter-travel").assertIsSelected().assertIsDisplayed()

        tab("you")
        clickText(R.string.mb_preferences)
        clickText(R.string.setup_faq_title)
        waitForTag("faq-setup")
        compose.onNodeWithTag("faq-answer-setup").assertExists()
        compose.onNodeWithTag("faq-open-context").performScrollTo().performClick()
        waitForTag("context-name")
        // Import and prompt creation are first-class actions, without an advanced-settings step.
        compose.onNodeWithTag("context-import").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("context-paste").assertExists()
        compose.onNodeWithTag("context-starter-prompt").performScrollTo().assertIsDisplayed()
        back()
        waitForTag("faq-setup")
        compose.onNodeWithTag("faq-answer-setup").assertExists()
        back()
        compose.onNodeWithText(text(R.string.settings_title)).assertExists()
        assertEquals(1, app.sourceCalls.get())
        assertEquals(0, app.aiCalls.get())
        assertEquals(0, state().requestsToday)
    }

    private fun fixtureModules() = ModuleCatalog.defaults.map { module ->
        module.copy(enabled = module.kind !in setOf("opportunities", "scholarships", "weather", "horoscope"),
            sources = if (module.id == "news") listOf(FeedSource("Example Publisher", "https://publisher.example/feed.xml")) else emptyList())
    }

    private fun stories(now: Long): List<FeedItem> = (1..20).map { number ->
        story("story-$number", "Useful update $number for your day", now - number * 60_000L)
    } + story("travel-guide", "A practical guide for a short trip", now - 30 * 60_000L)
        .copy(moduleId = "travel", kind = "travel")

    private fun story(id: String, title: String, now: Long) = FeedItem(
        id = id, moduleId = "news", title = title,
        summary = "A sourced update with a clear explanation and a useful next step for this test edition.",
        sourceName = "Example Publisher", sourceUrl = "https://publisher.example/$id",
        publishedAtMillis = now, fetchedAtMillis = now,
        citations = listOf(FeedCitation("Example Publisher", "https://publisher.example/$id")),
    )

    private fun state() = runBlocking { app.feedRepository.current() }
    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
    private fun tab(name: String) { compose.onNodeWithTag("nav-$name").performClick() }
    private fun back() { compose.onNodeWithContentDescription(text(R.string.back)).performClick() }
    private fun clickText(resource: Int) { compose.onNodeWithText(text(resource)).performScrollTo().performClick() }
    private fun scrollFeedTo(tag: String) { compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag(tag)) }
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForState(predicate: (FeedState) -> Boolean) {
        compose.waitUntil(15_000) { predicate(state()) }
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(2_000)
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

/** Isolated test-only transports; the deferred source makes refresh races deterministic. */
class MeBotUsabilityTestApplication : MeBotApplication() {
    override fun onCreate() { super.onCreate(); androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    val sourceCalls = AtomicInteger(0)
    val aiCalls = AtomicInteger(0)
    @Volatile var sourceItems: List<FeedItem> = emptyList()
    @Volatile var sourceGate: CompletableDeferred<Unit>? = null
    @Volatile var sourceEntered: CompletableDeferred<Unit>? = null
    override val feedSecrets by lazy { TestVault(this) }
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                val gate = sourceGate
                if (source.url == "https://publisher.example/feed.xml") {
                    sourceCalls.incrementAndGet()
                    sourceEntered?.complete(Unit)
                    gate?.await()
                }
                return sourceItems.filter { it.moduleId == module.id }
            }
        }, object : AiProvider {
            override suspend fun check(connection: AiConnection, secret: String) { aiCalls.incrementAndGet() }
            override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String): List<FeedItem> {
                aiCalls.incrementAndGet()
                return emptyList()
            }
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
                aiCalls.incrementAndGet()
                return AiAnswer("Fixture explanation", item?.citations.orEmpty())
            }
        }, language = { repository.current().settings.language })
    }
}
