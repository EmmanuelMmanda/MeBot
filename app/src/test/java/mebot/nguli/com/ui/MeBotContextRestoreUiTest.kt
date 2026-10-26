package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import mebot.nguli.com.R
import mebot.nguli.com.core.ContextEntry
import mebot.nguli.com.core.PersonalProfile085
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
        val profile = saved.context.single { it.id == PersonalProfile085.PROFILE_ID }
        assertEquals("Keep this unsaved learning preference", profile.text)
        assertTrue(profile.global)
        assertTrue(profile.scopes.isEmpty())
        assertEquals(entry, saved.context.single { it.id == entry.id })
        assertEquals(1, closed)
    }

    @Test fun globalProfileSavePreservesUnconfirmedLegacyMetadata() {
        val primary = entry.copy(id=PersonalProfile085.PROFILE_ID,category="profile",global=true,scopes=emptySet())
        val legacy = entry.copy(id="unconfirmed",confirmed=false,origin="imported")
        runBlocking { app.feedRepository.update { it.copy(context=listOf(primary,legacy)) } }
        compose.waitUntil(10_000) { selected.value!!.ui.value.state?.context == listOf(primary,legacy) }
        restoration.setContent {
            val vm=selected.value!!; val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize()) { ContextScreen(ui,vm,onDone={closed++}) } }
        }
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("Revised learning interest")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed==1 }
        val saved=runBlocking { app.feedRepository.current() }
        assertTrue(saved.context.single { it.id==primary.id }.global)
        assertTrue(saved.context.single { it.id==primary.id }.scopes.isEmpty())
        assertEquals(legacy,saved.context.single { it.id==legacy.id })
    }

    @Test fun globalAboutAndVisibleScopedNotesSaveTogetherWithoutPromotingOldProfile() {
        val old = entry.copy(id="profile-about-you",text="Previously scoped business detail",scopes=setOf("business"))
        runBlocking { app.feedRepository.update { it.copy(context=listOf(old,entry)) } }
        compose.waitUntil(10_000) { selected.value!!.ui.value.state?.context == listOf(old,entry) }
        restoration.setContent {
            val vm=selected.value!!; val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize()) { ContextScreen(ui,vm,onDone={closed++}) } }
        }
        compose.onNodeWithTag("context-edit").assertTextContains("")
        compose.onNodeWithTag("context-scopes-${PersonalProfile085.PROFILE_ID}").assertDoesNotExist()
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("I manage several small businesses in Tanzania.")
        capture("global-about-you")
        compose.onNodeWithTag("context-edit-${old.id}").performScrollTo().assertTextContains(old.text)
        compose.onNodeWithTag("context-edit-${entry.id}").performScrollTo().performTextReplacement("x".repeat(1001))
        compose.onNodeWithTag("context-save").performScrollTo().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("context-edit-${entry.id}").performScrollTo().assertTextContains("x".repeat(1001))
        compose.onNodeWithTag("context-edit-${entry.id}").performTextReplacement("Practical learning notes")
        compose.onNodeWithTag("context-scopes-${entry.id}").performScrollTo().performClick()
        compose.onNodeWithTag("context-scope-${entry.id}-news").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.onNodeWithTag("context-edit-${entry.id}").performScrollTo()
        capture("scoped-context-notes")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed==1 }
        val saved=runBlocking { app.feedRepository.current() }
        assertEquals(old,saved.context.single { it.id==old.id })
        assertTrue(saved.context.single { it.id==PersonalProfile085.PROFILE_ID }.global)
        assertEquals(setOf("learning","news"),saved.context.single { it.id==entry.id }.scopes)
        assertFalse(saved.context.single { it.id==entry.id }.global)
    }

    @Test fun contextNoteCanBeRemovedAddedAndSavedWithoutGlobalSharing() {
        restoration.setContent {
            val vm=selected.value!!; val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize()) { ContextScreen(ui,vm,onDone={closed++}) } }
        }
        compose.onNodeWithTag("context-remove-${entry.id}").performScrollTo().performClick()
        compose.onNodeWithTag("context-edit-${entry.id}").assertDoesNotExist()
        compose.onNodeWithTag("context-add").performScrollTo().performClick()
        val noteMatcher=SemanticsMatcher("a scoped note editor") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("context-edit-") == true && it.config.contains(SemanticsProperties.EditableText)
        }
        compose.onNode(noteMatcher).performScrollTo().performTextInput("Only use this after I select a topic")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(noteMatcher).assertTextContains("Only use this after I select a topic")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed==1 }
        val saved=runBlocking { app.feedRepository.current() }.context.single()
        assertEquals("Only use this after I select a topic",saved.text)
        assertFalse(saved.global); assertTrue(saved.scopes.isEmpty()); assertNotEquals(entry.id,saved.id)
    }

    @Test fun visibleInvalidTimezoneBlocksSaveAndCorrectionKeepsTheSameDraft() {
        restoration.setContent {
            val vm=selected.value!!; val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize()) { ContextScreen(ui,vm,onDone={closed++}) } }
        }
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("Keep my work interests")
        compose.onNodeWithTag("context-timezone").performScrollTo().performTextReplacement("Not/A_Timezone")
        compose.onNodeWithTag("context-timezone").assertIsDisplayed()
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.onNodeWithTag("context-timezone").performScrollTo().assertTextContains("Not/A_Timezone")
        assertEquals(0,closed)
        compose.onNodeWithTag("context-edit").assertTextContains("Keep my work interests")
        compose.onNodeWithTag("context-timezone").performTextReplacement("UTC")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed==1 }
        val saved=runBlocking { app.feedRepository.current() }
        assertEquals("UTC",saved.timezone)
        assertEquals("Keep my work interests",saved.context.single { it.id==PersonalProfile085.PROFILE_ID }.text)
    }

    @Test fun leavingAndReenteringProfileKeepsUnsavedDraftWithoutWritingIt() {
        val visible = mutableStateOf(true)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            if (visible.value) holder.SaveableStateProvider("context") {
                val vm = selected.value!!
                val ui by vm.ui.collectAsState()
                MeBotTheme { Surface(Modifier.fillMaxSize()) { ContextScreen(ui, vm, onDone = {}) } }
            }
        }
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("My unsaved personal draft")
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("context-edit").assertDoesNotExist()
        assertEquals(listOf(entry), runBlocking { app.feedRepository.current().context })
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("context-edit").assertTextContains("My unsaved personal draft")
        assertEquals(listOf(entry), runBlocking { app.feedRepository.current().context })
    }

    @Test fun noExplicitTopicsDoesNotShareCustomInterestsWithFallbackNews() {
        var completed: Boolean? = null
        selected.value!!.configureOnboarding(emptySet(), "", "My private custom interest") { completed = it }
        compose.waitUntil(10_000) { completed != null }
        assertEquals(true, completed)
        val saved = runBlocking { app.feedRepository.current() }
        assertTrue(saved.context.single { it.id == "onboarding-interests" }.scopes.isEmpty())
        assertEquals(entry, saved.context.single { it.id == entry.id })
    }

    @Test fun blankOnboardingRetryAfterModelRecreationDoesNotDeleteOrRescopeExistingNote() {
        val old = ContextEntry(id = "onboarding-interests", text = "Kilimo na biashara", scopes = setOf("learning"),
            confirmed = true, origin = "onboarding", updatedAtMillis = 42L)
        runBlocking { app.feedRepository.update { it.copy(context = listOf(entry, old)) } }
        models.clear()
        models = ViewModelStore()
        selected.value = model(models)
        compose.waitUntil(10_000) { selected.value!!.ui.value.state != null }
        repeat(2) {
            var completed: Boolean? = null
            selected.value!!.configureOnboarding(setOf("news"), "", "") { completed = it }
            compose.waitUntil(10_000) { completed != null }
            assertEquals(true, completed)
            assertEquals(listOf(entry, old), runBlocking { app.feedRepository.current().context })
        }
    }

    @Test fun customOnboardingInterestsCommitWithSelectedScopesAndKeepLegacyNotes() {
        var completed: Boolean? = null
        selected.value!!.configureOnboarding(setOf("learning"), "Example region", "Napenda kujifunza kilimo") { completed = it }
        compose.waitUntil(10_000) { completed != null }
        assertEquals(true, completed)
        val saved = runBlocking { app.feedRepository.current() }
        assertEquals(entry, saved.context.single { it.id == entry.id })
        val interest = saved.context.single { it.id == "onboarding-interests" }
        assertEquals("Napenda kujifunza kilimo", interest.text)
        assertEquals(setOf("learning"), interest.scopes)
        assertEquals("Example region", saved.region)
        completed = null
        selected.value!!.configureOnboarding(setOf("news"), "Other region", "na ".repeat(151)) { completed = it }
        compose.waitUntil(10_000) { completed != null }
        assertEquals(false, completed)
        assertEquals(saved, runBlocking { app.feedRepository.current() })
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

    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val bitmap=compose.runOnIdle {
            val window=compose.activity.window
            window.decorView.invalidate()
            android.graphics.Bitmap.createBitmap(window.decorView.width,window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888).also {
                var status:Int?=null
                android.view.PixelCopy.request(window,it,{result->status=result},android.os.Handler(android.os.Looper.getMainLooper()))
                assertEquals(android.view.PixelCopy.SUCCESS,status)
            }
        }
        val file=java.io.File("build/outputs/screenshots/v085/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }

    private fun model(store: ViewModelStore): FeedViewModel =
        // The global factory singleton can retain an earlier Robolectric
        // Application. Match production's application-scoped factory instead.
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java].also {
            assertSame(app, it.getApplication<FeedTestApplication>())
        }
}
