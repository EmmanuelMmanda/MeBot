package mebot.nguli.com.security

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.ui.MeBotTheme
import mebot.nguli.com.ui.feed.FullscreenMediaShell
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose dialog/media/popup registration, deliberately without a lock-state UI collector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedWindowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun controller(enabled: Boolean = true): AppLockController {
        val record = if (enabled) LockRecord(salt = ByteArray(32), verifier = ByteArray(32), biometric = true) else null
        return AppLockController(object : LockStorage {
            override fun enabled() = enabled
            override fun read() = record
            override fun write(record: LockRecord) {}
            override fun disable() {}
        }, { 100_000 }, { 1 }, { false }).apply { foreground(true); biometricSuccess(ticket()) }
    }
    @Test fun sensitiveContextDialogHiddenOnPauseWithoutCollectorAndStaleCommitRejected() {
        val c = controller()
        val curtain = compose.runOnUiThread { PrivacyCurtain(compose.activity, c) }
        compose.setContent { MeBotTheme {
            AlertDialog(onDismissRequest = {}, title = { Text("Synthetic private context") },
                text = { Text("Synthetic personal draft") }, confirmButton = { ProtectedWindowRegistration(); TextButton(onClick = {}) { Text("Done") } })
        } }
        compose.onNodeWithText("Synthetic personal draft").assertIsDisplayed()
        val oldTicket = c.ticket()
        compose.runOnUiThread { curtain.cover(); c.foreground(false) }
        assertTrue(curtain.registeredRootCount() > 0)
        assertTrue(curtain.registeredRootsHidden())
        compose.runOnUiThread {
            c.foreground(true)
            assertFalse(curtain.committed(curtain.epoch, oldTicket, false))
            assertTrue(curtain.committed(curtain.epoch, c.ticket(), true))
            assertTrue(curtain.registeredRootsHidden())
        }
        compose.runOnUiThread { curtain.close() }
    }
    @Test fun fullscreenMediaWindowHiddenSynchronouslyOnScreenOff() {
        val c = controller()
        val curtain = compose.runOnUiThread { PrivacyCurtain(compose.activity, c) }
        compose.setContent { MeBotTheme { FullscreenMediaShell("Synthetic PDF", {}) { Text("Synthetic page") } } }
        compose.onNodeWithText("Synthetic page").assertIsDisplayed()
        compose.runOnUiThread { curtain.cover(); c.lock() }
        assertTrue(curtain.registeredRootCount() > 0)
        assertTrue(curtain.registeredRootsHidden())
        compose.runOnUiThread { curtain.close() }
    }
    @Test fun defaultOffContextDialogAndDropdownRemainVisibleAndInteractiveAfterPauseResume() {
        val c = controller(false)
        val curtain = compose.runOnUiThread { PrivacyCurtain(compose.activity, c) }
        val dialog = androidx.compose.runtime.mutableStateOf(true)
        val popup = androidx.compose.runtime.mutableStateOf(false)
        var chosen = 0
        compose.setContent { MeBotTheme {
            if (dialog.value) AlertDialog(onDismissRequest = { dialog.value = false }, title = { Text("Normal context") },
                confirmButton = { ProtectedWindowRegistration(); TextButton(onClick = { dialog.value = false }) { Text("Close context") } })
            DropdownMenu(expanded = popup.value, onDismissRequest = { popup.value = false }) {
                ProtectedWindowRegistration(); TextButton(onClick = { chosen++; popup.value = false }) { Text("Normal option") }
            }
        } }
        fun pauseResume() {
            compose.runOnUiThread { curtain.cover(); c.foreground(false) }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.runOnUiThread { curtain.cover(); c.foreground(true) }
            compose.waitForIdle()
        }
        pauseResume()
        compose.onNodeWithText("Close context").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(dialog.value); popup.value = true }
        pauseResume()
        compose.onNodeWithText("Normal option").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, chosen); assertFalse(popup.value) }
        compose.runOnUiThread { curtain.close() }
    }
    @Test fun actualDropdownPopupRootParticipatesInSynchronousProtection() {
        val c = controller()
        val curtain = compose.runOnUiThread { PrivacyCurtain(compose.activity, c) }
        compose.setContent { MeBotTheme { DropdownMenu(expanded = true, onDismissRequest = {}) {
            ProtectedWindowRegistration(); Text("Synthetic private option")
        } } }
        compose.onNodeWithText("Synthetic private option").assertIsDisplayed()
        compose.runOnUiThread { curtain.cover(); c.lock() }
        assertTrue(curtain.registeredRootCount() > 0)
        assertTrue(curtain.registeredRootsHidden())
        compose.runOnUiThread { curtain.close() }
    }
}
