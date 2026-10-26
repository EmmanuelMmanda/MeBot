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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mebot.nguli.com.MeBotNavigation
import mebot.nguli.com.feed.FeedJson
import mebot.nguli.com.core.*
import mebot.nguli.com.ui.feed.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native UI using dated public reading fixtures, never live provider inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryModules085UiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val items=FeedJson.decodeFromString<List<FeedItem>>(javaClass.getResourceAsStream("/release-082/public-items.json")!!.bufferedReader().use{it.readText()}).take(2).map { it.copy(media=null) }
    private val state=FeedState(items=items,savedIds=items.map { it.id }.toSet(),savedCollections=mapOf(items.first().id to "Community"),
        starredStories=listOf(StarredStory(items.first().id,starredAtMillis=items.first().fetchedAtMillis)),
        modules=ModuleCatalog.defaults.map { it.copy(enabled=it.kind in setOf("news","business","learning")) })

    @Test fun compactHeadersAndReadingHierarchyInEnglish() = journey("en",1f,"en")
    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun smallSwahiliLargeTextKeepsFiltersAndFollowingReachable() = journey("sw",1.3f,"small-sw-large")

    private fun journey(language:String,fontScale:Float,suffix:String) {
        val page=mutableStateOf("modules")
        compose.setContent { MeBotTheme(Settings(language=language)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,fontScale), LocalPublisherImageLoader provides {null},
                LocalFeedClock provides items.first().fetchedAtMillis, LocalRelevanceContext provides RelevanceUiContext(state,onStar={},onConfigureStar={_,_,_->},onCollection={_,_->})) {
                Scaffold(bottomBar={MeBotNavigation(page.value,{page.value=it})}) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
                    if(page.value=="modules") ModulesScreen(state,AppState(),{},{_,_->},{},{_,_->})
                    else SavedFeed(state,{},{},{page.value="modules"})
                } }
            }
        } }
        compose.onNodeWithTag("module-search").assertIsDisplayed()
        val statusBounds = compose.onNodeWithTag("module-status-rail").getUnclippedBoundsInRoot()
        assertTrue(statusBounds.bottom - statusBounds.top <= 72.dp)
        val categoryBounds = compose.onNodeWithTag("module-category-rail").getUnclippedBoundsInRoot()
        assertTrue(categoryBounds.bottom - categoryBounds.top <= 72.dp)
        capture("modules-$suffix")
        compose.onNodeWithTag("module-category-life").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("module-status-disabled").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("module-compare-prices").assertDoesNotExist()
        compose.runOnIdle { page.value="saved" }
        compose.onNodeWithTag("library-search").assertIsDisplayed()
        capture("saved-$suffix")
        compose.onNodeWithTag("library-starred").performScrollTo().performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("star-active-${items.first().id}"))
        compose.onNodeWithTag("star-active-${items.first().id}").assertIsDisplayed()
        capture("following-$suffix")
    }

    @Test fun horizontalModuleFiltersAndSearchSurviveRestoration() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MeBotTheme { ModulesScreen(state,AppState(),{},{_,_->},{},{_,_->}) } }
        compose.onNodeWithTag("module-status-disabled").performScrollTo().performClick()
        compose.onNodeWithTag("module-category-growth").performScrollTo().performClick()
        compose.onNodeWithTag("module-search").performScrollTo().performTextInput("farming")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("module-search").assertTextContains("farming")
        compose.onNodeWithTag("module-status-disabled").assertIsSelected()
        compose.onNodeWithTag("module-category-growth").assertIsSelected()
        compose.onNodeWithTag("module-farming").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        val bitmap=compose.runOnIdle {
            val window=compose.activity.window;window.decorView.invalidate()
            Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888).also {
                var result:Int?=null;PixelCopy.request(window,it,{status->result=status},Handler(Looper.getMainLooper()));assertEquals(PixelCopy.SUCCESS,result)
            }
        }
        val file=File("build/outputs/screenshots/v085/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
}
