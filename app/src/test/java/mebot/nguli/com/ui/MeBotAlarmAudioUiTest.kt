package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.work.testing.WorkManagerTestInitHelper
import mebot.nguli.com.MeBotViewModel
import mebot.nguli.com.R
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.Settings
import mebot.nguli.com.feed.FeedTestApplication
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotAlarmAudioUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val app get() = compose.activity.application as FeedTestApplication
    private lateinit var vm: MeBotViewModel
    private var saved = 0

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking { app.repository.erase() }
        app.alarms.clearAll()
        vm = ViewModelProvider(compose.activity)[MeBotViewModel::class.java]
        compose.waitUntil(10_000) { vm.ui.value.state != null }
    }

    @After fun cleanup() {
        app.alarms.clearAll()
        Dispatchers.resetMain()
    }

    @Test fun newEditorStartsWithPulseAndSavesRestoredSoundAndSpeechDraft() {
        restoration.setContent {
            val ui by vm.ui.collectAsState()
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ui.state?.let { AlarmEditScreen(it, vm, null, { saved++ }, {}) }
            } }
        }
        compose.onNodeWithTag("alarm-enabled").performClick()
        compose.onNodeWithTag("alarm-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-sound-pulse").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("alarm-sound-classic").assertIsNotSelected()
        compose.onNodeWithTag("alarm-sound-phone").performScrollTo().assertIsDisplayed()
        val soundTop = compose.onNodeWithTag("alarm-advanced").fetchSemanticsNode().boundsInRoot.top
        val captureMargin = compose.activity.resources.displayMetrics.density * 16f
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("alarm-advanced")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, soundTop - captureMargin) }
        screenshot("69-mebot-alarm-audio-v061")
        compose.onNodeWithTag("alarm-sound-bell").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-speech-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-speech-message").performScrollTo().performTextInput("Open the curtains and start the day.")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("alarm-sound-bell").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("alarm-speech-message").performScrollTo().assertTextContains("Open the curtains and start the day.")
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved == 1 && runBlocking { app.repository.current() }.alarms.size == 1 }
        val plan = runBlocking { app.repository.current() }.alarms.single()
        assertEquals("bell", plan.soundId)
        assertEquals("", plan.soundUri)
        assertTrue(plan.speechEnabled)
        assertEquals("Open the curtains and start the day.", plan.speechMessage)
        assertFalse(plan.enabled)
        assertEquals(80, plan.volumePercent)
        assertEquals(20, plan.rampSeconds)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowLargeTextKeepsSoundChoicesAndVoiceControlsReachable() {
        val draft = mutableStateOf(AlarmPlan())
        compose.setContent {
            MeBotTheme(Settings(theme = "dark", language = "sw")) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ScreenContainer {
                            Text(compose.activity.getString(R.string.mebot_more_alarm), style = MaterialTheme.typography.headlineLarge)
                            AppCard { AlarmAudioControls(draft.value, "sw", { draft.value = it }) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("alarm-sound-classic").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("alarm-sound-pulse").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-sound-phone").performScrollTo().assertIsDisplayed()
        screenshot("70-mebot-alarm-audio-small-sw-v061")
        compose.onNodeWithTag("alarm-sound-file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-speech-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-speech-message").performScrollTo().performTextInput("Ni wakati wa kuamka.")
        compose.onNodeWithTag("alarm-sound-preview").performScrollTo().assertIsDisplayed()
        assertEquals("pulse", draft.value.soundId)
        assertTrue(draft.value.speechEnabled)
        assertEquals("Ni wakati wa kuamka.", draft.value.speechMessage)
    }

    @Test fun previewEndsWhenTheActivityGoesToTheBackground() {
        val draft = mutableStateOf(AlarmPlan(soundId = "pulse"))
        compose.setContent {
            MeBotTheme { ScreenContainer { AlarmAudioControls(draft.value, "en", { draft.value = it }) } }
        }
        compose.onNodeWithTag("alarm-sound-preview").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("alarm-sound-preview").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(compose.activity.getString(R.string.alarm_audio_stop)).assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(compose.activity.getString(R.string.alarm_audio_preview)).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
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
