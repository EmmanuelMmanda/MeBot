package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
import mebot.nguli.com.ui.feed.QuickGuideSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Usability084UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun guideExplainsCurrentSaveFollowAndFullscreenThenCloses() = verifyGuide(false)

    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun swahiliGuideKeepsCurrentActionsReachable() = verifyGuide(true)

    private fun verifyGuide(swahili: Boolean) {
        var closed = 0
        compose.setContent { MeBotTheme { QuickGuideSheet { closed++ } } }
        val feedback = compose.activity.getString(R.string.quick_guide_feedback_body)
        assertTrue(feedback.contains(if (swahili) "Nyota" else "star", ignoreCase=true))
        assertTrue(feedback.contains(if (swahili) "Hifadhi" else "Saved"))
        assertFalse(feedback.contains("Not relevant", ignoreCase=true))
        assertFalse(feedback.contains("Sihitaji", ignoreCase=true))
        compose.onNodeWithText(feedback).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.guide_media_body_084)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quick-guide-done").performScrollTo().performClick()
        assertEquals(1,closed)
    }
}
