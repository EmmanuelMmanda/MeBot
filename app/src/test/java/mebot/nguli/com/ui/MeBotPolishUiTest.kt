package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.AiActivity
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.feed.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotPolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val papers = listOf(
        FeedCitation("Deployment checklist (example PDF)", "https://example.com/deployment-checklist.pdf"),
        FeedCitation("Official guide (example)", "https://example.com/guide"),
        FeedCitation("YouTube explainer (example)", "https://www.youtube.com/watch?v=M7lc1UVf-VE"))
    private val thread = AiConversation(id = "polish", title = "A focused answer", turns = listOf(
        AiTurn("q", "user", "What do I need before I start?"),
        AiTurn("a", "assistant", "## Start with a small test\nCheck the requirements, try one example, then review the result.\n\n**Keep the original sources.** You can save the checklist below.", citations = papers, model = "Example model")))
    private val state = FeedState(onboardingComplete = true, conversations = listOf(thread))

    @Test fun oneTopStatusAndNoMiddleSpinnerWhileAnAnswerStreams() {
        val ui = mutableStateOf(FeedUiState(state = state.copy(conversations = listOf(thread.copy(turns = thread.turns.take(1)))),
            loading = false, connected = true, activeConversationId = thread.id, asking = true,
            answer = "A useful answer is arriving without waiting for media.",
            activities = listOf(AiActivity("answer", thread.id, "technology", "ask", "writing"),
                AiActivity("background", "feed", "news", "discovery", "queued"))))
        compose.setContent { MeBotTheme { Scaffold(topBar = { ProcessingStatus(ui.value, "ai", null) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                AiWorkspace(ui.value, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        } } }
        compose.onAllNodesWithTag("ai-activity-panel").assertCountEquals(1)
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag("workspace-draft"))
        compose.onNodeWithTag("workspace-draft").assert(hasAnyDescendant(hasText("A useful answer", substring = true)))
        compose.onAllNodesWithTag("workspace-importing").assertCountEquals(0)
        screenshot("mebot-0.8.1-streaming")
        compose.runOnIdle { ui.value = ui.value.copy(asking = false, answer = null, activities = emptyList(), state = state) }
        compose.onAllNodesWithTag("ai-activity-panel").assertCountEquals(0)
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag("turn-source-a-resources"))
        // File candidates are categorized by URL; verification happens only after an explicit action.
        compose.onNodeWithTag("turn-source-a-filter-files").assertExists().performClick()
        compose.onNodeWithTag("turn-source-a-0").assertExists()
        compose.onNodeWithTag("turn-source-a-1").assertDoesNotExist()
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag("download-resource"))
        compose.onNodeWithTag("download-resource").assertIsEnabled()
        screenshot("mebot-0.8.1-ai-resources")
    }

    @Test fun customAccentAndSkinCanBeChangedAndRestored() {
        val setting = mutableStateOf(Settings())
        val restore = StateRestorationTester(compose)
        restore.setContent { MeBotTheme(setting.value) { ScreenContainer(Modifier.testTag("appearance-preview")) {
            AppearanceControls(setting.value) { transform -> setting.value = transform(setting.value) }
        } } }
        compose.onNodeWithTag("accent-custom").performScrollTo().performClick()
        compose.onNodeWithTag("custom-accent-input").performScrollTo().performTextReplacement("#bad")
        compose.onNodeWithTag("custom-accent-apply").assertIsNotEnabled()
        compose.onNodeWithTag("custom-accent-input").performTextReplacement("#7251b5")
        compose.onNodeWithTag("custom-accent-apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#7251B5", setting.value.customAccent); assertEquals("custom", setting.value.accent) }
        compose.onNodeWithTag("skin-tinted").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("tinted", setting.value.skin) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("accent-custom").performScrollTo().assertIsSelected()
        screenshot("mebot-0.8.1-appearance")
    }

    @Test fun detailKeepsOriginalNotesAndFileActionsAvailable() {
        val item = FeedItem("detail", "technology", "A useful guide, with its resources", "An example original summary retained in full.",
            sourceUrl = papers[0].url, sourceName = "Example publisher", kind = "technology",
            keyPoints = listOf("Check the requirements.", "Start with a reversible test."), nextStep = "Save the checklist.")
        val ui = FeedUiState(state = state.copy(items = listOf(item)), loading = false, connected = true,
            detailItemId = item.id, detailTurns = thread.turns.takeLast(1))
        compose.setContent { MeBotTheme { FeedDetail(ui, item.id, {}, {}, {}) } }
        compose.onNodeWithTag("detail-brief-answer").performScrollTo().assert(hasAnyDescendant(hasText("Start with a small test", substring = false)))
        compose.onNodeWithTag("detail-story-notes").performScrollTo().performClick()
        compose.onNodeWithText(item.summary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(item.nextStep).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-citation-filter-files").assertExists().performClick()
        compose.onNodeWithTag("detail-citation-0").assertExists()
        compose.onNodeWithTag("detail-citation-1").assertDoesNotExist()
        compose.onAllNodesWithTag("download-resource").onLast().performScrollTo().assertIsEnabled()
        screenshot("mebot-0.8.1-detail-files")
    }

    @Test fun anchoredActivityShowsActualDiscoveryAndStopsAfterCancellation() {
        val ui = mutableStateOf(FeedUiState(state = state, loading = false, refreshing = true,
            activities = listOf(AiActivity("discover", "news", "news", "discovery", "summarizing",
                steps = listOf(mebot.nguli.com.feed.AiActivityStep("preparing"), mebot.nguli.com.feed.AiActivityStep("reading")), contextEntries = 2))))
        compose.setContent { MeBotTheme { Scaffold(topBar = { ProcessingStatus(ui.value, "home", null) }) { padding ->
            Column(Modifier.padding(padding)) { Text("Your information stays visible") }
        } } }
        compose.onNodeWithText("Preparing your updates").assertIsDisplayed()
        compose.onNodeWithText("Writing answer").assertDoesNotExist()
        compose.onAllNodesWithTag("mebot-active-ring", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("ai-activity-panel").performClick()
        compose.onNodeWithTag("activity-details-popup").assertIsDisplayed()
        compose.onNodeWithText("Using 2 relevant saved preferences").assertIsDisplayed()
        screenshot("mebot-0.8.1-activity-details")
        compose.onNodeWithText("Show less").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(refreshing = false, activities = emptyList()) }
        compose.onAllNodesWithTag("mebot-active-ring", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("ai-activity-panel").assertCountEquals(0)
    }

    @Test fun detailSummaryStaysReadableWhileMeBotWorks() {
        val item = FeedItem("working", "opportunities", "An opportunity to explore", "Read the source requirements before applying.",
            sourceUrl = "https://example.com/opportunity", sourceName = "Example employer", kind = "opportunities")
        val ui = FeedUiState(state = state.copy(items = listOf(item)), loading = false, connected = true,
            detailItemId = item.id, detailLoading = true,
            activities = listOf(AiActivity("lookup", item.id, item.kind, "answer", "searching")))
        compose.setContent { MeBotTheme { Scaffold(topBar = { ProcessingStatus(ui, "item", item.id) }) { padding ->
            Box(Modifier.padding(padding)) { FeedDetail(ui, item.id, {}, {}, {}) }
        } } }
        compose.onNodeWithText(item.summary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-story-notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show more").assertIsDisplayed()
        screenshot("mebot-0.8.1-detail-working")
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            window.decorView.invalidate()
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888).also {
                var status: Int? = null
                PixelCopy.request(window, it, { value -> status = value }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, status)
            }
        }
        File("build/outputs/screenshots/$name.png").also { it.parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
}
