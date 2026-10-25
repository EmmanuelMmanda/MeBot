package mebot.nguli.com.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mebot.nguli.com.MeBotNavigation
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.feed.FeedDetail
import mebot.nguli.com.ui.feed.FeedHome
import mebot.nguli.com.ui.feed.LocalPublisherImageLoader
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Native UI verification only. The profile and other stories are explicitly synthetic.
 * Optional preview photos are downloaded publisher media, read from a private build input;
 * tests never fetch a network image or ship fixtures in the APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotEditionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val ui = mutableStateOf(FeedUiState(state = fixtureState(), loading = false, connected = true))
    private val prompts = mutableListOf<Pair<String?, String>>()
    private val selectedItems = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private val imageRequests = mutableListOf<String>()
    private var modulesOpened = 0
    private var connectionsOpened = 0
    private var refreshed = 0

    @Test fun visualEditionHasDirectResourcesAndGroundedAiFollowUps() {
        show()
        compose.waitUntil { GOOGLE_IMAGE in imageRequests }
        screenshot("40-mebot-edition-v5")
        feedNode("source-google").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(GOOGLE_SOURCE, opened.dataString)
        feedNode("explain-google").performClick()
        assertEquals("google", prompts.single().first)
        assertEquals(text(R.string.feed_ask_explain), prompts.single().second)
        feedNode("save-google").performClick()
        assertEquals(listOf("google"), saved)
        feedNode("edition-ask").performClick()
        assertNull(prompts.last().first)
        assertEquals(text(R.string.edition_ask_today_prompt), prompts.last().second)
        feedNode("item-nasa").assertIsDisplayed()
        screenshot("40b-mebot-edition-across-modules-v5")
        feedNode("edition-modules").performClick()
        assertEquals(1, modulesOpened)
    }

    @Test fun firstEditionIsFiniteAndModuleChangesRemoveContentWithoutDeletingTheCache() {
        show()
        feedNode("feed-page-progress").assertTextEquals(pageCount(5, 7))
        compose.onNodeWithTag("item-learning-extra").assertDoesNotExist()
        feedNode("feed-show-more").performClick()
        feedNode("feed-page-progress").assertTextEquals(pageCount(7, 7))
        // The chip's nearest scrolling ancestor is horizontal. First return the
        // enclosing vertical screen to its header, then reveal and tap the chip.
        feedNode("feed-refresh").assertIsDisplayed()
        feedNode("filter-weather").assertIsDisplayed()
        val chipLeft = compose.onNodeWithTag("filter-weather").getUnclippedBoundsInRoot().left.value
        compose.onNodeWithTag("filter-weather").performClick().assertIsSelected().assertIsDisplayed()
        assertEquals(chipLeft, compose.onNodeWithTag("filter-weather").getUnclippedBoundsInRoot().left.value, 1f)
        feedNode("weather-weather").assertIsDisplayed()
        compose.onAllNodes(storyNode()).assertCountEquals(0)
        compose.runOnIdle {
            ui.value = ui.value.copy(state = ui.value.state!!.copy(modules = ui.value.state!!.modules.map {
                if (it.id == "weather") it.copy(enabled = false) else it
            }))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("filter-weather").assertDoesNotExist()
        feedNode("feed-refresh")
        compose.onNodeWithTag("filter-all").assertIsSelected()
        compose.onNodeWithTag("weather-weather").assertDoesNotExist()
        compose.onNodeWithTag("item-google").assertExists()
        assertTrue(ui.value.state!!.items.any { it.id == "weather" })
    }

    @Test fun cachedEditionAndResourceLinksRemainUsableWhenAiIsDisconnectedOrRefreshFails() {
        ui.value = ui.value.copy(connected = false, error = "offline")
        show()
        feedNode("source-google").performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        feedNode("explain-google").performClick()
        assertEquals(1, connectionsOpened)
        assertTrue(prompts.isEmpty())
        compose.runOnIdle { ui.value = ui.value.copy(refreshing = true, error = null) }
        feedNode("feed-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("item-google").assertExists()
        compose.runOnIdle { ui.value = ui.value.copy(refreshing = false, error = "offline") }
        feedNode("feed-refresh").performClick()
        assertEquals(1, refreshed)
        assertEquals(8, ui.value.state!!.items.size)
    }

    @Test fun multiplePagesRevealEveryStoryOnceWithoutSkippingNewRowsOrRequestingAi() {
        ui.value = ui.value.copy(state = pagedState())
        show()
        // Even opening the first edition must not start media work for the full cache.
        compose.waitForIdle()
        assertTrue(imageRequests.distinct().size < 5)
        feedNode("feed-page-progress").assertTextEquals(pageCount(5, 13))
        feedNode("feed-show-more").performClick()
        compose.onNodeWithTag("item-page-6").assertIsDisplayed()
        feedNode("feed-page-progress").assertTextEquals(pageCount(10, 13))
        feedNode("feed-show-more").performClick()
        compose.onNodeWithTag("item-page-11").assertIsDisplayed()
        feedNode("feed-page-progress").assertTextEquals(pageCount(13, 13))
        compose.onNodeWithTag("feed-show-more").assertDoesNotExist()
        (1..13).forEach { feedNode("item-page-$it").performClick() }
        assertEquals((1..13).map { "page-$it" }, selectedItems)
        assertEquals(0, refreshed)
        assertTrue(prompts.isEmpty())
        assertEquals(13, ui.value.state!!.items.size)
        // Only nearby cards stay composed after reaching the end of a long edition.
        assertTrue(compose.onAllNodes(storyNode()).fetchSemanticsNodes().size < 13)
    }

    @Test fun filterPagesAndReadingPositionSurviveRestoreRefreshAndHiddenItems() {
        ui.value = ui.value.copy(state = pagedState(includeWeather = true))
        show()
        feedNode("feed-show-more").performClick()
        feedNode("feed-show-more").performClick()
        feedNode("item-page-9").assertIsDisplayed()
        val beforeRestore = compose.onNodeWithTag("item-page-9").getUnclippedBoundsInRoot().top
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("item-page-9").assertIsDisplayed()
        val afterRestore = compose.onNodeWithTag("item-page-9").getUnclippedBoundsInRoot().top
        assertEquals(beforeRestore.value, afterRestore.value, 1f)

        // Inserting newer content and hiding an earlier item keeps the current story anchored.
        compose.runOnIdle {
            val state = ui.value.state!!
            val newer = state.items.first().copy(id = "new-story", title = "Preview: newly arrived story", relevance = 100)
            ui.value = ui.value.copy(state = state.copy(items = listOf(newer) + state.items))
        }
        compose.onNodeWithTag("item-page-9").assertIsDisplayed()
        assertEquals(afterRestore.value, compose.onNodeWithTag("item-page-9").getUnclippedBoundsInRoot().top.value, 1f)
        compose.runOnIdle {
            ui.value = ui.value.copy(state = ui.value.state!!.copy(hiddenIds = setOf("page-4")))
        }
        compose.onNodeWithTag("item-page-9").assertIsDisplayed()
        assertEquals(afterRestore.value, compose.onNodeWithTag("item-page-9").getUnclippedBoundsInRoot().top.value, 1f)

        feedNode("feed-refresh")
        compose.onNodeWithTag("filter-weather").performScrollTo().performClick()
        feedNode("weather-weather").assertIsDisplayed()
        feedNode("feed-refresh")
        compose.onNodeWithTag("filter-all").performScrollTo().performClick()
        feedNode("feed-page-progress").assertTextEquals(pageCount(13, 13))
        compose.onNodeWithTag("feed-show-more").assertDoesNotExist()
        assertEquals(0, refreshed)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun longTitleWithoutMediaAndLargerSwahiliTextKeepAllPrimaryActionsReachable() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(items = ui.value.state!!.items.map {
            if (it.id == "google") it.copy(
                title = "Mfano: Mwongozo mpana wa kujenga programu zenye taarifa sahihi na mipangilio binafsi inayohifadhiwa kwa usalama",
                summary = "Maudhui haya ni mfano wa kupima maandishi marefu kwenye simu ndogo. Fungua chanzo au uliza swali kwa maelezo zaidi.",
                why = "Mfano unaonyesha jinsi maandishi makubwa yanavyopangwa bila kuficha vitendo.", media = null,
            ) else it
        }))
        show(Settings(language = "sw", theme = "dark"), 1.3f)
        screenshot("41-mebot-edition-small-swahili-v5")
        feedNode("source-google").assertIsDisplayed().performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        feedNode("explain-google").assertIsDisplayed().performClick()
        feedNode("save-google").assertIsDisplayed().performClick()
        assertEquals(listOf("google"), saved)
        assertEquals("google", prompts.single().first)
        feedNode("source-github").assertIsDisplayed().performClick()
        assertEquals("https://example.com/preview-project", shadowOf(compose.activity).nextStartedActivity.dataString)
        screenshot("41b-mebot-edition-small-swahili-cards-v5")
        feedNode("edition-modules").assertIsDisplayed().performClick()
        assertEquals(1, modulesOpened)
    }

    @Test fun detailRetainsPublisherAttributionAndOriginalResource() {
        show(detail = true)
        compose.onNodeWithTag("detail-story-notes").performScrollTo().performClick()
        compose.onNodeWithText(ui.value.state!!.items.first { it.id == "google" }.summary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-citation-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-citation-0").performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        compose.onNodeWithTag("detail-tab-overview").performScrollTo().performClick()
        compose.onNodeWithTag("detail-save").performScrollTo().performClick()
        assertEquals(listOf("google"), saved)
        compose.onNodeWithTag("detail-ask").performScrollTo().performClick()
        assertEquals(listOf("google"), selectedItems)
    }

    private fun show(settings: Settings = Settings(), fontScale: Float = 1f, detail: Boolean = false) {
        val imageLoader: suspend (String) -> Bitmap? = { url ->
            imageRequests += url
            val directory = System.getenv("MEBOT_PREVIEW_MEDIA_DIR")
            val name = when (url) { GOOGLE_IMAGE -> "v5-google-preview.img"; NASA_IMAGE -> "v5-nasa-preview.img"; else -> "" }
            if (directory != null && name.isNotBlank()) BitmapFactory.decodeFile(File(directory, name).path).also {
                assertNotNull("The requested publisher preview image must decode", it)
            } else null
        }
        restoration.setContent {
            MeBotTheme(settings) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalPublisherImageLoader provides imageLoader) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background,
                        topBar = { Text(if (settings.language == "sw") "Toleo la mfano" else "Preview edition", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) },
                        bottomBar = { if (!detail) MeBotNavigation("feed") {} },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            if (detail) FeedDetail(ui.value, "google", onSave = { saved += it }, onAsk = { selectedItems += "google" }, onFeed = {})
                            else FeedHome(ui.value, onItem = { selectedItems += it }, onModules = { modulesOpened++ }, onYou = {},
                                onConnect = { connectionsOpened++ }, onAsk = {}, onRefresh = { refreshed++ }, onSave = { saved += it },
                                onHide = {}, onDismissError = {}, nextAlarm = null, onAlarms = {}, onPrompt = { id, question -> prompts += id to question })
                        }
                    }
                }
            }
        }
    }

    private fun feedNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun pageCount(shown: Int, total: Int) = compose.activity.getString(R.string.edition_page_count, shown, total)
    private fun text(id: Int) = compose.activity.getString(id)
    private fun storyNode() = SemanticsMatcher("Feed story") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("item-") == true }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        val image = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val file = File("build/outputs/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    companion object {
        private const val GOOGLE_SOURCE = "https://blog.google/company-news/outreach-and-initiatives/arts-culture/visionaries-petit-palais-art-basel-paris-prune-nourry/"
        private const val GOOGLE_IMAGE = "https://storage.googleapis.com/gweb-uniblog-publish-prod/images/Prune_Nourry_Hero.width-1300.png"
        private const val NASA_SOURCE = "https://science.nasa.gov/earth/earth-observatory/extratropical-cyclone-whips-past-iceland/"
        private const val NASA_IMAGE = "https://assets.science.nasa.gov/content/dam/science/esd/eo/images/iotd/2026/extratropical-cyclone-whips-past-iceland/icelandstorm_vir2_20261014_th.jpg/jcr:content/renditions/cq5dam.web.1280.1280.jpeg"

        private fun fixtureState(): FeedState {
            val now = System.currentTimeMillis()
            fun preview(id: String, module: String, title: String, type: String, score: Int, url: String = "https://example.com/$id") =
                FeedItem(id, module, title, "Synthetic preview content. Open the original resource for details.", sourceName = "Preview source", sourceUrl = url,
                    publishedAtMillis = now, fetchedAtMillis = now, kind = module, relevance = score, contentType = type)
            val google = FeedItem("google", "technology", "Google uses AI to reconstruct lost artists’ studios",
                "A documentary combines archival research with AI reconstructions of the workspaces of women sculptors.",
                why = "A practical example of AI in research and visual storytelling.", sourceName = "Google", sourceUrl = GOOGLE_SOURCE,
                publishedAtMillis = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli(), fetchedAtMillis = now,
                kind = "technology", aiGenerated = false, citations = listOf(FeedCitation("Google Arts & Culture", GOOGLE_SOURCE)), relevance = 100,
                media = FeedMedia(GOOGLE_IMAGE, GOOGLE_SOURCE, "Google Arts & Culture"))
            val nasa = FeedItem("nasa", "science", "A satellite view of a storm passing Iceland",
                "NASA shares a NOAA-21 satellite image of an extratropical cyclone over the North Atlantic.", sourceName = "NASA", sourceUrl = NASA_SOURCE,
                publishedAtMillis = Instant.parse("2026-10-21T04:01:00Z").toEpochMilli(), fetchedAtMillis = now, kind = "science", relevance = 96,
                media = FeedMedia(NASA_IMAGE, NASA_SOURCE, "NASA Earth Observatory / NOAA-21"))
            return FeedState(onboardingComplete = true, name = "Alex", region = "Preview city", timezone = "UTC",
                modules = ModuleCatalog.defaults.map { it.copy(enabled = it.kind in setOf("technology", "science", "github", "learning", "scholarships", "weather")) },
                items = listOf(google, nasa,
                    preview("github", "github", "Preview: A small toolkit for local AI", "repository", 50, "https://example.com/preview-project"),
                    preview("learning", "learning", "Preview: Build a reliable Android worker", "course", 46),
                    preview("scholarships", "scholarships", "Preview: Research funding to explore", "opportunity", 42),
                    preview("weather", "weather", "Preview weather", "weather", 35).copy(weather = FeedWeather(26.0, 28.0, 20, 2, "Preview city")),
                    preview("learning-extra", "learning", "Preview: A guide to API observability", "course", 25),
                    preview("learning-extra-two", "learning", "Preview: Practice with a small project", "course", 20)),
                lastRefreshMillis = now, connection = AiConnection(mode = "chatgpt", model = "preview-model", accountId = "preview-account"))
        }

        private fun pagedState(includeWeather: Boolean = false): FeedState {
            val base = fixtureState()
            val now = System.currentTimeMillis()
            val stories = (1..13).map { index ->
                val url = "https://example.com/preview-lesson-$index"
                FeedItem("page-$index", "learning", "Preview lesson $index", "Synthetic pagination test content.",
                    sourceName = "Preview source", sourceUrl = url, kind = "learning", relevance = 100 - index,
                    publishedAtMillis = now - index * 1_000L, fetchedAtMillis = now,
                    media = FeedMedia("https://example.com/preview-$index.jpg", url, "Preview image"))
            }
            return base.copy(modules = base.modules.map { it.copy(enabled = it.kind == "learning" || (includeWeather && it.kind == "weather")) },
                items = stories + if (includeWeather) base.items.filter { it.weather != null } else emptyList())
        }
    }
}
