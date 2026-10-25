package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Everyday083UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)

    @Test fun interestSearchSelectsNewModuleAndPreservesSelectionAfterRestore() {
        restoration.setContent {
            var selected by rememberSaveable { mutableStateOf(arrayListOf("news")) }
            MeBotTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState())) {
                InterestPicker083(selected, true) { selected = ArrayList(it) }
            } } }
        }
        compose.onNodeWithTag("onboarding-interest-search").performTextInput("cooking")
        compose.onNodeWithTag("onboarding-interest-food").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-interest-food").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-interest-food").assertIsSelected()
        compose.onNodeWithTag("onboarding-interest-search").performScrollTo().performTextReplacement("notafoundtopic")
        compose.onNodeWithTag("onboarding-interest-food").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-interest-search").performTextReplacement("cooking")
        compose.onNodeWithTag("onboarding-interest-food").assertIsSelected()
        screenshot("v083-interests")
    }

    @Test fun calculatorValidatesComputesRestoresAndClearsWithoutNetwork() {
        restoration.setContent { MeBotTheme { Surface(Modifier.fillMaxSize()) { UnitPriceScreen() } } }
        compose.onNodeWithTag("unit-price-compare").performScrollTo().performClick()
        compose.onNodeWithTag("unit-price-error").assertExists()
        compose.onNodeWithTag("unit-price-a-cost").performScrollTo().performTextInput("2500")
        compose.onNodeWithTag("unit-price-a-amount").performScrollTo().performTextInput("1")
        compose.onNodeWithTag("unit-price-b-cost").performScrollTo().performTextInput("6000")
        compose.onNodeWithTag("unit-price-b-amount").performScrollTo().performTextInput("3")
        compose.onNodeWithTag("unit-price-compare").performScrollTo().performClick()
        compose.onNodeWithTag("unit-price-result").assertExists()
        compose.onNodeWithTag("unit-price-error").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("unit-price-result").performScrollTo().assertIsDisplayed()
        screenshot("v083-price-comparison")
        compose.onNodeWithTag("unit-price-reset").performScrollTo().performClick()
        compose.onNodeWithTag("unit-price-result").assertDoesNotExist()
    }

    @Test fun modulesExposeOfflinePriceToolAndNewTopicsWithoutChangingSavedChoices() {
        var opened = ""
        compose.setContent { MeBotTheme { Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
            ModulesScreen(FeedState(),AppState(),{},{_,_->},{opened=it},{_,_->})
        } } }
        compose.onNodeWithTag("module-search").performTextInput("compare prices")
        compose.onNodeWithTag("module-compare-prices").performScrollTo().performClick()
        assertEquals("compare_prices", opened)
        compose.onNodeWithTag("module-search").performScrollTo().performTextReplacement("farming")
        compose.onNodeWithTag("module-farming").assertExists()
        compose.onNodeWithTag("module-toggle-farming").assertIsOff()
        screenshot("v083-module-filters")
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
