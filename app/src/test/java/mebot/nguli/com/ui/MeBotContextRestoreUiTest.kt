package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import mebot.nguli.com.R
import mebot.nguli.com.core.ContextEntry
import mebot.nguli.com.core.FeedModule
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.feed.FeedTestApplication
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.ui.feed.ContextScreen
import mebot.nguli.com.ui.feed.ModuleEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real ViewModels and DataStore; the dispatcher models a process dying before a queued save starts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotContextRestoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val app get() = compose.activity.application as FeedTestApplication
    private var models = ViewModelStore()
    private val selected = mutableStateOf<FeedViewModel?>(null)
    private var closed = 0
    private val entry = ContextEntry(id = "restore-note", text = "Original reading preference", scopes = setOf("learning"), updatedAtMillis = 1L)
    private val module = FeedModule(id = "learning", kind = "learning", enabled = true)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            app.feedRepository.update { FeedState(name = "Original name", modules = listOf(module), context = listOf(entry), personalSeedApplied = true) }
        }
        selected.value = model(models)
        compose.waitUntil(10_000) { selected.value!!.ui.value.state != null }
        assertEquals("Original name", selected.value!!.ui.value.state!!.name)
        assertEquals(listOf(entry), selected.value!!.ui.value.state!!.context)
    }

    @After fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun restoredUncommittedSaveKeepsTheDraftEditableAndOnlyClosesAfterARealRetryCommits() {
        restoration.setContent {
            val vm = selected.value!!
            val ui by vm.ui.collectAsState()
            MeBotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ContextScreen(ui, vm, onDone = { closed++ })
                }
            }
        }
        compose.onNodeWithTag("context-name").performScrollTo().performTextReplacement("Draft name")
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("Keep this unsaved learning preference")

        // saveContext publishes busy synchronously, while its actual mutation
        // remains queued on Main. No disk change has happened at this point.
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        assertTrue(selected.value!!.ui.value.contextSaving)
        compose.onNodeWithTag("context-save").assertIsNotEnabled()
        assertEquals(0, closed)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("context-save").performScrollTo().assertIsNotEnabled()

        // A new process has a new ViewModel, but restores the editor's draft and
        // pending intent. Cancelling the old scope must not commit its queued save.
        compose.runOnIdle { models.clear() }
        models = ViewModelStore()
        val replacement = model(models)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        paused.scheduler.runCurrent()
        compose.waitUntil(10_000) { replacement.ui.value.state != null }
        assertEquals("Original name", runBlocking { app.feedRepository.current().name })
        assertEquals(listOf(entry), runBlocking { app.feedRepository.current().context })
        compose.runOnIdle { selected.value = replacement }

        compose.onNodeWithTag("context-name").performScrollTo().assertIsEnabled().assertTextContains("Draft name")
        compose.onNodeWithTag("context-edit").performScrollTo().assertIsEnabled().assertTextContains("Keep this unsaved learning preference")
        compose.onNodeWithTag("context-save").performScrollTo().assertIsEnabled()
        assertEquals("An orphaned pending flag must not navigate as if saved", 0, closed)

        compose.onNodeWithTag("context-save").performClick()
        compose.waitUntil(10_000) { closed == 1 && !replacement.ui.value.contextSaving }
        val saved = runBlocking { app.feedRepository.current() }
        assertEquals("Draft name", saved.name)
        assertEquals("Keep this unsaved learning preference", saved.context.single().text)
        assertEquals(setOf("learning"), saved.context.single().scopes)
        assertEquals(entry.id, saved.context.single().id)
        assertEquals(1, closed)
    }

    @Test fun restoredUncommittedModuleSaveRetainsTopicPreferencesAndScopeUntilRetryCommits() {
        restoration.setContent {
            val vm = selected.value!!
            val ui by vm.ui.collectAsState()
            MeBotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModuleEditor(module, ui, false, onSave = { vm.saveModule(it) },
                        onRemove = { vm.removeModule(it) }, onDone = { closed++ },
                        onSavePersonalized = { value, ids -> vm.saveModule(value, ids) })
                }
            }
        }
        compose.onNodeWithTag("module-topic").performScrollTo().performTextReplacement("Reliable database design")
        compose.onNodeWithTag("module-pref-level").performScrollTo().performClick()
        compose.onNodeWithTag("module-pref-level-advanced").performClick()
        compose.onNodeWithTag("module-personalization").performScrollTo().performClick()
        compose.onNodeWithTag("module-note-${entry.id}").performScrollTo().assertIsOn().performClick()

        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        compose.onNodeWithTag("module-save").performScrollTo().performClick()
        assertEquals(module.id, selected.value!!.ui.value.moduleSavingId)
        compose.onNodeWithTag("module-save").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("module-save").performScrollTo().assertIsNotEnabled()
        assertEquals(0, closed)

        compose.runOnIdle { models.clear() }
        models = ViewModelStore()
        val replacement = model(models)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        paused.scheduler.runCurrent()
        compose.waitUntil(10_000) { replacement.ui.value.state != null }
        val unchanged = runBlocking { app.feedRepository.current() }
        assertEquals(module, unchanged.modules.first { it.id == module.id })
        assertEquals(listOf(entry), unchanged.context)
        compose.runOnIdle { selected.value = replacement }

        compose.onNodeWithTag("module-topic").performScrollTo().assertIsEnabled().assertTextContains("Reliable database design")
        compose.onNodeWithTag("module-pref-level").performScrollTo().assertIsEnabled()
            .assertTextContains(compose.activity.getString(R.string.modules_option_advanced))
        compose.onNodeWithTag("module-note-${entry.id}").performScrollTo().assertIsOff()
        compose.onNodeWithTag("module-save").performScrollTo().assertIsEnabled()
        assertEquals("An uncommitted restored module must stay open", 0, closed)

        compose.onNodeWithTag("module-save").performClick()
        compose.waitUntil(10_000) { closed == 1 && replacement.ui.value.moduleSavingId == null }
        val saved = runBlocking { app.feedRepository.current() }
        val committedModule = saved.modules.first { it.id == module.id }
        assertEquals("Reliable database design", committedModule.query)
        assertEquals(mapOf("level" to "advanced"), committedModule.preferences)
        assertTrue(saved.context.single().scopes.isEmpty())
        assertEquals(entry.id, saved.context.single().id)
        assertEquals(1, closed)
    }

    private fun model(store: ViewModelStore): FeedViewModel =
        // The global factory singleton can retain an earlier Robolectric
        // Application. Match production's application-scoped factory instead.
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java].also {
            assertSame(app, it.getApplication<FeedTestApplication>())
        }
}
