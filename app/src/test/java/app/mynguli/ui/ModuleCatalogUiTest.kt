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

/** Permission and pending-state checks use synthetic context; no external AI is called. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleCatalogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val module = FeedModule(id = "security", kind = "security", enabled = true)
    private val note = ContextEntry(id = "systems", text = "I maintain Linux servers and PostgreSQL databases.", scopes = setOf("news"))
    private val ui = mutableStateOf(FeedUiState(state = FeedState(onboardingComplete = true, modules = listOf(module), context = listOf(note)), loading = false))
    private val saves = mutableListOf<Pair<FeedModule, Set<String>>>()
    private var finished = 0

    @Test fun changingOnlyPersonalizationWaitsForScopePersistenceBeforeClosing() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(context = listOf(note,
            ContextEntry(id = "unconfirmed", text = "Unconfirmed private guess", confirmed = false, scopes = setOf("security")))))
        showEditor()
        compose.onNodeWithTag("module-note-systems").assertDoesNotExist()
        compose.onNodeWithTag("module-personalization").performScrollTo().performClick()
        compose.onNodeWithTag("module-note-unconfirmed").assertDoesNotExist()
        compose.onNodeWithTag("module-note-systems").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("systems"), saves.single().second)
        assertEquals(module, saves.single().first)
        assertEquals(0, finished)
        compose.onNodeWithTag("module-save").assertIsNotEnabled()

        compose.runOnIdle {
            ui.value = ui.value.copy(state = ui.value.state!!.copy(context = listOf(note.copy(scopes = setOf("news", "security")))))
        }
        compose.waitForIdle()
        assertEquals("Matching values must wait until the operation finishes", 0, finished)
        compose.onNodeWithTag("module-save").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(moduleSavingId = null) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("The persisted permission update closes the editor once", 1, finished)
            assertEquals(setOf("news", "security"), ui.value.state!!.context.single().scopes)
        }
    }

    @Test fun failedSaveKeepsFocusAndChosenNotesForRetry() {
        showEditor()
        compose.onNodeWithTag("module-topic").performScrollTo().performTextInput("Linux and database advisories")
        compose.onNodeWithTag("module-personalization").performScrollTo().performClick()
        compose.onNodeWithTag("module-note-systems").performScrollTo().performClick()
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        compose.runOnIdle { ui.value = ui.value.copy(error = "storage_error", moduleSavingId = null) }
        compose.onNodeWithTag("module-save").assertIsEnabled()
        compose.onNodeWithTag("module-topic").performScrollTo().assertTextContains("Linux and database advisories")
        compose.onNodeWithTag("module-note-systems").performScrollTo().assertIsOn()
        assertEquals(0, finished)
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        assertEquals(2, saves.size)
        assertEquals(saves.first(), saves.last())
    }

    @Test fun categoryBrowsingAndTogglesReflectCommittedConfiguration() {
        val state = mutableStateOf(FeedState(onboardingComplete = true))
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val edits = mutableListOf<String?>()
        compose.setContent {
            MeBotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModulesScreen(state.value, AppState(), onEdit = { edits += it }, onToggle = { id, value -> toggles += id to value }, onUtility = {}, onUtilityToggle = { _, _ -> })
                }
            }
        }
        screenshot("42-mebot-modules-v5")
        compose.onNodeWithText(text(R.string.module_group_life)).performScrollTo().performClick()
        compose.onNodeWithTag("module-toggle-weather").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("weather" to true), toggles)
        compose.onNodeWithTag("module-toggle-weather").assertIsOff()
        compose.runOnIdle { state.value = state.value.copy(modules = state.value.modules.map { if (it.id == "weather") it.copy(enabled = true) else it }) }
        compose.onNodeWithTag("module-toggle-weather").performScrollTo().assertIsOn()
        compose.onNodeWithTag("module-weather").performClick()
        assertEquals(listOf<String?>("weather"), edits)
        compose.onNodeWithTag("module-alarm").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun compactSwahiliPersonalizationKeepsSaveAndLongNotesReachable() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(context = listOf(note.copy(text = "Ninasimamia seva za Linux na hifadhidata za PostgreSQL, na ninahitaji taarifa sahihi kuhusu marekebisho ya usalama."))))
        showEditor(Settings(theme = "dark", language = "sw"), 1.3f)
        compose.onNodeWithTag("module-personalization").performScrollTo().performClick()
        compose.onNodeWithTag("module-note-systems").performScrollTo().performClick()
        screenshot("43-mebot-module-personalization-small-sw")
        compose.onNodeWithTag("module-save").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(setOf("systems"), saves.single().second)
    }

    private fun showEditor(settings: Settings = Settings(), fontScale: Float = 1f) {
        compose.setContent {
            MeBotTheme(settings) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ModuleEditor(module, ui.value, false, onSave = { fail("Personalization must be part of the same save") }, onRemove = {}, onDone = { finished++ },
                            onSavePersonalized = { value, ids ->
                                saves += value to ids
                                ui.value = ui.value.copy(moduleSavingId = value.id, error = null)
                            })
                    }
                }
            }
        }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(5_000)
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
