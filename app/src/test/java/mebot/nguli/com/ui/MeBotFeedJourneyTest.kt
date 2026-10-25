package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
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
        waitForText(text(R.string.feed_saved_title))
        compose.onNodeWithTag("item-toolkit").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.feed_saved_title))
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
        waitForText(text(R.string.feed_saved_title))

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
        start()
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
        compose.onNodeWithTag("module-name").assertTextContains("Research watch")
        compose.onNodeWithTag("module-topic").assertTextContains("Open source tools for local AI")
        screenshot("25-mebot-add-watch")
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        waitForState { it.modules.any { module -> module.title == "Research watch" } }
        val module = state().modules.single { it.title == "Research watch" }
        waitForTag("module-${module.id}")
        val article = stories().first().copy(id = "watch-result", moduleId = module.id, kind = "watch")
        runBlocking { app.feedRepository.update { it.copy(items = listOf(article), savedIds = setOf(article.id)) } }
        compose.onNodeWithTag("module-toggle-${module.id}").performScrollTo().performClick()
        waitForState { it.modules.single { entry -> entry.id == module.id }.enabled.not() }
        compose.activityRule.scenario.recreate()
        waitForTag("module-toggle-${module.id}")
        compose.onNodeWithTag("module-toggle-${module.id}").performScrollTo().assertIsOff()
        tab("feed")
        compose.onNodeWithTag("item-watch-result").assertDoesNotExist()
        tab("saved")
        compose.onNodeWithTag("item-watch-result").performScrollTo().assertIsDisplayed()
        assertEquals(setOf(article.id), state().savedIds)
    }

    @Test fun editingContextPreservesExistingScopesAndChangesTheReopenedGreeting() {
        start()
        val scoped = ContextEntry(id = "scoped-interest", text = "Build small Android tools", scopes = setOf("news"), origin = "imported", updatedAtMillis = 1)
        val changedText = "Build accessible Android tools\nKeep them easy to maintain"
        runBlocking { app.feedRepository.update { it.copy(context = listOf(scoped), items = listOf(stories().first().copy(why = "An old explanation", relevance = 90))) } }
        tab("you")
        clickText(R.string.feed_context)
        waitForTag("context-name")
        compose.onNodeWithTag("context-name").performScrollTo().performTextInput("Alex")
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement(changedText)
        compose.onNodeWithTag("context-add").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.feed_context_entry, 2))).performScrollTo().performTextInput("Learn practical machine learning")
        compose.activityRule.scenario.recreate()
        waitForTag("context-name")
        compose.onNodeWithTag("context-name").assertTextContains("Alex")
        compose.onNodeWithTag("context-edit").assertTextContains(changedText)
        compose.onNode(hasSetTextAction() and hasText(text(R.string.feed_context_entry, 2))).assertTextContains("Learn practical machine learning")
        compose.onNodeWithTag("context-name").performScrollTo()
        screenshot("28-mebot-context")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        waitForState { it.name == "Alex" && it.context.size == 2 }
        waitForTag("nav-you")
        val edited = state().context.single { it.id == scoped.id }
        assertEquals(scoped.copy(text = changedText, updatedAtMillis = edited.updatedAtMillis), edited)
        assertTrue(edited.updatedAtMillis > scoped.updatedAtMillis)
        assertEquals(setOf("news", "opportunities", "watch"), state().context.single { it.id != scoped.id }.scopes)
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
        compose.onNodeWithTag("detail-ask").performScrollTo().performClick()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-send").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-question").performTextInput("How could I use this in a small project?")
        compose.activityRule.scenario.recreate()
        waitForTag("workspace-question")
        compose.onNodeWithTag("workspace-question").assertTextContains("How could I use this in a small project?")
        compose.onNodeWithTag("workspace-send").performClick()
        waitForState { it.conversations.any { thread -> thread.itemId == "toolkit" && thread.turns.lastOrNull()?.role == "assistant" } }
        val conversation = state().conversations.single { it.itemId == "toolkit" }
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
