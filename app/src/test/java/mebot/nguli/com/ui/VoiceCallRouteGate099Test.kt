package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.ui.feed.VoiceCallRouteGate099
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCallRouteGate099Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun savedCallRouteCannotRestoreCallControlsWithoutProcessOwnedTransport() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var route by rememberSaveable { mutableStateOf("feed") }
            var transport by remember { mutableStateOf(false) }
            var capability by remember { mutableStateOf(false) }
            when (route) {
                "feed" -> Button(onClick = { capability = true; transport = true; route = "voice-call" }) { Text("Start configured call") }
                "voice-call" -> VoiceCallRouteGate099(capability, transport, { route = "voice-setup" }) { Text("Live call controls") }
                else -> Text("Voice setup")
            }
        }
        compose.onNodeWithText("Start configured call").performClick()
        compose.onNodeWithText("Live call controls").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Live call controls").assertDoesNotExist()
        compose.onNodeWithText("Voice setup").assertExists()
    }
}
