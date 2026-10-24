package app.mynguli.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.ui.feed.ModuleEditor
import app.mynguli.ui.feed.ModulesScreen
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
class ModulePolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val news = FeedModule(id = "news", kind = "news", query = "A selected news topic")
    private val weather = FeedModule(id = "weather", kind = "weather", enabled = false)
    private val learning = FeedModule(id = "learning", kind = "learning", preferences = mapOf("level" to "advanced"))
    private val security = FeedModule(id = "security", kind = "security")

    @Test fun holdingAndDraggingAHandleCommitsOneFullOrderWithoutChangingModuleChoices() {
        val initial = listOf(news, weather, learning, security)
        val state = mutableStateOf(FeedState(modules = initial))
        val submitted = mutableListOf<List<String>>()
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModulesScreen(state.value, AppState(), {}, { _, _ -> }, {}, { _, _ -> }, onReorder = { ids ->
                    submitted += ids
                    state.value = state.value.copy(modules = ModuleOrder.reorder(state.value.modules, ids))
                })
            } }
        }
        val from = compose.onNodeWithTag("module-order-news").fetchSemanticsNode().boundsInRoot.center.y
        val to = compose.onNodeWithTag("module-order-learning").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNodeWithTag("module-drag-news").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, to - from))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf("learning", "weather", "news", "security")), submitted)
        assertEquals(listOf(learning, weather, news, security), state.value.modules)
        compose.onNodeWithTag("module-order-pending").assertDoesNotExist()
        assertTrue(top("learning") < top("news"))
        screenshot("50-mebot-module-order-v06")
    }

    @Test fun failedOrderPersistenceRollsBackAndTheMoveMenuCanRetry() {
        val initial = listOf(news, learning, security)
        val state = mutableStateOf(FeedState(modules = initial))
        val error = mutableStateOf<String?>(null)
        val submitted = mutableListOf<List<String>>()
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModulesScreen(state.value, AppState(), {}, { _, _ -> }, {}, { _, _ -> }, error = error.value,
                    onDismissError = { error.value = null }, onReorder = { submitted += it })
            } }
        }
        compose.onNodeWithTag("module-drag-news").performClick()
        compose.onNodeWithTag("module-down-news").performClick()
        compose.onNodeWithTag("module-order-pending").assertExists()
        compose.onNodeWithTag("module-toggle-news").assertIsNotEnabled()
        compose.runOnIdle { error.value = "storage_error" }
        compose.onNodeWithTag("module-order-pending").assertDoesNotExist()
        assertTrue(top("news") < top("learning"))
        assertEquals(initial, state.value.modules)
        compose.onNodeWithTag("module-drag-news").performScrollTo().performClick()
        compose.onNodeWithTag("module-down-news").performClick()
        assertEquals(2, submitted.size)
        assertEquals(submitted.first(), submitted.last())
        compose.runOnIdle { state.value = state.value.copy(modules = ModuleOrder.reorder(state.value.modules, submitted.last())) }
        compose.onNodeWithTag("module-order-pending").assertDoesNotExist()
        assertTrue(top("learning") < top("news"))
    }

    @Test fun horoscopeNeedsAnExplicitEnableAndSavesSignAndReflectionPreferencesTogether() {
        val module = FeedModule(id = "horoscope", kind = "horoscope", enabled = false)
        val ui = mutableStateOf(FeedUiState(state = FeedState(modules = listOf(module)), loading = false))
        val saved = mutableListOf<FeedModule>()
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModuleEditor(module, ui.value, false, {
                    saved += it
                    ui.value = ui.value.copy(moduleSavingId = it.id, error = null)
                }, {}, {})
            } }
        }
        assertEquals(signTop("aries"), signTop("gemini"), 1f)
        compose.onNodeWithTag("module-editor-enabled").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        assertTrue(saved.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.feed_zodiac_required)).assertExists()
        compose.onNodeWithTag("module-sign-aries").performScrollTo().performClick()
        compose.onNodeWithTag("module-pref-focus").performScrollTo().performClick()
        compose.onNodeWithTag("module-pref-focus-work").performClick()
        compose.onNodeWithTag("module-pref-tone").performScrollTo().performClick()
        compose.onNodeWithTag("module-pref-tone-playful").performClick()
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        val result = saved.single()
        assertTrue(result.enabled)
        assertEquals("aries", result.zodiacSign)
        assertEquals(mapOf("focus" to "work", "tone" to "playful"), result.preferences)
        assertFalse(module.enabled)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun compactHoroscopeSetupKeepsAllSignsAndPreferencesReachable() {
        val module = FeedModule(id = "horoscope", kind = "horoscope", enabled = false, zodiacSign = "sagittarius")
        compose.setContent {
            MeBotTheme(Settings(theme = "dark", language = "sw")) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ModuleEditor(module, FeedUiState(state = FeedState(modules = listOf(module)), loading = false), false, {}, {}, {})
                    }
                }
            }
        }
        assertEquals(signTop("aries"), signTop("taurus"), 1f)
        assertTrue(signTop("gemini") > signTop("aries"))
        compose.onNodeWithTag("module-sign-sagittarius").performScrollTo().assertIsSelected()
        screenshot("51-mebot-horoscope-small-sw-v06")
        compose.onNodeWithTag("module-pref-tone").performScrollTo().performClick()
        compose.onNodeWithTag("module-pref-tone-playful").performClick()
        compose.onNodeWithTag("module-save").performScrollTo().assertIsDisplayed()
    }

    private fun top(id: String) = compose.onNodeWithTag("module-order-$id").fetchSemanticsNode().boundsInRoot.top
    private fun signTop(sign: String) = compose.onNodeWithTag("module-sign-$sign").fetchSemanticsNode().boundsInRoot.top
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
