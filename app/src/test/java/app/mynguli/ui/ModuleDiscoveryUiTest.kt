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
class ModuleDiscoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disabledCatalogIncludesCustomTopicsAndSearchCanRecoverFromNoMatches() {
        val modules = ModuleCatalog.defaults + FeedModule(id = "custom", kind = "watch", title = "Database releases", query = "PostgreSQL", enabled = false)
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModulesScreen(FeedState(modules = modules), AppState(), {}, { _, _ -> }, {}, { _, _ -> }, initialFilter = "disabled")
            } }
        }
        compose.onNodeWithText(text(R.string.modules_filter_disabled)).assertIsSelected()
        compose.onNodeWithTag("module-news").assertDoesNotExist()
        compose.onNodeWithTag("module-weather").assertExists()
        compose.onNodeWithTag("module-custom").assertExists()
        screenshot("54-mebot-module-discovery-v061")
        compose.onNodeWithTag("module-search").performTextInput("postgres")
        compose.onNodeWithTag("module-custom").assertExists()
        compose.onNodeWithTag("module-weather").assertDoesNotExist()
        compose.onNodeWithTag("module-search").performTextReplacement("nothingmatcheshere")
        compose.onNodeWithTag("module-filters-reset").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.modules_filter_all)).assertIsSelected()
        compose.onNodeWithTag("module-news").assertExists()
        compose.onNodeWithTag("module-weather").assertExists()
    }

    @Test fun filteredOrderChangesOnlyTheMatchingEnabledRows() {
        val initial = listOf(
            FeedModule(id = "news", kind = "news", title = "Platform news"),
            FeedModule(id = "weather", kind = "weather", enabled = false),
            FeedModule(id = "learning", kind = "learning"),
            FeedModule(id = "security", kind = "security", title = "Platform security"),
        )
        val state = mutableStateOf(FeedState(modules = initial))
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ModulesScreen(state.value, AppState(), {}, { _, _ -> }, {}, { _, _ -> }, onReorder = { ids ->
                    state.value = state.value.copy(modules = ModuleOrder.reorder(state.value.modules, ids))
                })
            } }
        }
        compose.onNodeWithTag("module-search").performTextInput("platform")
        compose.onNodeWithTag("module-order-learning").assertDoesNotExist()
        compose.onNodeWithTag("module-drag-news").performScrollTo().performClick()
        compose.onNodeWithTag("module-down-news").performClick()
        compose.waitForIdle()
        assertEquals(listOf("security", "weather", "learning", "news"), state.value.modules.map { it.id })
        assertEquals(initial[1], state.value.modules[1])
        assertEquals(initial[2], state.value.modules[2])
        compose.onNodeWithTag("module-search-clear").performScrollTo().performClick()
        compose.onNodeWithTag("module-order-learning").assertExists()
        compose.onNodeWithTag("module-weather").assertExists()
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun defaultFocusCanBeRestoredWithoutErasingPreferencesOnNarrowLargeTextScreen() {
        val module = FeedModule(id = "security", kind = "security", query = "Linux and PostgreSQL", preferences = mapOf("urgency" to "critical"))
        val saved = mutableListOf<FeedModule>()
        compose.setContent {
            MeBotTheme(Settings(theme = "dark", language = "sw")) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ModuleEditor(module, FeedUiState(state = FeedState(modules = listOf(module)), loading = false), false,
                            { saved += it }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("module-default-context").performScrollTo().assertIsDisplayed()
        screenshot("55-mebot-module-defaults-small-sw-v061")
        compose.onNodeWithTag("module-default-reset").performScrollTo().performClick()
        compose.onNodeWithTag("module-default-reset").assertDoesNotExist()
        compose.onNodeWithTag("module-pref-urgency").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        assertEquals("", saved.single().query)
        assertEquals(module.preferences, saved.single().preferences)
        assertTrue(saved.single().enabled)
    }

    private fun text(id: Int) = compose.activity.getString(id)

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
