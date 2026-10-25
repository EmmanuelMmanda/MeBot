package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.feed.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release083FeedUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun story(id: String, title: String) = FeedItem(id,"news",title,"Useful source summary",sourceUrl="https://example.com/$id",sourceName="Publisher")
    @Test fun savedSearchCollectionsAndStarPauseHaveRealState() {
        val state = mutableStateOf(FeedState(items=listOf(story("one","Building a shop"),story("two","Learning music")),savedIds=setOf("one","two"),starredStories=listOf(StarredStory("one"))))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(
            state=state.value, onStar={state.value=StarredStories.toggle(state.value,it,100)},
            onConfigureStar={id,paused,hours->state.value=StarredStories.configure(state.value,id,paused,hours)},
            onCollection={id,name->state.value=StarredStories.collection(state.value,id,name)})) {
            SavedFeed(state.value,{}, {}, {})
        } } }
        compose.onNodeWithTag("library-search").performTextInput("shop")
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("item-one"))
        compose.onNodeWithTag("item-one").assertExists()
        compose.onNodeWithTag("item-two").assertDoesNotExist()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-collection-one"))
        compose.onNodeWithTag("library-collection-one").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("collection-name"))
        compose.onNodeWithTag("collection-name").performTextInput("Work")
        compose.onNodeWithTag("saved-library").performScrollToNode(hasText("Save collection"))
        compose.onNodeWithText("Save collection").performClick()
        assertEquals("Work",state.value.savedCollections["one"])
        screenshot("v083-saved-library")
        compose.onNodeWithTag("saved-library").performScrollToIndex(0)
        compose.onNodeWithTag("library-starred").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("star-active-one"))
        compose.onNodeWithTag("star-active-one").performClick()
        assertTrue(state.value.starredStories.single().paused)
        assertEquals(setOf("one","two"),state.value.savedIds)
    }
    @Test fun feedStarPreviewUsesLocalFixtureWithoutProviderCalls() {
        val state = mutableStateOf(FeedState(items=listOf(story("fixture", "A new chapter for local businesses")), readingPreferences=ReadingPreferences(autoLoadMore=false)))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(
            state=state.value,onStar={state.value=StarredStories.toggle(state.value,it,100)})) {
            FeedHome(FeedUiState(state=state.value,loading=false),{},{},{},{},{},{},{},{},{},null,{})
        } } }
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("star-fixture"))
        compose.onNodeWithTag("star-fixture").performClick()
        assertEquals("fixture", state.value.starredStories.single().itemId)
        screenshot("v083-feed-stars")
    }
    @Test fun savedCollectionDoesNotSilentlyFilterFollowingTab() {
        val state = FeedState(items=listOf(story("one","Saved for work"),story("two","Following without bookmark")),
            savedIds=setOf("one"),savedCollections=mapOf("one" to "Work"),starredStories=listOf(StarredStory("two")))
        compose.setContent { MeBotTheme { SavedFeed(state, {}, {}, {}) } }
        compose.onNodeWithTag("library-filter-collection-name:Work").performClick()
        compose.onNodeWithTag("library-starred").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("item-two"))
        compose.onNodeWithTag("item-two").assertIsDisplayed()
        compose.onNodeWithTag("saved-library").performScrollToIndex(0)
        compose.onNodeWithTag("library-saved").performClick()
        compose.onNodeWithTag("library-filter-collection-name:Work").assertIsSelected()
    }
    @Test fun collectionNamesMayMatchFilterSentinels() {
        val state = FeedState(items=listOf(story("one","First collection"),story("two","Second collection")),
            savedIds=setOf("one","two"),savedCollections=mapOf("one" to "all","two" to "unfiled"))
        compose.setContent { MeBotTheme { SavedFeed(state, {}, {}, {}) } }
        compose.onNodeWithTag("library-filter-collection-name:all").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("item-one"))
        compose.onNodeWithTag("item-two").assertDoesNotExist()
        compose.onNodeWithTag("saved-library").performScrollToIndex(0)
        compose.onNodeWithTag("library-filter-collection-name:unfiled").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("item-two"))
        compose.onNodeWithTag("item-one").assertDoesNotExist()
    }
    @Test fun collectionDraftSurvivesActivityStateRestorationUntilExplicitCancel() {
        val state = FeedState(items=listOf(story("one","Saved story")),savedIds=setOf("one"))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MeBotTheme { CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(
            state=state,onCollection={_,_->})) { SavedFeed(state, {}, {}, {}) } } }
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-collection-one"))
        compose.onNodeWithTag("library-collection-one").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("collection-name"))
        compose.onNodeWithTag("collection-name").performTextInput("Draft collection")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("collection-name"))
        compose.onNodeWithTag("collection-name").assertTextContains("Draft collection")
        compose.onNodeWithTag("saved-library").performScrollToNode(hasText("Cancel"))
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("collection-name").assertDoesNotExist()
    }
    @Test fun iconOnlyStarSitsBesideBookmarkWithoutManagementCopy() {
        val state = mutableStateOf(FeedState(items=listOf(story("one","A compact story"))))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(
            state=state.value,onStar={state.value=StarredStories.toggle(state.value,it,100)})) {
            StoryRow(state.value.items.single(),false,{}, {}, null)
        } } }
        val star = compose.onNodeWithTag("star-one")
        val bookmark = compose.onNodeWithTag("save-one")
        star.assertContentDescriptionEquals("Star and follow this story")
        star.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val starBounds = star.fetchSemanticsNode().boundsInRoot
        val bookmarkBounds = bookmark.fetchSemanticsNode().boundsInRoot
        assertEquals(starBounds.top, bookmarkBounds.top, 1f)
        assertEquals(starBounds.right, bookmarkBounds.left, 1f)
        star.performClick().assertIsSelected().assertContentDescriptionEquals("Unstar this story")
        compose.onNodeWithTag("star-settings-one").assertDoesNotExist()
        compose.onNodeWithText("Starred", substring=true).assertDoesNotExist()
    }
    @Test fun emptyFollowingExplainsStarsAndSavedExplainsBookmarks() {
        compose.setContent { MeBotTheme { SavedFeed(FeedState(), {}, {}, {}) } }
        compose.onNodeWithTag("library-starred").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasText("Follow a story"))
        compose.onNodeWithText("Follow a story").assertIsDisplayed()
        compose.onNodeWithText("Tap the bookmark on an update.", substring=true).assertDoesNotExist()
        compose.onNodeWithTag("saved-library").performScrollToIndex(0)
        compose.onNodeWithTag("library-saved").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasText("Keep something useful"))
        compose.onNodeWithText("Keep something useful").assertIsDisplayed()
        compose.onNodeWithText("Follow a story").assertDoesNotExist()
    }
    @Test fun mountingShortFeedDoesNotAutomaticallySpendRequests() {
        var refreshes = 0
        val state = FeedState(items=listOf(story("one","One source")),readingPreferences=ReadingPreferences(autoLoadMore=true))
        compose.setContent { MeBotTheme { FeedHome(FeedUiState(state=state,loading=false),{},{},{},{},{},{refreshes++},{},{},{},null,{}, onLoadMore={refreshes++}) } }
        compose.waitForIdle()
        assertEquals(0,refreshes)
    }
    @Test fun newResultBannerPlacesNewStoryBeforePreviouslyRankedOldStory() {
        val state = mutableStateOf(FeedState(items=listOf(story("old","Existing story")),readingPreferences=ReadingPreferences(autoLoadMore=false)))
        compose.setContent { MeBotTheme { FeedHome(FeedUiState(state=state.value,loading=false),{},{},{},{},{},{},{},{},{},null,{}) } }
        compose.runOnIdle { state.value = state.value.copy(items=state.value.items+story("new","Fresh development")) }
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-new-results"))
        compose.onNodeWithTag("feed-new-results").performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-new"))
        compose.onNodeWithTag("item-new").assertIsDisplayed()
        compose.onNodeWithTag("feed-new-results").assertDoesNotExist()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window,it,{status -> result=status},Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS,result)
            }
        }
        val file=File("build/outputs/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
}
