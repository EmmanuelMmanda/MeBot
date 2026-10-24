package app.mynguli.ui

import android.content.ClipboardManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedTestApplication
import app.mynguli.feed.FeedViewModel
import app.mynguli.ui.feed.ContextScreen
import app.mynguli.ui.feed.ContextSetup
import app.mynguli.ui.feed.FaqScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val app get() = compose.activity.application as FeedTestApplication
    private val models = ViewModelStore()
    private lateinit var vm: FeedViewModel
    private val original = FeedState(name = "Example reader", personalSeedApplied = true,
        context = listOf(ContextEntry(id = "private-note", text = "A private local note never copied into the setup prompt", scopes = setOf("news"))))

    @Before fun setUp() {
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("MeBotSetupMain"))
        runBlocking { app.feedRepository.update { original } }
        vm = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        waitForModel { vm.ui.value.state != null }
    }

    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }

    @Test fun setupPromptUsesAValidImportContractInBothLanguagesAndNeverIncludesLocalPersonalNotes() {
        val strict = Json { ignoreUnknownKeys = false }
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("sw-TZ"))) {
            val localized = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(locale) })
            val example = ContextSetup.example(localized.getString(R.string.setup_context_example))
            val imported = strict.decodeFromString<PersonalContextImport>(example)
            FeedLogic.validate(FeedState(name = imported.name, region = imported.region, timezone = imported.timezone,
                context = imported.entries, modules = imported.modules))
            assertEquals("mebot-context-v1", imported.format)
            assertTrue(imported.modules.isEmpty())
            assertTrue(imported.entries.all { it.scopes.all { scope -> scope in ModuleCatalog.kinds } })
            val prompt = ContextSetup.prompt(localized)
            assertTrue(prompt.contains(example))
            ModuleCatalog.kinds.forEach { assertTrue(prompt.contains(it)) }
            assertTrue("The setup prompt should remain copyable in one short message", prompt.length < 2500)
            assertFalse(prompt.contains(original.name))
            assertFalse(prompt.contains(original.context.single().text))
        }
    }

    @Test fun contextImportIsVisibleAndInvalidPasteSurvivesRotationUntilReviewedAndConfirmed() {
        restoration.setContent {
            val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ContextScreen(ui, vm, onDone = {})
            } }
        }
        compose.onNodeWithTag("context-import").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.feed_advanced)).assertDoesNotExist()
        screenshot("60-mebot-context-v061")
        compose.onNodeWithTag("context-paste").performClick()
        compose.onNodeWithTag("context-paste-review").assertIsNotEnabled()
        compose.onNodeWithTag("context-paste-input").performTextInput("{ invalid JSON }")
        compose.onNodeWithTag("context-paste-review").performClick()
        waitForModel { !vm.ui.value.importWorking && vm.ui.value.error != null }
        assertEquals(original.context, runBlocking { app.feedRepository.current().context })
        screenshot("67-mebot-context-paste-v061", "context-paste-dialog")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("context-paste-input").assertTextContains("{ invalid JSON }")

        val valid = ContextSetup.example("Prefer practical tutorials with working examples")
        compose.onNodeWithTag("context-paste-input").performTextReplacement(valid)
        compose.onNodeWithTag("context-paste-review").performClick()
        waitForModel { vm.ui.value.importPreview != null }
        compose.onNodeWithTag("context-paste-dialog").assertDoesNotExist()
        compose.onNodeWithTag("import-review").assertExists()
        assertEquals(original.context, runBlocking { app.feedRepository.current().context })
        compose.onNodeWithTag("import-confirm").performClick()
        waitForModel { vm.ui.value.importSummary == "context" && !vm.ui.value.importWorking }
        val saved = runBlocking { app.feedRepository.current() }
        assertEquals("Prefer practical tutorials with working examples", saved.context.single().text)
        assertEquals(original.modules, saved.modules)
    }

    @Test fun faqStartsWithSetupAndCopiesThePromptBeforeOpeningTheOfficialChatgptPage() {
        var contextOpened = 0
        compose.setContent { MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FaqScreen(onContext = { contextOpened++ }, onConnect = {})
        } } }
        compose.onNodeWithTag("faq-answer-setup").assertExists()
        compose.onNodeWithTag("faq-open-context").performScrollTo().performClick()
        assertEquals(1, contextOpened)
        compose.onNodeWithTag("context-copy-prompt").performScrollTo().performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(ContextSetup.prompt(compose.activity), clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithTag("context-prompt-copied").assertExists()
        compose.onNodeWithTag("context-prepare-chatgpt").performScrollTo().performClick()
        assertEquals(ContextSetup.CHATGPT_URL, shadowOf(compose.activity).nextStartedActivity.dataString)
        compose.onNodeWithTag("faq-search").performScrollTo()
        screenshot("61-mebot-faq-v061")
    }

    @Test fun faqSearchExposesTheMemoryBoundaryAndAnEmptyStateWithoutLosingTheQuery() {
        restoration.setContent { MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FaqScreen(onContext = {}, onConnect = {})
        } } }
        compose.onNodeWithTag("faq-search").performTextInput("memory")
        compose.onNodeWithTag("faq-setup").assertDoesNotExist()
        compose.onNodeWithTag("faq-memory").performClick()
        compose.onNodeWithTag("faq-answer-memory").assertTextEquals(compose.activity.getString(R.string.setup_faq_memory_a))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("faq-search").assertTextContains("memory")
        compose.onNodeWithTag("faq-answer-memory").assertExists()
        compose.onNodeWithTag("faq-search").performTextReplacement("a question with no match 019283")
        compose.onNodeWithTag("faq-empty").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun compactSwahiliFaqKeepsSetupAndCopyActionsReachableAtLargeTextSize() {
        compose.setContent {
            MeBotTheme(Settings(theme = "dark", language = "sw")) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FaqScreen({}, {}) }
                }
            }
        }
        compose.onNodeWithTag("context-copy-prompt").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("context-prepare-chatgpt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-view-prompt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-copy-prompt").assertIsDisplayed()
        screenshot("62-mebot-faq-small-sw-v061")
    }

    private fun waitForModel(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun screenshot(name: String, nodeTag: String? = null) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (nodeTag != null) compose.onNodeWithTag(nodeTag).assertIsDisplayed()
        val image = compose.runOnIdle {
            // Compose dialogs have a separate Android window from the activity.
            val window = if (nodeTag != null) {
                val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "No native dialog to capture" }
                assertTrue("The captured dialog must be visible", dialog.isShowing)
                requireNotNull(dialog.window)
            } else compose.activity.window
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
