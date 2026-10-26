package mebot.nguli.com.ui

import android.content.ClipboardManager
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
import mebot.nguli.com.ui.feed.ContextSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native actual Activity/VM/DataStore journey. No external provider calls or user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextImport092JourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication
    @Test fun englishImportRetriesAndPersistsAfterReview()=journey("en")
    @Test @Config(qualifiers="sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliImportRetriesAndPersistsAfterReview()=journey("sw")
    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowLargeTextImportKeepsActionsReachable(){RuntimeEnvironment.setFontScale(1.3f);compose.activityRule.scenario.recreate();journey("sw","sw-stress")}
    private fun journey(language:String,suffix:String=language){
        compose.waitForIdle()
        val original=ContextEntry(id="original",text="Existing local note",scopes=setOf("news"))
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update{AppState(profile=Profile(onboardingComplete=true),settings=Settings(language=language))}
            app.feedRepository.update{FeedState(onboardingComplete=true,quickGuideSeen=true,interestsConfigured=true,timezone="UTC",context=listOf(original),modules=ModuleCatalog.mergeCatalog(listOf(FeedModule(id="news",kind="news",query="Fixture local market news"))))}
        }
        waitTag("feed-screen");compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.preferences_title_083)).performScrollTo().performClick()
        waitTag("context-region")
        compose.onNodeWithTag("context-region").assertIsDisplayed()
        capture("context-visible-$suffix")
        compose.onNodeWithText(text(R.string.context_import_title_092)).performScrollTo().performClick()
        waitTag("context-import-screen")
        capture("context-import-$suffix")
        compose.onNodeWithTag("context-starter-prompt").performScrollTo().performClick()
        val clip=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(ContextSetup.prompt(compose.activity),clip);assertFalse(clip.contains(original.text))
        compose.onNodeWithTag("context-paste-input").performScrollTo().performTextInput("{ invalid JSON }")
        compose.onNodeWithTag("context-paste-input").assertTextContains("{ invalid JSON }")
        compose.onNodeWithTag("context-paste-review").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        val feedVm = androidx.lifecycle.ViewModelProvider(compose.activity)[mebot.nguli.com.feed.FeedViewModel::class.java]
        compose.waitUntil(10_000) { !feedVm.ui.value.importWorking && feedVm.ui.value.error != null }
        assertEquals("import_format", feedVm.ui.value.error)
        compose.onNodeWithText(text(R.string.polish_import_format)).performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate();waitTag("context-paste-input")
        compose.onNodeWithTag("context-paste-input").performScrollTo().assertTextContains("{ invalid JSON }")
        if (language == "en") {
            RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-xhdpi");compose.activityRule.scenario.recreate();waitTag("context-paste-input")
            compose.onNodeWithTag("context-paste-input").performScrollTo().assertTextContains("{ invalid JSON }")
            compose.onNodeWithTag("context-paste-review").performScrollTo().assertIsDisplayed()
            RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-xhdpi");compose.activityRule.scenario.recreate();waitTag("context-paste-input")
        }
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitTag("context-region")
        compose.onNodeWithText(text(R.string.context_import_title_092)).performScrollTo().performClick()
        compose.onNodeWithTag("context-paste-input").performScrollTo().assertTextContains("{ invalid JSON }")
        val modulesBeforeImport = runBlocking { app.feedRepository.current() }.modules
        val valid=ContextSetup.example("Synthetic preference for practical business news")
        compose.onNodeWithTag("context-paste-input").performTextReplacement(valid)
        compose.onNodeWithTag("context-paste-review").performScrollTo().performClick();waitTag("import-review")
        assertEquals(listOf(original),runBlocking{app.feedRepository.current()}.context)
        compose.onNodeWithTag("import-cancel").performClick()
        compose.onNodeWithTag("context-paste-input").performScrollTo().assertTextContains(valid)
        compose.onNodeWithTag("context-paste-review").performScrollTo().performClick();waitTag("import-review")
        compose.onNodeWithTag("import-confirm").performClick()
        compose.waitUntil(10_000){runBlocking{app.feedRepository.current()}.context.any{it.text=="Synthetic preference for practical business news"}}
        val saved=runBlocking{app.feedRepository.current()}
        assertEquals(setOf("news"),saved.context.single().scopes);assertFalse(saved.context.single().global)
        assertEquals(modulesBeforeImport,saved.modules);assertEquals(0,saved.requestsToday)
        compose.activityRule.scenario.recreate();waitTag("context-import-screen")
        assertEquals(saved.context,runBlocking{app.feedRepository.current()}.context)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick();waitTag("context-region")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("nav-you").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick();waitTag("connection-screen")
        capture("connection-$suffix")
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsDisplayed()
    }
    private fun text(id:Int)=compose.activity.getString(id)
    private fun waitTag(tag:String){compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun capture(name:String){
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle();val window=compose.activity.window;val view=window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);var status=-1
        compose.runOnIdle{PixelCopy.request(window,bitmap,{status=it},Handler(Looper.getMainLooper()))}
        compose.waitUntil(10_000){status!=-1};assertEquals(PixelCopy.SUCCESS,status)
        File("build/outputs/screenshots/v092/$name.png").also{it.parentFile.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
