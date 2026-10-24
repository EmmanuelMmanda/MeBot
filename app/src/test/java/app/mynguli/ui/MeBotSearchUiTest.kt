package app.mynguli.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.ui.feed.SearchScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val modules = listOf(FeedModule(id = "learning", kind = "learning"), FeedModule(id = "weather", kind = "weather"),
        FeedModule(id = "news", kind = "news"), FeedModule(id = "horoscope", kind = "horoscope", zodiacSign = "aries"),
        FeedModule(id = "media", kind = "media", enabled = false))
    private val ui = mutableStateOf(FeedUiState(state = FeedState(modules = modules), loading = false, connected = true))
    private val searches = mutableListOf<Pair<String, String?>>()
    private val opened = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private var cancelled = 0
    private var connections = 0

    @Test fun keywordAndScopeSurviveRotationAndKeyboardSearchSubmitsOnlyOnce() {
        show()
        compose.onNodeWithTag("search-no-results").assertDoesNotExist()
        compose.onNodeWithTag("search-filter-weather").assertDoesNotExist()
        compose.onNodeWithTag("search-filter-horoscope").assertDoesNotExist()
        compose.onNodeWithTag("search-filter-media").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("search-filter-learning").fetchSemanticsNode().boundsInRoot.left <
            compose.onNodeWithTag("search-filter-news").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("search-query").performTextInput("PostgreSQL replication")
        compose.onNodeWithTag("search-filter-news").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-query").assertTextContains("PostgreSQL replication")
        compose.onNodeWithTag("search-filter-news").assertIsSelected()
        compose.onNodeWithTag("search-query").performImeAction()
        assertEquals(listOf("PostgreSQL replication" to "news"), searches)
        compose.onNodeWithTag("search-submit").assertIsNotEnabled()
        node("search-cancel").performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithTag("search-query").assertTextContains("PostgreSQL replication")
        compose.onNodeWithTag("search-submit").assertIsEnabled()
        compose.onNodeWithTag("search-query").performTextReplacement("PostgreSQL migration")
        compose.onNodeWithTag("search-filter-learning").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-query").assertTextContains("PostgreSQL migration")
        compose.onNodeWithTag("search-filter-learning").assertIsSelected()
        assertEquals(1, searches.size)
    }

    @Test fun errorRetryRetainsTheQueryAndEmptyCompletionIsDifferentFromFirstUse() {
        show()
        compose.onNodeWithTag("search-query").performTextInput("A current research topic")
        compose.onNodeWithTag("search-submit").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(searching = false, searchError = "offline") }
        nodeByText(compose.activity.getString(R.string.feed_retry)).performClick()
        assertEquals(2, searches.size)
        assertEquals(searches.first(), searches.last())
        compose.runOnIdle { ui.value = ui.value.copy(searching = false, searchError = null, searchCompleted = true, searchResults = emptyList()) }
        node("search-no-results").assertIsDisplayed()
        compose.onNodeWithTag("search-intro").assertDoesNotExist()
        compose.onNodeWithTag("search-query").assertTextContains("A current research topic")
    }

    @Test fun resultSourceCitationSaveAndDetailActionsUseActualResultData() {
        val first = FeedItem("example-guide", "learning", "Example replication guide", "A synthetic guide used to verify the search interface.",
            sourceName = "Example documentation", sourceUrl = "https://example.com/guide", kind = "learning", contentType = "course",
            citations = listOf(FeedCitation("Example documentation", "https://example.com/guide"), FeedCitation("Example release notes", "https://example.com/releases")))
        ui.value = ui.value.copy(searchQuery = "Database replication", searchCompleted = true, searchResults = listOf(first),
            state = ui.value.state!!.copy(items = listOf(first)))
        show()
        screenshot("52-mebot-search-v06")
        node("search-source-example-guide").performClick()
        val source = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, source.action)
        assertEquals(first.sourceUrl, source.dataString)
        node("search-save-example-guide").performClick()
        assertEquals(listOf(first.id), saved)
        assertTrue(first.id in ui.value.state!!.savedIds)
        node("search-item-example-guide").performClick()
        assertEquals(listOf(first.id), opened)
        node("search-sources-example-guide").performClick()
        node("search-citation-example-guide-1").performClick()
        assertEquals("https://example.com/releases", shadowOf(compose.activity).nextStartedActivity.dataString)
    }

    @Test fun unavailableConnectionShowsARealRouteAndDoesNotPretendToSearch() {
        ui.value = ui.value.copy(connected = false)
        show()
        compose.onNodeWithTag("search-query").performTextInput("Useful developer tools")
        compose.onNodeWithTag("search-submit").assertIsNotEnabled()
        node("search-connect").performClick()
        assertEquals(1, connections)
        assertTrue(searches.isEmpty())
    }

    private fun show() {
        restoration.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SearchScreen(ui.value, onSearch = { query, module ->
                    searches += query to module
                    ui.value = ui.value.copy(searchQuery = query, searchModuleId = module, searching = true, searchError = null)
                }, onItem = { opened += it }, onSave = { id ->
                    saved += id
                    ui.value = ui.value.copy(state = ui.value.state!!.copy(savedIds = ui.value.state!!.savedIds + id))
                }, onConnect = { connections++ }, onCancel = { cancelled++; ui.value = ui.value.copy(searching = false) })
            } }
        }
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun nodeByText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("search-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
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
