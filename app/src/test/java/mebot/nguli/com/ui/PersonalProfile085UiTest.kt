package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalProfile085UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)

    @Test fun exampleIsExplicitAndReplacementConfirmationSurvivesRestoration() {
        restoration.setContent {
            var text by rememberSaveable { mutableStateOf("My own details") }
            MeBotTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState())) {
                PersonalProfileEditor085(text, true, { text = it })
            } } }
        }
        compose.onNodeWithTag("context-edit").assertTextContains("My own details")
        compose.onNodeWithTag("context-edit-example").performClick()
        compose.onNodeWithTag("context-edit-insert-example").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("context-edit-cancel-example").performClick()
        compose.onNodeWithTag("context-edit").assertTextContains("My own details")
        compose.onNodeWithTag("context-edit-example").performClick()
        compose.onNodeWithTag("context-edit-insert-example").performClick()
        compose.onNodeWithTag("context-edit-confirm-example").performClick()
        compose.onNodeWithTag("context-edit").assertTextContains(compose.activity.getString(R.string.profile_example_085))
        compose.onNodeWithTag("context-edit").performTextReplacement("na ".repeat(151))
        compose.onNodeWithTag("context-edit-limit").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("context-edit").assertTextContains("na ".repeat(151))
        compose.onNodeWithTag("context-edit").performTextReplacement("Napenda kilimo na ufugaji")
        compose.onNodeWithTag("context-edit-limit").assertDoesNotExist()
        screenshot("v085-profile-editor")
    }

    @Test @Config(qualifiers="w320dp-h400dp-xhdpi")
    fun exampleDialogControlsRemainReachableAtLargeTextAndNarrowWidth() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale=1.5f)) {
                MeBotTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState())) {
                    PersonalProfileEditor085("", true, {})
                } } }
            }
        }
        compose.onNodeWithTag("context-edit-example").performScrollTo().performClick()
        compose.onNodeWithTag("context-edit-insert-example").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("context-edit-dismiss-example").assertIsDisplayed().performClick()
        compose.onNodeWithTag("context-edit-example").performClick()
        compose.onNodeWithTag("context-edit-insert-example").performClick()
        compose.onNodeWithTag("context-edit-example-text").assertDoesNotExist()
    }

    @Test fun onboardingCustomInterestsRestoreValidateAndRetryWithoutFalseCompletion() {
        var calls=0; var starts=0; var saved=""; var scopes=emptySet<String>()
        restoration.setContent { MeBotTheme { Surface(Modifier.fillMaxSize()) {
            MeBotOnboarding(FeedState(modules=listOf(FeedModule(id="food",kind="food",enabled=true))), "en", {},
                onStart={starts++}, onConnect={}, onPreferences={ selected, _, text, done ->
                    calls++; saved=text; scopes=selected; done(calls>1)
                })
        } } }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-preferences").performScrollTo().performTextInput("na ".repeat(151))
        compose.onNodeWithTag("onboarding-start").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding-preferences").performTextReplacement("Nataka kujifunza mapishi ya nyumbani")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-preferences").assertTextContains("Nataka kujifunza mapishi ya nyumbani")
        screenshot("v085-custom-interests")
        compose.onNodeWithTag("onboarding-start").performClick()
        assertEquals(1,calls); assertEquals(0,starts)
        compose.onNodeWithText(compose.activity.getString(R.string.interests_save_error)).assertExists()
        compose.onNodeWithTag("onboarding-start").performClick()
        assertEquals(2,calls); assertEquals(1,starts)
        assertEquals("Nataka kujifunza mapishi ya nyumbani",saved)
        assertEquals(setOf("food"),scopes)
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        var result=-1
        compose.runOnIdle { PixelCopy.request(compose.activity.window,bitmap,{result=it},Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { result!=-1 }
        assertEquals(PixelCopy.SUCCESS,result)
        val target=File("build/outputs/screenshots/$name.png");target.parentFile.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
