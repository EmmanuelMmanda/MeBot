package mebot.nguli.com.security

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.ui.MeBotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native host renders with synthetic in-memory security state; no keys, accounts or biometrics created. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLockUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun controller(enabled: Boolean): AppLockController {
        val store = object : LockStorage {
            var record = if (enabled) LockRecord(salt = ByteArray(32), verifier = ByteArray(32)) else null
            override fun enabled() = record != null
            override fun read() = record
            override fun write(record: LockRecord) { this.record = record }
            override fun disable() { record = null }
        }
        return AppLockController(store, { 100_000 }, { 1 }, { false }).apply { foreground(true) }
    }
    @Test fun lockedScreenRendersWithoutPersonalContentOrSavedPin() {
        val c = controller(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MeBotTheme { AppLockScreen(c, {}) } }
        compose.onNodeWithTag("app-lock-screen").assertIsDisplayed()
        capture("app-lock")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("718293")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(hasSetTextAction())[0].assertExists()
        assertEquals("", compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("app-lock-unlock").assertIsNotEnabled()
    }
    @Test fun partialPinIsClearedWhenAlreadyLockedScreenPauses() {
        val c = controller(true)
        compose.setContent { MeBotTheme { AppLockScreen(c, {}) } }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("718293")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertEquals("", compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    @Test fun settingsExplainsBoundaryAndRequiresMatchingPinBeforeSetup() {
        val c = controller(false)
        compose.setContent { MeBotTheme { AppLockSettingsScreen(c, {}) } }
        compose.onNodeWithTag("app-lock-settings").assertIsDisplayed()
        compose.onNodeWithText("Enable app lock").assertIsNotEnabled()
        capture("app-lock-settings")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("718293")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("718294")
        compose.onNodeWithText("Enable app lock").performScrollTo().performClick()
        compose.onNodeWithText("The new PINs do not match.").assertExists()
        assertFalse(c.state.value.enabled)
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            window.decorView.invalidate()
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { r -> result = r }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val file = File("build/outputs/screenshots/v090/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
