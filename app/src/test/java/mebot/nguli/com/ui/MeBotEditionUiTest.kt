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
import mebot.nguli.com.feed.SuggestionOptionSnapshot097
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargetKind097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.ui.feed.FeedDetail
import mebot.nguli.com.ui.feed.FeedHome
import mebot.nguli.com.ui.feed.LocalPublisherImageLoader
import mebot.nguli.com.ui.feed.LocalRelevanceContext
import mebot.nguli.com.ui.feed.RelevanceUiContext
import mebot.nguli.com.ui.feed.storyCategoryResource
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
    private val usedSuggestions = mutableListOf<Pair<SuggestionTargetSnapshot097, String>>()
    private val editedSuggestions = mutableListOf<Pair<SuggestionTargetSnapshot097, String>>()
    private val selectedItems = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private val imageRequests = mutableListOf<String>()
    private var modulesOpened = 0
    private var connectionsOpened = 0
    private var refreshed = 0
    private var detailBriefLoads = 0

    @Test fun visualEditionKeepsSourcesSecondaryAndGroundedAiFollowUps() {
        show()
        compose.waitUntil { GOOGLE_IMAGE in imageRequests }
        screenshot("40-mebot-edition-v5")
        compose.onNodeWithText(text(R.string.feed_category_ai)).assertExists()
        compose.onNodeWithText("Google", substring = true).assertExists()
        feedNode("item-google").performClick()
        assertEquals(listOf("google"), selectedItems)
        // Source access remains a single tap away from the overflow menu, not a card CTA.
        compose.onNodeWithTag("source-google").assertDoesNotExist()
        feedNode("story-menu-google").performClick()
        compose.onNodeWithTag("source-google").assertIsDisplayed().performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(GOOGLE_SOURCE, opened.dataString)
        compose.onNodeWithTag("source-google").assertDoesNotExist()
        feedNode("feed-suggestions-google-open").performClick()
        compose.onNodeWithText(text(R.string.suggestions_title)).assertIsDisplayed()
        compose.onNodeWithTag("feed-suggestions-google-question-0")
            .assertTextContains("What does AI change in this story?", substring = true)
        compose.onNodeWithTag("feed-suggestions-google-edit-0").performClick()
        assertEquals(SuggestionTargetKind097.ITEM, editedSuggestions.last().first.kind)
        assertEquals("google", editedSuggestions.last().first.itemId)
        assertTrue("Pencil editing does not send", usedSuggestions.isEmpty() && prompts.isEmpty())
        feedNode("save-google").performClick()
        assertEquals(listOf("google"), saved)
        feedNode("item-nasa").assertIsDisplayed()
        screenshot("40b-mebot-edition-across-modules-v5")
        feedNode("edition-modules").performClick()
        assertEquals(1, modulesOpened)
    }

    @Test fun firstEditionIsFiniteAndModuleChangesRemoveContentWithoutDeletingTheCache() {
        val cachedWeatherItem = ui.value.state!!.items.single { it.weather != null }
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
            val current = ui.value.state!!
            ui.value = ui.value.copy(state = current.copy(items = current.items.filterNot { it.weather != null }))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("weather-weather").assertDoesNotExist()
        // An empty, explicitly unsupported Weather filter must not silently inherit
        // the General scope Suggestions action.
        compose.onNodeWithTag("feed-suggestions-empty-open").assertDoesNotExist()
        compose.runOnIdle {
            val current = ui.value.state!!
            ui.value = ui.value.copy(state = current.copy(
                items = current.items + cachedWeatherItem,
                modules = current.modules.map { if (it.id == "weather") it.copy(enabled = false) else it },
            ))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("filter-weather").assertDoesNotExist()
        feedNode("feed-refresh")
        compose.onNodeWithTag("filter-all").assertIsSelected()
        compose.onNodeWithTag("weather-weather").assertDoesNotExist()
        compose.onNodeWithTag("item-google").assertExists()
        assertTrue(ui.value.state!!.items.any { it.id == "weather" })
    }

    @Test fun todayRowsOfferContextBoundSuggestionsAlongsideExistingStoryActions() {
        val now = System.currentTimeMillis()
        ui.value = ui.value.copy(state = ui.value.state!!.copy(items = ui.value.state!!.items.map {
            it.copy(publishedAtMillis = now, fetchedAtMillis = now,
                temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = now))
        }))
        show()
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        feedNode("today-suggestions-google-open").assertIsDisplayed()
        feedNode("save-google").assertIsDisplayed()
        feedNode("story-menu-google").assertIsDisplayed()
        feedNode("today-suggestions-google-open").performClick()
        compose.onNodeWithTag("today-suggestions-google-question-0")
            .assertTextContains("What", substring = true)
        compose.onNodeWithTag("today-suggestions-google-edit-0").assertIsDisplayed().performClick()
        assertEquals(SuggestionTargetKind097.ITEM, editedSuggestions.single().first.kind)
        assertEquals("google", editedSuggestions.single().first.itemId)
        assertTrue("Today Suggestions stays explicit and does not send on open/edit", usedSuggestions.isEmpty() && prompts.isEmpty())
    }

    @Test fun cachedEditionAndResourceLinksRemainUsableWhenAiIsDisconnectedOrRefreshFails() {
        ui.value = ui.value.copy(connected = false, error = "offline")
        show()
        feedNode("story-menu-google").performClick()
        compose.onNodeWithTag("source-google").assertIsDisplayed().performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        assertEquals(0, connectionsOpened)
        assertTrue(prompts.isEmpty())
        feedNode("feed-suggestions-google-open").performClick()
        compose.onNodeWithTag("feed-suggestions-google-offline-note").assertIsDisplayed()
        compose.onNodeWithTag("feed-suggestions-google-send-0").assertIsNotEnabled()
        compose.onNodeWithTag("feed-suggestions-google-edit-0").assertIsEnabled().performClick()
        assertEquals("google", editedSuggestions.single().first.itemId)
        assertTrue(usedSuggestions.isEmpty())
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
        feedNode("story-menu-google").assertIsDisplayed().performClick()
        compose.onNodeWithTag("source-google").assertIsDisplayed().performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        feedNode("feed-suggestions-google-open").assertIsDisplayed()
        feedNode("save-google").assertIsDisplayed().performClick()
        assertEquals(listOf("google"), saved)
        val selectedStory = FeedLogic.edition(ui.value.state!!, System.currentTimeMillis()).first()
        val suggestionIcon = feedNode("feed-suggestions-google-open").assertIsDisplayed()
        val iconBounds = suggestionIcon.getUnclippedBoundsInRoot()
        assertTrue("large-text suggestion target stays at least 48dp high", iconBounds.bottom - iconBounds.top >= 48.dp)
        suggestionIcon.performClick()
        compose.onNodeWithTag("feed-suggestions-google-question-0").assertIsDisplayed()
        compose.onNodeWithTag("feed-suggestions-google-edit-0").assertIsDisplayed().performClick()
        assertEquals("A compact-feed pencil opens the item's editable draft", selectedStory.id, editedSuggestions.last().first.itemId)
        feedNode("category-github").assertIsDisplayed()
        feedNode("story-menu-github").performClick()
        compose.onNodeWithTag("source-github").assertIsDisplayed().performClick()
        assertEquals("https://example.com/preview-project", shadowOf(compose.activity).nextStartedActivity.dataString)
        screenshot("41b-mebot-edition-small-swahili-cards-v5")
        feedNode("edition-modules").assertIsDisplayed().performClick()
        assertEquals(1, modulesOpened)
    }

    @Test fun detailRetainsPublisherAttributionAndOriginalResource() {
        show(detail = true)
        compose.onNodeWithText("Google").assertExists()
        compose.onNodeWithTag("detail-source").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.feed_open_source)).assertExists()
        compose.onNodeWithTag("detail-source").performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
        compose.onNodeWithTag("detail-story-notes").performScrollTo().performClick()
        compose.onNodeWithText(ui.value.state!!.items.first { it.id == "google" }.summary).performScrollTo().assertIsDisplayed()
        val story = ui.value.state!!.items.single { it.id == "google" }
        // A provider-generated set may contain five approved questions; the first Send button
        // must still re-resolve to that exact stable option rather than an arbitrary prompt.
        val expectedId = AiSuggestedInteractions095.suggestionId(story, story.suggestedInteractions.first())
        compose.onNodeWithTag("detail-suggestions-google-open").performScrollTo().performClick()
        compose.onNodeWithTag("detail-suggestions-google-question-0").assertIsDisplayed()
        compose.onNodeWithTag("detail-suggestions-google-send-0").assertIsEnabled().performClick()
        assertEquals("google", usedSuggestions.last().first.itemId)
        assertEquals(expectedId, usedSuggestions.last().second)
        assertTrue("Explicit Send is the only suggestion action that uses the primary callback", selectedItems.isEmpty())
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

    @Test fun validOriginalReadRendersAllSectionsAndSeparatesEvidenceFromFurtherReading() {
        val original = originalReadFixture("original-read", System.currentTimeMillis())
        assertTrue("Fixture exercises the canonical Original Read shape", FeedLogic.isValidOriginalReadShape(original))
        ui.value = ui.value.copy(state = ui.value.state!!.copy(items = ui.value.state!!.items + original))
        detailBriefLoads = 0

        show(detail = true, detailId = original.id)
        compose.waitForIdle()
        screenshot("v098-original-read-en-overview")

        assertEquals("A valid Original Read must not start a second automatic explanation", 0, detailBriefLoads)
        compose.onNodeWithTag("detail-brief").assertDoesNotExist()
        compose.onNodeWithTag("detail-source").assertDoesNotExist()
        compose.onNodeWithTag("detail-original-read-authorship").assertTextEquals(text(R.string.original_read_authorship_098))
        compose.onNodeWithTag("detail-original-read-created").assertTextContains("Created", substring = true)
        compose.onNodeWithTag("detail-original-read-heading-0").performScrollTo().assertTextEquals(original.sections[0].heading)
        compose.onNodeWithTag("detail-original-read-body-0").performScrollTo().assertTextEquals(original.sections[0].body)
        compose.onNodeWithTag("detail-original-read-heading-1").performScrollTo().assertTextEquals(original.sections[1].heading)
        compose.onNodeWithTag("detail-original-read-body-1").performScrollTo().assertTextEquals(original.sections[1].body)
        screenshot("v098-original-read-en-sections")

        compose.onNodeWithTag("detail-original-read-overview-evidence-heading").performScrollTo()
            .assertTextEquals(text(R.string.original_read_evidence_098))
        compose.onNodeWithTag("detail-original-read-overview-evidence-0").assertExists()
        compose.onNodeWithTag("detail-original-read-overview-further-reading-heading").performScrollTo()
            .assertTextEquals(text(R.string.original_read_further_reading_098))
        compose.onNodeWithTag("detail-original-read-overview-further-reading-0").assertExists()

        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-original-read-sources-evidence-heading").performScrollTo()
            .assertTextEquals(text(R.string.original_read_evidence_098))
        compose.onNodeWithTag("detail-original-read-sources-further-reading-heading").performScrollTo()
            .assertTextEquals(text(R.string.original_read_further_reading_098))
        compose.onNodeWithText(text(R.string.detail_published)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.detail_retrieved)).assertDoesNotExist()

        // The 0.9.7 five-question Suggestions action remains independent on an Original Read.
        compose.onNodeWithTag("detail-tab-overview").performScrollTo().performClick()
        compose.onNodeWithTag("detail-suggestions-${original.id}-open").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail-suggestions-${original.id}-question-0").assertExists()
        compose.onNodeWithTag("detail-suggestions-${original.id}-close").performClick()
        assertTrue("Opening and dismissing Suggestions never loads another detail brief", detailBriefLoads == 0)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun originalReadAuthorshipAndReferenceLabelsAreLocalizedInSwahili() {
        val original = originalReadFixture("original-read-sw", System.currentTimeMillis())
        ui.value = ui.value.copy(state = ui.value.state!!.copy(items = ui.value.state!!.items + original))
        detailBriefLoads = 0

        show(Settings(language = "sw"), detail = true, detailId = original.id)
        compose.waitForIdle()
        screenshot("v098-original-read-sw-overview")

        compose.onNodeWithTag("detail-original-read-authorship").assertTextEquals("Usomaji asilia ulioandikwa na AI")
        compose.onNodeWithTag("detail-original-read-created").assertTextContains("Iliundwa", substring = true)
        compose.onNodeWithTag("detail-original-read-overview-evidence-heading").performScrollTo().assertTextEquals("Ushahidi")
        compose.onNodeWithTag("detail-original-read-overview-further-reading-heading").performScrollTo().assertTextEquals("Usomaji wa ziada")
        compose.onNodeWithTag("detail-original-read-body-1").performScrollTo().assertIsDisplayed()
        screenshot("v098-original-read-sw-sections")
        assertEquals("Original Read detail suppresses the automatic brief in Kiswahili too", 0, detailBriefLoads)
    }

    @Test fun todaySeparatesPersonalSelectionFromVerifiedSourceItemsAndDeduplicates() {
        val now = System.currentTimeMillis()
        val dailyState = personalTodayState(now)
        val selectedIds = dailyState.personalDailySelection098!!.itemIds.toSet()
        val selectedSourceId = dailyState.items.first { it.id in selectedIds && it.contentType != "original_read" }.id
        val unselectedSourceId = dailyState.items.first { it.id !in selectedIds && it.id.startsWith("today-source-") }.id
        ui.value = ui.value.copy(state = dailyState)
        var ensureCalls = 0
        show(onEnsureDailySelection = { ensureCalls++ })
        compose.waitForIdle()
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        feedNode("today-personal-selection-heading")
            .assertTextContains(text(R.string.daily_selection_098_title), substring = true)
        feedNode("today-personal-story-today-original").assertIsDisplayed()

        compose.onNodeWithTag("story-author-today-original", useUnmergedTree = true).assertTextEquals(text(R.string.original_read_authorship_098))
        compose.onNodeWithTag("story-created-today-original", useUnmergedTree = true).assertTextContains("Created", substring = true)
        feedNode("item-$selectedSourceId")
        compose.onNodeWithTag("story-author-$selectedSourceId", useUnmergedTree = true).assertTextContains("Example publisher", substring = true)
        feedNode("today-time-$selectedSourceId").assertExists()
        val storyCardBounds = compose.onNodeWithTag("story-card-$selectedSourceId").getUnclippedBoundsInRoot()
        val sourceTimeBounds = compose.onNodeWithTag("today-time-$selectedSourceId").getUnclippedBoundsInRoot()
        assertTrue("Verified selected-item date is visually inside that source card", storyCardBounds.left <= sourceTimeBounds.left &&
            storyCardBounds.top <= sourceTimeBounds.top && storyCardBounds.right >= sourceTimeBounds.right && storyCardBounds.bottom >= sourceTimeBounds.bottom)
        compose.onNodeWithTag("story-created-$selectedSourceId", useUnmergedTree = true).assertDoesNotExist()
        feedNode("today-verified-section-heading").assertIsDisplayed()
        feedNode("today-time-$unselectedSourceId").assertExists()
        feedNode("item-$selectedSourceId").assertIsDisplayed()
        compose.onAllNodesWithTag("item-$selectedSourceId").assertCountEquals(1)
        feedNode("story-card-today-original").assertIsDisplayed()
        compose.onNodeWithTag("save-today-original", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("star-today-original", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("today-suggestions-today-original-open", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("FeedHome requests only the local daily projection", ensureCalls > 0)
        assertEquals("Selection rendering does not refresh or call a provider", 0, refreshed)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun todayPersonalSelectionAndOriginalReadProvenanceAreLocalizedInSwahili() {
        val now = System.currentTimeMillis()
        ui.value = ui.value.copy(state = personalTodayState(now))
        show(Settings(language = "sw"))
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        feedNode("today-personal-selection-heading")
            .assertTextContains("Kwa ajili yako leo", substring = true)
        feedNode("today-personal-story-today-original")
        compose.onNodeWithTag("story-author-today-original", useUnmergedTree = true).assertTextEquals("Usomaji asilia ulioandikwa na AI")
        compose.onNodeWithTag("story-created-today-original", useUnmergedTree = true).assertTextContains("Iliundwa", substring = true)
        feedNode("today-verified-section-heading")
            .assertTextContains("Zilizothibitishwa kuwa za leo", substring = true)
    }

    @Test fun technologyCategoryShowsAiOnlyWhenStoryTextSupportsIt() {
        val software = FeedItem("software", "technology", "A local software release", "The release adds offline work support.", kind = "technology")
        val artificialIntelligence = software.copy(title = "A new AI workflow", summary = "The tool uses machine learning.")
        assertEquals(R.string.feed_category_technology, storyCategoryResource(software))
        assertEquals(R.string.feed_category_ai, storyCategoryResource(artificialIntelligence))
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun detailPublisherAndSecondarySourceRemainReachableAtLargeSwahiliText() {
        show(Settings(language = "sw", theme = "dark"), 1.3f, detail = true)
        compose.onNodeWithText("Google").assertExists()
        val source = compose.onNodeWithTag("detail-source").assertIsDisplayed()
        val bounds = source.getUnclippedBoundsInRoot()
        assertTrue("secondary source remains a comfortable touch target", bounds.bottom - bounds.top >= 48.dp)
        screenshot("42-mebot-detail-small-swahili")
        source.performClick()
        assertEquals(GOOGLE_SOURCE, shadowOf(compose.activity).nextStartedActivity.dataString)
    }

    private fun show(settings: Settings = Settings(), fontScale: Float = 1f, detail: Boolean = false, detailId: String = "google",
        onEnsureDailySelection: () -> Unit = {}) {
        ui.value = ui.value.copy(appLanguage = if (settings.language == "sw") "sw" else "en")
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
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalPublisherImageLoader provides imageLoader,
                    LocalRelevanceContext provides RelevanceUiContext(state = ui.value.state, onStar = {}),
                ) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background,
                        topBar = { Text(if (settings.language == "sw") "Toleo la mfano" else "Preview edition", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) },
                        bottomBar = { if (!detail) MeBotNavigation("feed") {} },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            if (detail) FeedDetail(ui.value, detailId, onSave = { saved += it }, onAsk = { selectedItems += detailId }, onFeed = {},
                                onLoadBrief = { detailBriefLoads++ },
                                onUseSuggestion = { target, id, _ -> usedSuggestions += target to id },
                                onEditSuggestion = { target, id, _ -> editedSuggestions += target to id },
                                onSuggestionSheet = ::suggestionSheet,
                                onGenerateSuggestion = { _, _ -> null }, onCancelSuggestionGeneration = {})
                            else FeedHome(ui.value, onItem = { selectedItems += it }, onModules = { modulesOpened++ }, onYou = {},
                                onConnect = { connectionsOpened++ }, onAsk = {}, onRefresh = { refreshed++ }, onSave = { saved += it },
                                onHide = {}, onDismissError = {}, nextAlarm = null, onAlarms = {}, onPrompt = { id, question -> prompts += id to question },
                                onUseSuggestion = { target, id, _ -> usedSuggestions += target to id },
                                onEditSuggestion = { target, id, _ -> editedSuggestions += target to id },
                                onSuggestionSheet = ::suggestionSheet,
                                onGenerateSuggestion = { _, _ -> null }, onCancelSuggestionGeneration = {},
                                onEnsureDailySelection = onEnsureDailySelection)
                        }
                    }
                }
            }
        }
    }

    private fun originalReadFixture(id: String, now: Long): FeedItem {
        val pilot = "Choose one repeatable task before testing a new tool. Describe the current steps, the time they take, and what a useful result would look like. Use a representative example rather than the easiest case, so a small pilot answers a real question. Keep the trial inside a temporary workspace and include only information approved for the selected provider. A person should review each result before it is used; the experiment is meant to expose fit and gaps, not to replace accountable judgment. Record the prompts, corrections, and time required so another team member can repeat the same test."
        val comparison = "Compare the tool with the simplest workable alternative on the same example. Check installation effort, recurring costs, data handling, and whether the result can be verified. Note where errors appear and how much review is needed; a polished response alone does not show that the workflow saves time. Stop if the tool needs information you cannot share or if its behavior is too inconsistent for the task. If results are useful, repeat the pilot with a second approved example and agree on a human owner before expanding it. Keep the original measurements so a later review can tell whether the change helped."
        return FeedItem(
            id = id, moduleId = "technology", title = "A careful way to test a new AI tool",
            summary = "A practical pilot can show where a tool helps and where review remains necessary.",
            kind = "technology", aiGenerated = true, contentType = "original_read",
            fetchedAtMillis = now, acquisition = FeedAcquisition.ORIGINAL_READ, authorship = FeedAuthorship.AI,
            sections = listOf(
                FeedContentSection("Start with a bounded pilot", pilot),
                FeedContentSection("Compare the result with a baseline", comparison),
            ),
            citations = listOf(FeedCitation("Evidence: evaluation guide", "https://example.com/evaluation-guide")),
            furtherReading = listOf(FeedCitation("Optional: pilot checklist", "https://example.com/pilot-checklist")),
        )
    }

    private fun personalTodayState(now: Long): FeedState {
        val sources = (1..5).map { index ->
            FeedItem(
                id = "today-source-$index", moduleId = "technology", title = "A verified source update $index for today",
                summary = "A synthetic source-backed report with an exact publisher publication time.",
                sourceName = "Example publisher $index", sourceUrl = "https://example.com/today-source-$index",
                publishedAtMillis = now, fetchedAtMillis = now, kind = "technology", relevance = 100 - index,
                temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = now),
            )
        }
        val base = fixtureState().copy(
            timezone = "UTC", items = sources + originalReadFixture("today-original", now),
            personalDailySelection098 = null,
        )
        return PersonalDailySelectionLogic098.ensure(base, now)
    }

    /** Explicitly synthetic AI-provider output for Compose component tests only. */
    private fun suggestionSheet(target: SuggestionTargetSnapshot097, language: String): SuggestionSheetSnapshot097 {
        val state = ui.value.state
        val item = target.itemId?.let { id -> state?.items?.singleOrNull { it.id == id } }
        if (state != null && item != null && AiSuggestedInteractions095.isApplicable(state, item, System.currentTimeMillis(), language)) {
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.READY,
                item.suggestedInteractions.map { interaction -> SuggestionOptionSnapshot097(
                    AiSuggestedInteractions095.suggestionId(item, interaction), interaction.prompt,
                    item.suggestedInteractionsGeneratedAtMillis,
                ) },
                validUntilMillis = item.suggestedInteractionsGeneratedAtMillis + AiSuggestionCache097.CACHE_TTL_MILLIS,
            )
        }
        val first = if (target.itemId == "google") "What does AI change in this story?"
            else "What evidence would most change the decision in this synthetic preview?"
        val questions = listOf(
            first,
            "Which assumption about this topic should be checked before acting?",
            "What practical limitation or risk remains unresolved?",
            "Which alternative is useful to compare, and on what measure?",
            "What unanswered detail would be most useful to investigate next?",
        )
        return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.READY,
            questions.mapIndexed { index, question -> SuggestionOptionSnapshot097("provider-fixture-$index", question, System.currentTimeMillis()) },
            validUntilMillis = System.currentTimeMillis() + AiSuggestionCache097.CACHE_TTL_MILLIS)
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
            val modules = ModuleCatalog.defaults.map { it.copy(enabled = it.kind in setOf("technology", "science", "github", "learning", "scholarships", "weather")) }
            val base = FeedState(readingPreferences = ReadingPreferences(autoLoadMore = false), onboardingComplete = true, name = "Alex", region = "Preview city", timezone = "UTC",
                modules = modules,
                items = listOf(google, nasa,
                    preview("github", "github", "Preview: A small toolkit for local AI", "repository", 50, "https://example.com/preview-project"),
                    preview("learning", "learning", "Preview: Build a reliable Android worker", "course", 46),
                    preview("scholarships", "scholarships", "Preview: Research funding to explore", "opportunity", 42),
                    preview("weather", "weather", "Preview weather", "weather", 35).copy(weather = FeedWeather(26.0, 28.0, 20, 2, "Preview city")),
                    preview("learning-extra", "learning", "Preview: A guide to API observability", "course", 25),
                    preview("learning-extra-two", "learning", "Preview: Practice with a small project", "course", 20)),
                lastRefreshMillis = now, connection = AiConnection(mode = "chatgpt", model = "preview-model", accountId = "preview-account"))
            val suggestions = listOf(
                AiSuggestedInteraction(label = "AI fixture one", prompt = "What does AI change in this story?"),
                AiSuggestedInteraction(label = "AI fixture two", prompt = "Which claim about this synthetic story has the strongest source evidence?"),
                AiSuggestedInteraction(label = "AI fixture three", prompt = "What practical limitation should be checked before acting on this update?"),
                AiSuggestedInteraction(label = "AI fixture four", prompt = "Which alternative would be useful to compare with this approach?"),
                AiSuggestedInteraction(label = "AI fixture five", prompt = "What unresolved detail could most change the next decision?"),
            )
            val unverifiedSuggestionItem = google.copy(
                suggestedInteractions = suggestions,
                suggestedInteractionsGeneratedAtMillis = now,
            )
            val module = modules.single { it.id == google.moduleId }
            val suggested = unverifiedSuggestionItem.copy(
                suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(unverifiedSuggestionItem),
                suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(base, module, "en"),
            )
            return base.copy(items = base.items.map { if (it.id == google.id) suggested else it })
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
