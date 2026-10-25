package mebot.nguli.com.alarm

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.MathChallenge
import mebot.nguli.com.core.MathQuestion
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = MeBotApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotAlarmJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<AlarmActivity>()
    private lateinit var controller: AlarmController
    private lateinit var store: AlarmStore

    @Before fun setUp() {
        controller = AlarmController(compose.activity)
        store = AlarmStore(compose.activity)
        controller.clearAll()
    }

    @After fun cleanup() { controller.clearAll() }

    private fun beginChallenge() {
        store.session = AlarmSession(
            id = "ui-session", plan = AlarmPlan(id = "ui-alarm", label = "Morning", dismissMode = AlarmDismissMode.MATH),
            occurrenceId = "ui-occurrence", startedAtMillis = System.currentTimeMillis(), isTest = true,
            mathChallenge = MathChallenge(listOf(
                MathQuestion("q1", "12 + 7", 19),
                MathQuestion("q2", "18 − 6", 12),
                MathQuestion("q3", "9 + 8", 17),
            )),
        )
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("math-question").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun mathQuizRejectsWrongAnswerAndPersistsAcrossActivityRecreation() {
        beginChallenge()
        screenshot("10-mebot-math-alarm")
        key("2")
        key("submit")
        compose.onNodeWithText("Try again").assertIsDisplayed()
        assertEquals(0, controller.session()?.mathChallenge?.answered)
        key("1")
        key("9")
        key("submit")
        compose.onNodeWithTag("math-question").assertTextEquals("18 − 6")
        key("1")
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("math-answer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("math-answer").assertTextEquals("1")
        assertEquals(1, controller.session()?.mathChallenge?.answered)
        key("2")
        key("submit")
        compose.onNodeWithTag("math-question").assertTextEquals("9 + 8")
        key("1")
        key("7")
        key("submit")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alarm ended").fetchSemanticsNodes().isNotEmpty() }
        assertNull(controller.session())
        assertTrue(controller.events().any { it.kind == "math_completed" })
    }

    @Test
    @Config(qualifiers = "w320dp-h740dp-xhdpi")
    fun compactScreenCanReachKeypadAndEmergencyStop() {
        beginChallenge()
        key("9")
        compose.onNodeWithContentDescription("Delete digit").performScrollTo().performClick()
        compose.onNodeWithTag("math-answer").performScrollTo().assertTextEquals("?")
        compose.onNodeWithText("Emergency stop").performScrollTo().performClick()
        compose.onNodeWithText("Keep ringing").performClick()
        assertNotNull(controller.session())
        compose.onNodeWithText("Emergency stop").performScrollTo().performClick()
        compose.onNodeWithText("Stop alarm").performClick()
        assertNull(controller.session())
    }

    private fun key(value: String) { compose.onNodeWithTag("math-key-$value").performScrollTo().performClick() }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val output = File("build/outputs/screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
