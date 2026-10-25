package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedTestApplication
import mebot.nguli.com.ui.feed.ContextPasteDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the dialog window without a ViewModel or a replacement Main dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextPasteDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyDialogAcceptsTextAndOffersReviewAndCancellation() {
        var open by mutableStateOf(false)
        var draft by mutableStateOf("")
        var reviewed: String? = null
        compose.setContent {
            MeBotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Button(onClick = { open = true }, modifier = Modifier.testTag("open-paste")) { Text("Open") }
                    if (open) ContextPasteDialog(draft, { draft = it }, working = false, error = null,
                        onDismiss = { open = false }, onReview = { reviewed = it })
                }
            }
        }
        compose.onNodeWithTag("open-paste").performClick()
        compose.onNodeWithTag("context-paste-review").assertIsNotEnabled()
        compose.onNodeWithTag("context-paste-input").performTextInput("{}")
        compose.onNodeWithTag("context-paste-review").assertIsEnabled().performClick()
        assertEquals("{}", reviewed)
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("context-paste-dialog").assertDoesNotExist()
    }
}
