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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Activity, navigation, ViewModel and durable repository. External transports are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotToday090JourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication
    private fun state()=runBlocking { app.feedRepository.current() }
    private fun waitForState(predicate:(FeedState)->Boolean) { compose.waitUntil(15_000) { predicate(state()) } }
    private fun waitForTag(tag:String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun scrollFeedTo(tag: String) {
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag(tag))
    }

    @Test fun actualTodayShellKeepsStarBookmarkCollectionsFollowSettingsAndOriginalOwnership() {
        compose.waitForIdle()
        val now=System.currentTimeMillis()
        val item=FeedItem(id="today-owned",moduleId="news",kind="news",title="A local market update — illustrative fixture",
            summary="Synthetic source-backed reading fixture for native Activity verification.",sourceName="Example publisher",
            sourceUrl="https://example.com/today-owned",publishedAtMillis=now,fetchedAtMillis=now,
            temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile=Profile(onboardingComplete=true)) }
            app.feedRepository.update { FeedState(onboardingComplete=true,interestsConfigured=true,quickGuideSeen=true,
                timezone="UTC",modules=listOf(FeedModule(id="news",kind="news")),items=listOf(item),lastRefreshMillis=now,
                context=listOf(ContextEntry(id="scoped",text="Synthetic local business interest",scopes=setOf("news")))) }
        }
        waitForTag("feed-screen")
        for(tab in listOf("feed","modules","ai","saved","you")) compose.onNodeWithTag("nav-$tab").assertIsDisplayed()
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("star-${item.id}"))
        compose.onNodeWithTag("star-${item.id}").assertIsDisplayed()
        compose.onNodeWithTag("save-${item.id}").assertIsDisplayed()
        val star=compose.onNodeWithTag("star-${item.id}").getUnclippedBoundsInRoot()
        val bookmark=compose.onNodeWithTag("save-${item.id}").getUnclippedBoundsInRoot()
        assertTrue(star.right<=bookmark.left)
        assertEquals(star.top,bookmark.top)
        compose.onNodeWithTag("star-${item.id}").performClick()
        waitForState { it.starredStories.any { star->star.itemId==item.id } }
        compose.onNodeWithTag("save-${item.id}").performClick()
        waitForState { item.id in it.savedIds }
        capture("today-full-shell-en")
        compose.onNodeWithTag("nav-saved").performClick()
        waitForTag("saved-library")
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-collection-${item.id}"))
        compose.onNodeWithTag("library-collection-${item.id}").performClick()
        compose.onNodeWithTag("collection-name").performScrollTo().performTextInput("Market reading")
        compose.onNodeWithText(compose.activity.getString(R.string.library_collection_apply)).performScrollTo().performClick()
        waitForState { it.savedCollections[item.id]=="Market reading" }
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-starred"))
        compose.onNodeWithTag("library-starred").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("star-active-${item.id}"))
        compose.onNodeWithTag("star-active-${item.id}").performClick()
        waitForState { it.starredStories.single().paused }
        assertEquals(item,state().items.single())
        assertEquals("news",state().items.single().moduleId)
        assertEquals(setOf("news"),state().context.single().scopes)
        assertEquals(0,state().requestsToday)
        compose.activityRule.scenario.recreate()
        waitForTag("saved-library")
        assertEquals("Market reading",state().savedCollections[item.id])
        assertTrue(state().starredStories.single().paused)
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-${item.id}"))
        compose.onNodeWithTag("item-${item.id}").performClick()
        waitForTag("detail-save")
        compose.onNodeWithText(item.title).assertExists()
        assertEquals(1,state().items.size)
        assertEquals(0,state().requestsToday)
    }

    @Test fun actualTodayShellSeparatesPersonalSelectionFromVerifiedTodayInEnAndSw() {
        compose.waitForIdle()
        val now = System.currentTimeMillis()
        val sourceItems = (1..6).map { index ->
            FeedItem(
                id = "selection-source-$index",
                moduleId = "technology",
                title = "A verified software update $index — synthetic fixture",
                summary = "A source-backed test report with a publisher timestamp, shown only to verify the personal selection and verified-today distinction.",
                sourceName = "Example publisher $index",
                sourceUrl = "https://example.com/selection-source-$index",
                publishedAtMillis = now,
                fetchedAtMillis = now,
                kind = "technology",
                relevance = 100 - index,
                temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = now),
            )
        }
        val original = FeedItem(
            id = "selection-original-read",
            moduleId = "technology",
            title = "A practical guide to evaluating a small technology pilot — synthetic fixture",
            summary = "An illustrative Original Read fixture for verifying the personal daily-selection UI. This is not a live provider response.",
            why = "The test fixture demonstrates how an AI-authored read is labeled separately from publisher reporting.",
            fetchedAtMillis = now,
            publishedAtMillis = 0,
            kind = "technology",
            aiGenerated = true,
            contentType = "original_read",
            acquisition = FeedAcquisition.ORIGINAL_READ,
            authorship = FeedAuthorship.AI,
            sections = listOf(
                FeedContentSection("Set a narrow question", "A useful evaluation begins with a specific task and a baseline. Teams can record how long the task takes today, what errors occur, and which constraints matter most. A pilot should test a small number of representative cases before any broad rollout. This keeps the evidence tied to the work people actually do and makes it easier to spot where the new process does not fit."),
                FeedContentSection("Compare more than speed", "A faster result is not automatically a better result. Review quality, correction effort, privacy, accessibility, and the cost of ongoing oversight alongside time saved. Include examples that are unusual or difficult, not only the easy cases. Ask the people responsible for final decisions to describe which mistakes would be consequential and how they would notice them."),
                FeedContentSection("Decide what happens next", "Before a pilot starts, agree on the evidence that would justify continuing, changing, or stopping it. Keep a record of the sample, the human review steps, and any incidents. A small trial can reveal questions that need more investigation, but it cannot establish that every future use is safe or worthwhile. Treat the result as one input into a decision rather than a guarantee."),
            ),
        )
        assertTrue("The synthetic read follows the canonical Original Read contract", FeedLogic.isValidOriginalReadShape(original))
        runBlocking {
            app.feedEngine.disconnect()
            app.sourceItems = emptyList()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true), settings = Settings(language = "en")) }
            app.feedRepository.update {
                FeedState(
                    onboardingComplete = true,
                    interestsConfigured = true,
                    quickGuideSeen = true,
                    timezone = "UTC",
                    modules = listOf(FeedModule(id = "technology", kind = "technology", title = "Technology", query = "software")),
                    items = sourceItems + original,
                    lastRefreshMillis = now,
                )
            }
        }
        waitForTag("feed-screen")
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        waitForState { it.personalDailySelection098?.itemIds?.contains(original.id) == true }
        compose.waitForIdle()
        val selectedSourceId = state().personalDailySelection098!!.itemIds.first { id ->
            id != original.id && state().items.singleOrNull { it.id == id }?.contentType != "original_read"
        }
        val verifiedOnlyId = sourceItems.first { it.id !in state().personalDailySelection098!!.itemIds }.id

        scrollFeedTo("today-personal-selection-heading")
        compose.onNodeWithTag("today-personal-selection-heading").assertIsDisplayed()
            .assertTextContains("For you today", substring = true)
        scrollFeedTo("today-personal-story-${original.id}")
        compose.onNodeWithTag("today-personal-story-${original.id}").assertIsDisplayed()
        compose.onNodeWithTag("story-author-${original.id}", useUnmergedTree = true)
            .assertTextEquals("AI-authored original read")
        compose.onNodeWithTag("story-created-${original.id}", useUnmergedTree = true)
            .assertTextContains("Created", substring = true)
        scrollFeedTo("today-time-$selectedSourceId")
        compose.onNodeWithTag("today-time-$selectedSourceId").assertExists()
        scrollFeedTo("today-verified-section-heading")
        compose.onNodeWithTag("today-verified-section-heading")
            .assertTextContains("Verified today from sources", substring = true)
        scrollFeedTo("today-time-$verifiedOnlyId")
        compose.onNodeWithTag("today-time-$verifiedOnlyId").assertExists()
        scrollFeedTo("today-personal-story-${original.id}")
        capture098("today-selection-original-en")
        scrollFeedTo("today-verified-section-heading")
        capture098("today-selection-verified-en")

        runBlocking { app.repository.update { it.copy(settings = it.settings.copy(language = "sw")) } }
        scrollFeedTo("today-personal-selection-heading")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Kwa ajili yako leo", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        scrollFeedTo("today-personal-selection-heading")
        scrollFeedTo("today-personal-story-${original.id}")
        compose.onNodeWithTag("story-author-${original.id}", useUnmergedTree = true)
            .assertTextEquals("Usomaji asilia ulioandikwa na AI")
        compose.onNodeWithTag("story-created-${original.id}", useUnmergedTree = true)
            .assertTextContains("Iliundwa", substring = true)
        scrollFeedTo("today-personal-story-${original.id}")
        capture098("today-selection-original-sw")
        scrollFeedTo("today-verified-section-heading")
        compose.onNodeWithTag("today-verified-section-heading")
            .assertTextContains("Zilizothibitishwa kuwa za leo", substring = true)
        scrollFeedTo("today-time-$verifiedOnlyId")
        compose.onNodeWithTag("today-time-$verifiedOnlyId").assertExists()
        capture098("today-selection-verified-sw")

        assertEquals("Daily selection is local and never calls the provider", 0, state().requestsToday)
        assertEquals("No AI question-generation inference occurred", 0, app.suggestionCalls)
        assertEquals("No AI answer inference occurred", 0, app.answerCalls)
    }

    private fun capture(name:String) {
        compose.waitForIdle()
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        var status=-1
        compose.runOnIdle { PixelCopy.request(compose.activity.window,bitmap,{status=it},Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status!=-1 };assertEquals(PixelCopy.SUCCESS,status)
        File("build/outputs/screenshots/v090/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    private fun capture098(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnIdle { PixelCopy.request(compose.activity.window, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status != -1 }
        assertEquals(PixelCopy.SUCCESS, status)
        File("build/outputs/screenshots/v098/$name.png").also { it.parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
