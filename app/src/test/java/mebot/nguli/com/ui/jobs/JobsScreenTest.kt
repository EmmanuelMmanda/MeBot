package mebot.nguli.com.ui.jobs

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.jobs.JobsAssistantRequest
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
    private val assistantRequests = mutableListOf<JobsAssistantRequest>()
    private var pendingApplication by mutableStateOf<JobApplication?>(null)
    private var pendingPreferences by mutableStateOf<JobPreferences?>(null)
    private var screenError by mutableStateOf<String?>(null)
    private fun render(error: String? = null, draft: String = "", acknowledge: Boolean = true, context: JobsContextSnapshot = JobsContextSnapshot()) {
        screenError = error
        compose.setContent {
            LaunchedEffect(pendingApplication, pendingPreferences) {
                if (acknowledge) {
                    withFrameNanos { }
                    pendingApplication?.let { application ->
                        state = state.copy(applications = state.applications.filterNot { it.jobId == application.jobId } + application)
                        pendingApplication = null
                    }
                    pendingPreferences?.let { preferences ->
                        state = state.copy(preferences = preferences)
                        pendingPreferences = null
                    }
                }
            }
            MeBotTheme {
                JobsScreen(state, false, screenError, {}, { pendingPreferences = it }, { id ->
                    state = state.copy(applications = state.applications + JobApplication(id, snapshot = job))
                }, { application ->
                    pendingApplication = application
                }, { _, prompt -> asked += prompt }, { opened += it }, { draft }, contextSnapshot = context, onAssistant = { _, request -> assistantRequests += request })
            }
        }
    }
    private fun openJob() {
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).performClick()
    }
    private fun prepare() {
        openJob()
        compose.onNodeWithTag("jobs-prepare").assertIsDisplayed().performClick()
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
        compose.onNodeWithTag("jobs-apply-source").assertIsDisplayed().performClick()
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
        compose.waitUntil(5_000) { state.applications.isNotEmpty() }
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
        compose.onNodeWithTag("jobs-setup-step-0").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(preferences = JobPreferences(onboarded = true), jobs = listOf(job)) }
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-screen").assertIsDisplayed()
    }
    @Test fun applicationSaveWaitsForPersistenceAcknowledgement() {
        render(acknowledge = false); prepare()
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("Draft awaiting disk write")
        compose.onNodeWithTag("jobs-save-progress").performScrollTo().performClick()
        compose.onNodeWithText("Saving your changes…").assertIsDisplayed()
        assertTrue(state.applications.isEmpty())
        compose.runOnIdle {
            state = state.copy(applications = listOf(requireNotNull(pendingApplication)))
            pendingApplication = null
        }
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-prepare").assertExists()
    }

    @Test fun storageFailureKeepsWorkspaceEditsVisible() {
        render(acknowledge = false); prepare()
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("Do not lose this draft")
        compose.onNodeWithTag("jobs-save-progress").performScrollTo().performClick()
        compose.runOnIdle { screenError = "storage_error" }
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-storage-error").assertIsDisplayed()
        compose.onNodeWithTag("jobs-notes").performScrollTo().assertTextContains("Do not lose this draft")
        assertTrue(state.applications.isEmpty())
    }

    @Test fun salaryFilterRequiresExplicitCurrencyAndPeriod() {
        render()
        compose.onNodeWithTag("jobs-preferences").performClick()
        repeat(3) { compose.onNodeWithTag("jobs-setup-next").performClick() }
        compose.onNodeWithTag("jobs-advanced-preferences").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-salary-minimum").performScrollTo().performTextInput("2000")
        compose.onNodeWithTag("jobs-preferences-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("jobs-salary-currency").performScrollTo().performTextInput("USD")
        compose.onNodeWithText("Choose a pay period").performScrollTo().performClick()
        compose.onNodeWithText("Per month").performClick()
        compose.onNodeWithTag("jobs-preferences-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { state.preferences.salaryCurrency == "USD" && state.preferences.salaryPeriod == "month" }
        assertEquals("2000", state.preferences.salaryMinimum)
    }

    @Test fun restoredUnconfirmedSaveRetainsDraftWithoutResubmitting() {
        val restoration = StateRestorationTester(compose)
        var writes = 0
        restoration.setContent {
            MeBotTheme {
                JobsScreen(state, false, null, {}, {}, {}, { writes++ }, { _, _ -> }, {}, saving = false)
            }
        }
        prepare()
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("Retain this interrupted draft")
        compose.onNodeWithTag("jobs-save-progress").performScrollTo().performClick()
        compose.onNodeWithText("Saving your changes…").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-save-unconfirmed").assertIsDisplayed()
        compose.onNodeWithTag("jobs-notes").performScrollTo().assertTextContains("Retain this interrupted draft")
        assertEquals(1, writes)
        assertTrue(state.applications.isEmpty())
    }

    @Test fun guidedSetupUsesChoicesAndPersistsEachStepWithoutAi() {
        state = JobsState(jobs = listOf(job))
        render()
        compose.onNodeWithTag("jobs-role-engineer").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        compose.onNodeWithTag("jobs-setup-step-1").assertIsDisplayed()
        compose.onNodeWithText("Python").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        compose.onNodeWithTag("jobs-country-search").performScrollTo().performTextInput("Tanzania")
        compose.onNode(hasText("Tanzania") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        compose.onNodeWithTag("jobs-setup-step-3").assertIsDisplayed()
        compose.onNodeWithTag("jobs-preferences-save").performClick()
        compose.waitUntil(5_000) { state.preferences.onboarded }
        assertTrue(state.preferences.roles.contains("engineer"))
        assertTrue(state.preferences.skills.contains("Python"))
        assertEquals("Tanzania", state.preferences.country)
        assertEquals(3, state.preferences.onboardingStep)
        assertTrue(assistantRequests.isEmpty())
    }

    @Test fun aiReviewNeverSelectsPersonalContextByDefault() {
        val snapshot = JobsContextSnapshot("revision-one", listOf(JobsContextCandidate("skill", "profile", "About you", "Skills: Kotlin",
            JobsContextField.SKILL_TO_CONFIRM, "Kotlin", "self_reported_skill_review")))
        render(context = snapshot); prepare()
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("My unpublished evidence")
        compose.onNodeWithText("Draft a cover letter").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextContains("No personal information selected")
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("", assistantRequests.single().sharedContext)
        assertEquals("My unpublished evidence", assistantRequests.single().application?.notes)
        assertEquals("preparing", assistantRequests.single().application?.status)
        assertTrue(opened.isEmpty())
    }

    @Test fun aiReviewSharesOnlyExplicitlySelectedExactEvidence() {
        val snapshot = JobsContextSnapshot("revision-two", listOf(JobsContextCandidate("skill", "profile", "About you", "Skills: Kotlin",
            JobsContextField.SKILL_TO_CONFIRM, "Kotlin", "self_reported_skill_review")))
        render(context = snapshot); prepare()
        compose.onNodeWithText("Draft a cover letter").performScrollTo().performClick()
        compose.onNodeWithContentDescription("About you\nSkills: Kotlin").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextEquals("Skills: Kotlin")
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("Skills: Kotlin", assistantRequests.single().sharedContext)
        assertEquals("revision-two", assistantRequests.single().contextRevision)
    }

}
