package mebot.nguli.com.ui.jobs

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val job = JobPosting(id = "fixture-job", title = "Software engineer", company = "Example employer",
        location = "Worldwide", remote = true, employmentType = "full_time", description = "Build reliable systems.",
        url = "https://example.com/jobs/fixture", source = "Remotive")
    private var state by mutableStateOf(JobsState(preferences = JobPreferences(onboarded = true), jobs = listOf(job)))
    private val opened = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val assistantRequests = mutableListOf<JobsAssistantRequest>()
    private var pendingApplication by mutableStateOf<JobApplication?>(null)
    private var pendingPreferences by mutableStateOf<JobPreferences?>(null)
    private var preferencesWrites = 0
    private var screenError by mutableStateOf<String?>(null)
    private fun render(error: String? = null, draft: String = "", acknowledge: Boolean = true, context: JobsContextSnapshot = JobsContextSnapshot(), connected: Boolean = true) {
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
                JobsScreen(state, false, screenError, {}, { preferencesWrites++; pendingPreferences = it }, { id ->
                    val old = state.applications.firstOrNull { it.jobId == id }
                    state = state.copy(applications = state.applications.filterNot { it.jobId == id } +
                        (old?.copy(bookmarked = !old.bookmarked) ?: JobApplication(id, snapshot = job)))
                }, { application ->
                    pendingApplication = application
                }, { _, prompt -> asked += prompt }, { opened += it }, { draft }, contextSnapshot = context, onAssistant = { _, request -> assistantRequests += request }, aiConnected = connected)
            }
        }
    }
    private fun openJob() {
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).performClick()
    }
    private fun openAiReviewWithTrace() {
        println("JOBS_REVIEW_TRACE before detail action scroll")
        val action = compose.onNodeWithText("Understand this role with AI")
        action.performScrollTo()
        println("JOBS_REVIEW_TRACE after detail action scroll")
        action.assertIsDisplayed()
        println("JOBS_REVIEW_TRACE before detail action click")
        action.performClick()
        println("JOBS_REVIEW_TRACE after detail action click")
        compose.onNodeWithText(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(mebot.nguli.com.R.string.jobs_ai_review_title)).assertIsDisplayed()
        compose.onNodeWithTag("jobs-ai-fresh-chat").assertExists()
        println("JOBS_REVIEW_TRACE dialog semantics idle")
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

    @Test fun noMatchesBrowseLatestClearsQueryWithoutChangingSavedPreferences() {
        val preferences = JobPreferences(roles = "accountant", skills = "Excel", country = "Tanzania", onboarded = true)
        state = state.copy(preferences = preferences)
        render()
        compose.onNodeWithTag("jobs-search").performScrollTo().performTextInput("unmatched-search-text")
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("jobs-empty-edit-preferences"))
        compose.onNodeWithTag("jobs-empty-edit-preferences").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("jobs-empty-browse-all").performClick()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("jobs-search"))
        compose.onNodeWithTag("jobs-search").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("jobs-tab-recent").assertIsSelected()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("job-card-${job.id}"))
        compose.onNodeWithTag("job-card-${job.id}").assertIsDisplayed()
        assertEquals(preferences, state.preferences)
        assertEquals(0, preferencesWrites)
        assertTrue(asked.isEmpty())
    }

    @Test fun sourceFailureKeepsCachedJobVisible() {
        render(error = "offline")
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasText(job.title))
        compose.onNodeWithText(job.title).assertIsDisplayed()
        assertTrue(asked.isEmpty())
    }

    @Test fun latePersistedOnboardingStateShowsBrowse() {
        state = JobsState()
        render()
        compose.onNodeWithTag("jobs-setup-step-0").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(preferences = JobPreferences(onboarded = true), jobs = listOf(job)) }
        compose.waitForIdle()
        compose.onNodeWithTag("jobs-screen").assertIsDisplayed()
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
        render(context = snapshot); openJob()
        openAiReviewWithTrace()
        compose.onNodeWithTag("jobs-ai-fresh-chat").performScrollTo().assertTextEquals(
            "Each Jobs action starts a fresh chat. Earlier chats remain in MeBot history.")
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextEquals(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(mebot.nguli.com.R.string.jobs_ai_nothing_shared))
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("", assistantRequests.single().sharedContext)
        assertTrue(opened.isEmpty())
    }

    @Test fun aiReviewSharesOnlyExplicitlySelectedExactEvidence() {
        val snapshot = JobsContextSnapshot("revision-two", listOf(JobsContextCandidate("skill", "profile", "About you", "Skills: Kotlin",
            JobsContextField.SKILL_TO_CONFIRM, "Kotlin", "self_reported_skill_review")))
        render(context = snapshot); openJob()
        openAiReviewWithTrace()
        compose.onNodeWithContentDescription("About you\nSkills: Kotlin").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextEquals("Skills: Kotlin")
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("Skills: Kotlin", assistantRequests.single().sharedContext)
        assertEquals("revision-two", assistantRequests.single().contextRevision)
    }

    @Test fun unsavingLegacyRecordPreservesPrivateDraftAndSource() {
        val legacy = JobApplication(job.id, status = "preparing", notes = "Private note", draft = "Private draft", snapshot = job)
        state = state.copy(applications = listOf(legacy))
        render(); openJob()
        compose.onNodeWithContentDescription("Remove saved job").performClick()
        assertFalse(state.applications.single().bookmarked)
        assertEquals(legacy.notes, state.applications.single().notes)
        assertEquals(legacy.draft, state.applications.single().draft)
        compose.onNodeWithTag("jobs-prepare").assertDoesNotExist()
        compose.onNodeWithTag("jobs-apply-source").assertIsDisplayed()
    }

    @Test fun closedListingWarnsWithoutRemovingSourceAccess() {
        state = state.copy(jobs = listOf(job.copy(sourceStatus = "closed")))
        render(); openJob()
        compose.onNodeWithTag("jobs-listing-closed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("jobs-apply-source").assertIsEnabled()
    }

    @Test fun customQuestionBoundaryRetainsInvalidInputUntilCorrected() {
        render(); openJob()
        openAiReviewWithTrace()
        val field = compose.onNodeWithTag("jobs-ai-question")
        field.performScrollTo().performTextReplacement("a".repeat(1000))
        compose.onNodeWithTag("jobs-ai-send").assertIsEnabled()
        field.performTextReplacement("a".repeat(1001))
        compose.onNodeWithTag("jobs-ai-send").assertIsNotEnabled()
        field.assertTextContains("a".repeat(1001))
        assertTrue(assistantRequests.isEmpty())
        field.performTextReplacement("bad\u0000question")
        compose.onNodeWithTag("jobs-ai-send").assertIsNotEnabled()
        field.assertTextContains("bad\u0000question")
        field.performTextReplacement("a".repeat(1000))
        compose.onNodeWithTag("jobs-ai-send").assertIsEnabled().performClick()
        assertEquals("a".repeat(1000), assistantRequests.single().question)
        assertEquals(mebot.nguli.com.jobs.JobsAssistantIntent.QUESTION, assistantRequests.single().intent)
    }

    @Test fun isolatedReviewSurfaceReachesIdleWithoutJobsScreen() {
        compose.setContent {
            MeBotTheme {
                JobsAiReview(mebot.nguli.com.jobs.JobsAssistantIntent.REQUIREMENTS, "Explain this role",
                    JobsContextSnapshot(), "Test provider", true, true, {}, {}, {})
            }
        }
        println("JOBS_REVIEW_TRACE isolated review surface content set")
        compose.onNodeWithTag("jobs-ai-fresh-chat").assertExists()
        compose.onNodeWithTag("jobs-ai-send").assertIsDisplayed().assertIsEnabled()
        println("JOBS_REVIEW_TRACE isolated review surface idle")
    }

    @Test fun detailActionScrollAndPointerClickWithoutDialogReachIdle() {
        var clicks = 0
        compose.setContent {
            MeBotTheme {
                JobDetails(job, JobPreferences(), null, false, false, {}, {}, {}, { clicks++ })
            }
        }
        println("JOBS_REVIEW_TRACE isolated detail before scroll")
        compose.onNodeWithText("Understand this role with AI").performScrollTo()
        println("JOBS_REVIEW_TRACE isolated detail after scroll")
        compose.onNodeWithText("Understand this role with AI").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
        println("JOBS_REVIEW_TRACE isolated detail pointer click idle")
    }

    @Test fun jobicyAnywhereIsUnassignedAndPreservesRawSourceRegion() {
        state = state.copy(jobs = listOf(job.copy(source = "Jobicy", location = "Anywhere")))
        render()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("job-card-${job.id}"))
        compose.onNodeWithText("Region not specified by Jobicy", substring = true).assertIsDisplayed()
        openJob()
        compose.onNodeWithText("Region not specified by Jobicy").assertIsDisplayed()
        compose.onNodeWithText("Source region label: Anywhere. Confirm country eligibility with the employer.")
            .performScrollTo().assertIsDisplayed()
        assertEquals("Anywhere", state.jobs.single().location)
    }

    @Test fun otherSourceAnywhereIsNotAttributedToJobicy() {
        state = state.copy(jobs = listOf(job.copy(source = "Remotive", location = "Anywhere")))
        render()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("job-card-${job.id}"))
        compose.onNodeWithText("Anywhere", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Region not specified by Jobicy", substring = true).assertDoesNotExist()
        openJob()
        compose.onNodeWithText("Anywhere").assertIsDisplayed()
        compose.onNodeWithText("Source region label:", substring = true).assertDoesNotExist()
    }

    @Test fun disconnectedQuestionDraftSurvivesConnectAndExplicitReopenWithoutSending() {
        render(connected = false); openJob(); openAiReviewWithTrace()
        compose.onNodeWithTag("jobs-ai-question").performScrollTo().performTextReplacement("Is this role open in Tanzania?")
        compose.onNodeWithTag("jobs-ai-connect").performClick()
        compose.onNodeWithTag("jobs-ai-question").assertDoesNotExist()
        openAiReviewWithTrace()
        compose.onNodeWithTag("jobs-ai-question").performScrollTo().assertTextContains("Is this role open in Tanzania?")
        compose.onNodeWithTag("jobs-ai-send").assertDoesNotExist()
        assertTrue(assistantRequests.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        openAiReviewWithTrace()
        compose.onNodeWithTag("jobs-ai-question").performScrollTo().assertTextContains("Is this role open in Tanzania?")
        assertTrue(assistantRequests.isEmpty())
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun captureReviewSurfaceLightPhone() = captureReviewSurface("light")

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun captureReviewSurfaceDarkPhone() = captureReviewSurface("dark")

    private fun captureReviewSurface(theme: String) {
        compose.setContent {
            MeBotTheme(mebot.nguli.com.core.Settings(theme = theme)) {
                JobsAiReview(mebot.nguli.com.jobs.JobsAssistantIntent.REQUIREMENTS, "Explain this role and its requirements.",
                    JobsContextSnapshot(), "Not connected", true, false, {}, {}, {})
            }
        }
        compose.onNodeWithTag("jobs-ai-connect").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("jobs-ai-review-screen").assertIsDisplayed()
        // Match this project's proven Robolectric native-graphics capture path. Compose's
        // captureToImage waits for a hardware redraw callback unavailable in this host setup.
        val image = compose.runOnIdle {
            val window = compose.activity.window
            window.decorView.invalidate()
            android.graphics.Bitmap.createBitmap(window.decorView.width, window.decorView.height,
                android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                var status: Int? = null
                android.view.PixelCopy.request(window, bitmap, { status = it }, android.os.Handler(android.os.Looper.getMainLooper()))
                assertEquals(android.view.PixelCopy.SUCCESS, status)
            }
        }
        val directory = java.io.File("build/outputs/screenshots/jobs-review-host").apply { mkdirs() }
        java.io.File(directory, "review-360-$theme.png").outputStream().use {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        java.io.File(directory, "PROVENANCE.txt").writeText("Robolectric native-graphics host rendering, 360dp. Synthetic review. Not an emulator/device lifecycle verification.\n")
    }

    @Test fun reviewHidesUnderlyingDetailActionsFromAccessibilityUntilCancelled() {
        render(); openJob(); openAiReviewWithTrace()
        compose.onNodeWithTag("jobs-apply-source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save job").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("jobs-apply-source").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save job").assertIsDisplayed()
        assertTrue(opened.isEmpty())
        assertTrue(assistantRequests.isEmpty())
    }

    @Test fun changedSourceRevisionRequiresFreshSelectionBeforeSharingNewFacts() {
        var snapshot by mutableStateOf(JobsContextSnapshot("before", listOf(JobsContextCandidate("same-id", "profile", "About you",
            "Skills: Kotlin", JobsContextField.SKILL_TO_CONFIRM, "Kotlin", "self_reported_skill_review"))))
        compose.setContent {
            MeBotTheme {
                JobsAiReview(mebot.nguli.com.jobs.JobsAssistantIntent.REQUIREMENTS, "Explain this role", snapshot,
                    "Test provider", true, true, {}, {}, { assistantRequests += it })
            }
        }
        compose.onNodeWithContentDescription("About you\nSkills: Kotlin").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextEquals("Skills: Kotlin")
        compose.runOnIdle { snapshot = snapshot.copy(revision = "after", candidates = snapshot.candidates.map {
            it.copy(sourceText = "Skills: private new fact")
        }) }
        compose.onNodeWithContentDescription("About you\nSkills: private new fact").performScrollTo().assertIsOff()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo().assertTextEquals(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(mebot.nguli.com.R.string.jobs_ai_nothing_shared))
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("", assistantRequests.single().sharedContext)
        assertEquals("after", assistantRequests.single().contextRevision)
        compose.onNodeWithContentDescription("About you\nSkills: private new fact").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-ai-send").performClick()
        assertEquals("Skills: private new fact", assistantRequests.last().sharedContext)
    }

}
