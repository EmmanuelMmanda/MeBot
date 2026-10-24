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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.work.testing.WorkManagerTestInitHelper
import app.mynguli.R
import app.mynguli.core.FeedState
import app.mynguli.core.RequestBudget
import app.mynguli.core.Settings
import app.mynguli.feed.FeedTestApplication
import app.mynguli.feed.FeedViewModel
import app.mynguli.ui.feed.DeliveryPreferencesScreen
import java.io.File
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotDeliveryPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val app get() = compose.activity.application as FeedTestApplication
    private var models = ViewModelStore()
    private val selected = mutableStateOf<FeedViewModel?>(null)
    private var closed = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking { app.feedRepository.update { FeedState(timezone = "Africa/Dar_es_Salaam", personalSeedApplied = true) } }
        selected.value = model(models)
        compose.waitUntil(10_000) { selected.value!!.ui.value.state != null }
    }

    @After fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun newDefaultAndUnlimitedAreVisibleAndSavingPersistsTheSelectedBudget() {
        show()
        compose.onNodeWithTag("delivery-limit-28").assertIsSelected()
        compose.onNodeWithTag("delivery-limit-56").assertExists()
        compose.onNodeWithTag("delivery-limit-112").assertExists()
        compose.onNodeWithTag("delivery-limit-224").assertExists()
        compose.onNodeWithTag("delivery-limit-unlimited").assertExists()
        screenshot("56-mebot-background-v061")
        compose.onNodeWithTag("delivery-limit-unlimited").performScrollTo().performClick()
        compose.onNodeWithTag("delivery-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed == 1 && !selected.value!!.ui.value.deliverySaving }
        assertEquals(RequestBudget.UNLIMITED, current().dailyRequestLimit)
        assertEquals(6, current().refreshHours)
        assertFalse(current().notifications)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("delivery-limit-unlimited").performScrollTo().assertIsSelected()
    }

    @Test fun existingLimitIsKeptAndInvalidCustomDraftDoesNotSaveThenCanBeCorrected() {
        runBlocking { app.feedRepository.update { it.copy(dailyRequestLimit = 12, refreshHours = 2) } }
        compose.waitUntil(10_000) { selected.value!!.ui.value.state?.dailyRequestLimit == 12 }
        show()
        compose.onNodeWithTag("delivery-limit-custom").assertIsSelected()
        compose.onNodeWithTag("delivery-limit-input").assertTextContains("12")
        compose.onNodeWithTag("delivery-limit-input").performTextReplacement("0")
        compose.onNodeWithTag("delivery-save").performScrollTo().performClick()
        assertEquals(0, closed)
        assertEquals(12, current().dailyRequestLimit)
        compose.onNodeWithTag("delivery-limit-input").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.delivery_limit_invalid)).assertExists()
        compose.onNodeWithTag("delivery-limit-input").performTextReplacement("500")
        compose.onNodeWithTag("delivery-interval-input").performScrollTo().assertTextContains("2")
        compose.onNodeWithTag("delivery-quiet-start").performScrollTo().performTextReplacement("9")
        compose.onNodeWithTag("delivery-quiet-end").performScrollTo().performTextReplacement("9")
        compose.onNodeWithTag("delivery-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed == 1 && !selected.value!!.ui.value.deliverySaving }
        assertEquals(500, current().dailyRequestLimit)
        assertEquals(2, current().refreshHours)
        assertEquals(9, current().quietStartHour)
        assertEquals(9, current().quietEndHour)
    }

    @Test fun interruptedSaveRestoresEditableBudgetWithoutClaimingSuccess() {
        show()
        compose.onNodeWithTag("delivery-limit-112").performClick()
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        compose.onNodeWithTag("delivery-save").performScrollTo().performClick()
        assertTrue(selected.value!!.ui.value.deliverySaving)
        compose.onNodeWithTag("delivery-save").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("delivery-save").performScrollTo().assertIsNotEnabled()
        assertEquals(28, current().dailyRequestLimit)

        compose.runOnIdle { models.clear() }
        models = ViewModelStore()
        val replacement = model(models)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        paused.scheduler.runCurrent()
        compose.waitUntil(10_000) { replacement.ui.value.state != null }
        compose.runOnIdle { selected.value = replacement }
        compose.onNodeWithTag("delivery-limit-112").performScrollTo().assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("delivery-save").performScrollTo().assertIsEnabled()
        assertEquals(0, closed)
        assertEquals(28, current().dailyRequestLimit)
        compose.onNodeWithTag("delivery-save").performClick()
        compose.waitUntil(10_000) { closed == 1 && !replacement.ui.value.deliverySaving }
        assertEquals(112, current().dailyRequestLimit)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowLargeTextKeepsEveryChoiceReachableWithoutHorizontalScrolling() {
        show(largeText = true)
        compose.onNodeWithTag("delivery-limit-28").assertIsSelected()
        compose.onNodeWithTag("delivery-limit-unlimited").performScrollTo().assertIsDisplayed().performClick()
        screenshot("57-mebot-background-small-sw-v061")
        compose.onNodeWithTag("delivery-interval-custom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("delivery-quiet-start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("delivery-quiet-end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("delivery-save").performScrollTo().assertIsDisplayed()
    }

    private fun show(largeText: Boolean = false) {
        restoration.setContent {
            val vm = selected.value!!
            val ui by vm.ui.collectAsState()
            MeBotTheme(if (largeText) Settings(theme = "dark", language = "sw") else Settings()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (largeText) 1.3f else 1f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        DeliveryPreferencesScreen(ui, vm, onDone = { closed++ })
                    }
                }
            }
        }
    }

    private fun current() = runBlocking { app.feedRepository.current() }
    private fun model(store: ViewModelStore): FeedViewModel =
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java].also {
            assertSame(app, it.getApplication<FeedTestApplication>())
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
