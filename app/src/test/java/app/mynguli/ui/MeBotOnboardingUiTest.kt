package app.mynguli.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.mynguli.R
import app.mynguli.core.FeedState
import app.mynguli.core.Settings
import app.mynguli.feed.FeedTestApplication
import app.mynguli.ui.feed.FeedOnboarding
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotOnboardingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)

    @Test fun sixStepsKeepNavigationExplicitAndOfferBothConnectionAndDirectEntry() {
        var opened = 0
        var connected = 0
        compose.setContent {
            MeBotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FeedOnboarding(FeedState(), "en", {}, { opened++ }, { connected++ })
                }
            }
        }
        val titles = listOf(R.string.tour_feed_title, R.string.tour_modules_title, R.string.tour_explain_title,
            R.string.tour_search_title, R.string.tour_tools_title, R.string.tour_context_title)
        titles.forEachIndexed { index, title ->
            compose.onNodeWithTag("onboarding-step-$index").assertExists()
            compose.onNodeWithText(compose.activity.getString(title)).assertExists()
            compose.onNodeWithTag("onboarding-progress").assertTextEquals(compose.activity.getString(R.string.tour_step, index + 1, 6))
            compose.onNodeWithTag("onboarding-start").performScrollTo().assertIsDisplayed()
            assertEquals(0, opened)
            assertEquals(0, connected)
            when (index) {
                0 -> screenshot("63-mebot-welcome-v061")
                1 -> screenshot("64-mebot-tour-modules-v061")
                5 -> screenshot("65-mebot-tour-context-v061")
            }
            if (index < titles.lastIndex) compose.onNodeWithTag("onboarding-next").performScrollTo().performClick()
        }
        // Both the system gesture and the visible Back button revisit the previous step.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("onboarding-step-4").assertExists()
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-step-3").assertExists()
        repeat(2) { compose.onNodeWithTag("onboarding-next").performScrollTo().performClick() }
        compose.onNodeWithTag("onboarding-finish").performScrollTo().performClick()
        assertEquals(1, connected)
        assertEquals(0, opened)
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun skipIsAvailableImmediatelyAndStepSurvivesLanguageThemeAndRestoration() {
        val language = mutableStateOf("en")
        val dark = mutableStateOf(false)
        var opened = 0
        var connected = 0
        restoration.setContent {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedConfiguration = remember(configuration, language.value) {
                Configuration(configuration).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                }
            }
            val localized = remember(context, localizedConfiguration) {
                context.createConfigurationContext(localizedConfiguration)
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localizedConfiguration,
                LocalResources provides localized.resources) {
                MeBotTheme(Settings(language = language.value, theme = if (dark.value) "dark" else "light")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        FeedOnboarding(FeedState(), language.value, { language.value = it }, { opened++ }, { connected++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        assertEquals(1, opened)
        repeat(3) { compose.onNodeWithTag("onboarding-next").performScrollTo().performClick() }
        compose.onNodeWithTag("onboarding-language").performClick()
        assertEquals("sw", language.value)
        compose.runOnIdle { dark.value = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-step-3").assertExists()
        val sw = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("sw"))
        })
        compose.onNodeWithText(sw.getString(R.string.tour_search_title)).assertExists()
        compose.onNodeWithTag("onboarding-progress").assertTextEquals(sw.getString(R.string.tour_step, 4, 6))
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        assertEquals(2, opened)
        assertEquals(0, connected)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowSwahiliTourKeepsEveryActionReachableAtLargeTextSize() {
        var opened = 0
        compose.setContent {
            MeBotTheme(Settings(language = "sw", theme = "dark")) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        FeedOnboarding(FeedState(), "sw", {}, { opened++ }, {})
                    }
                }
            }
        }
        screenshot("66-mebot-welcome-small-sw-v061")
        repeat(5) { index ->
            compose.onNodeWithTag("onboarding-step-$index").assertExists()
            compose.onNodeWithTag("onboarding-start").assertIsDisplayed()
            compose.onNodeWithTag("onboarding-next").assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("onboarding-finish").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-start").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(2_000)
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
}
