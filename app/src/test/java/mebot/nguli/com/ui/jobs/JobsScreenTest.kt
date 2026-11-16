package mebot.nguli.com.ui.jobs

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.ui.MeBotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JobsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val job = JobPosting(id = "fixture-job", title = "Software engineer", company = "Example employer",
        location = "Worldwide", remote = true, employmentType = "full_time", description = "Build reliable systems.",
        url = "https://example.com/jobs/fixture", source = "Remotive")
    private var state by mutableStateOf(JobsState(preferences = JobPreferences(onboarded = true), jobs = listOf(job)))
    private val opened = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private fun render(error: String? = null, draft: String = "") {
        compose.setContent {
            MeBotTheme {
                JobsScreen(state, false, error, {}, { state = state.copy(preferences = it) }, { id ->
                    state = state.copy(applications = state.applications + JobApplication(id, snapshot = job))
                }, { application ->
                    state = state.copy(applications = state.applications.filterNot { it.jobId == application.jobId } + application)
                }, { _, prompt -> asked += prompt }, { opened += it }, { draft })
            }
        }
    }
    private fun openJob() {
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).performClick()
    }
    private fun prepare() {
        openJob()
        compose.onNodeWithTag("jobs-prepare").performScrollTo().performClick()
    }

    @Test fun browsingAndSavingNeverRequiresAi() {
        render()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithContentDescription("Save job").performClick()
        compose.onNodeWithTag("jobs-screen").performScrollToIndex(0)
        compose.onNodeWithTag("jobs-tab-saved").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).assertIsDisplayed()
        assertEquals(job.id, state.applications.single().jobId)
        assertTrue(asked.isEmpty())
    }

    @Test fun openOriginalDoesNotMarkApplied() {
        render(); openJob()
        compose.onNodeWithText("View original listing").performScrollTo().performClick()
        assertEquals(listOf(job.url), opened)
        assertTrue(state.applications.isEmpty())
    }

    @Test fun sourceFailureKeepsCachedJobVisible() {
        render(error = "offline")
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).assertIsDisplayed()
        assertTrue(asked.isEmpty())
    }

    @Test fun notesSaveWithoutAiAndOpeningWorkspaceDoesNotSubmit() {
        render(); prepare()
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("Check work eligibility")
        compose.onNodeWithTag("jobs-save-progress").performScrollTo().performClick()
        assertEquals("preparing", state.applications.single().status)
        assertEquals("Check work eligibility", state.applications.single().notes)
        assertTrue(asked.isEmpty()); assertTrue(opened.isEmpty())
    }

    @Test fun documentExportNeedsReviewAndEditsClearReview() {
        render(draft = "Verified candidate details only."); prepare()
        compose.onNodeWithText("Application draft").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-export-pdf").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Use last completed AI answer").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-export-pdf").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("jobs-export-pdf").assertIsEnabled()
        compose.onNodeWithTag("jobs-document-text").performScrollTo().performTextInput(" Updated.")
        compose.onNodeWithTag("jobs-export-pdf").performScrollTo().assertIsNotEnabled()
    }

    @Test fun latePersistedOnboardingStateShowsBrowse() {
        state = JobsState()
        render()
        compose.onNodeWithText("Find work that fits").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(preferences = JobPreferences(onboarded = true), jobs = listOf(job)) }
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-screen").assertIsDisplayed()
    }
}
