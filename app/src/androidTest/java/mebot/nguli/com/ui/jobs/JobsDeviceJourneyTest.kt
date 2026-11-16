package mebot.nguli.com.ui.jobs

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.jobs.JobsAlertScheduler
import mebot.nguli.com.jobs.JobsRefreshScheduler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual MainActivity and Android lifecycle. Synthetic fixtures only; AI is never connected or sent. */
@RunWith(AndroidJUnit4::class)
class JobsDeviceJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MeBotApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val variant get() = InstrumentationRegistry.getArguments().getString("qaVariant", "phone-light")
    private val fixture = JobPosting(id = "device-fixture", title = "Software Engineer", company = "Example Employer",
        location = "Worldwide", remote = true, employmentType = "full_time", source = "Remotive",
        description = "Synthetic verification listing, not an open vacancy. Build reliable software, review code and document integrations. Location and employment eligibility still need confirmation.",
        url = "https://example.com/jobs/device-fixture", tags = listOf("Kotlin", "SQL"))

    @Before fun seedFreshSyntheticState() = runBlocking {
        JobsAlertScheduler.cancel(app)
        JobsRefreshScheduler.cancel(app)
        app.feedEngine.disconnect()
        app.repository.update { AppState(profile = it.profile.copy(onboardingComplete = true),
            settings = it.settings.copy(language = "en", theme = if (variant.contains("dark")) "dark" else "light")) }
        app.feedRepository.update { FeedState(onboardingComplete = true, interestsConfigured = true,
            quickGuideSeen = true, personalSeedApplied = true, modules = emptyList(), lastRefreshMillis = System.currentTimeMillis()) }
        app.jobsRepository.erase()
        // The fixture is recent and the manual source window is intentionally occupied. Combined
        // with the emulator's disabled Wi-Fi/data this prevents public or private provider calls.
        val now = System.currentTimeMillis()
        app.jobsRepository.update { JobsState(jobs = listOf(fixture.copy(fetchedAt = now)), lastAttemptAt = now, lastRefreshAt = now) }
    }

    @After fun cleanup() {
        scenario?.close()
        JobsRefreshScheduler.cancel(app)
        JobsAlertScheduler.cancel(app)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java).setAction("mebot.nguli.com.OPEN_JOBS"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("nav-jobs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-jobs").assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "jobs-device-qa/$variant").apply { mkdirs() }
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        File(directory, "$name.semantics.txt").writeText(runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("Multiple native roots; inspect the screenshot and instrumentation assertions."))
        File(directory, "PROVENANCE.txt").writeText("Native Android MainActivity instrumented capture. Synthetic job fixture; no live listing, account or AI response. Variant: $variant. Device fontScale=${app.resources.configuration.fontScale}; widthDp=${app.resources.configuration.screenWidthDp}.\n")
    }
    private fun openFixture() {
        waitFor("jobs-screen")
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("job-card-${fixture.id}"))
        compose.onNodeWithTag("job-card-${fixture.id}").performClick()
        waitFor("jobs-detail")
    }

    @Test fun fourStepOnboardingPersistsAndShowsNativeCardsWithoutAi() {
        launch()
        waitFor("jobs-setup-step-0")
        capture("01-roles")
        compose.onNodeWithTag("jobs-role-engineer").performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        waitFor("jobs-setup-step-1")
        capture("02-skills")
        compose.onNodeWithText("SQL", useUnmergedTree = false).performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        waitFor("jobs-setup-step-2")
        capture("03-location")
        compose.onNodeWithTag("jobs-country-search").performScrollTo().performTextInput("Tanzania")
        compose.onNode(hasText("Tanzania", substring = false) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithTag("jobs-setup-next").performClick()
        waitFor("jobs-setup-step-3")
        capture("04-review")
        compose.onNodeWithTag("jobs-preferences-save").performClick()
        waitFor("jobs-screen")
        capture("05-matches")
        val saved = runBlocking { app.jobsRepository.current() }
        assertTrue(saved.preferences.onboarded)
        assertTrue(saved.preferences.roles.contains("engineer"))
        assertEquals("Tanzania", saved.preferences.country)
        assertFalse(saved.preferences.alertsEnabled)
        scenario!!.recreate()
        waitFor("jobs-screen")
        capture("06-restored-matches")
        assertTrue(runBlocking { app.jobsRepository.current().preferences.onboarded })
    }

    @Test fun documentAndNotesSurviveActivityRecreationAndPickerCancellation() {
        runBlocking { app.jobsRepository.preferences(JobPreferences(roles = "engineer", skills = "SQL", onboarded = true)) }
        launch(); openFixture(); capture("07-detail")
        compose.onNodeWithTag("jobs-apply-source").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("jobs-prepare").performClick()
        waitFor("jobs-notes")
        compose.onNodeWithTag("jobs-notes").performScrollTo().performTextInput("Synthetic note: confirm location eligibility.")
        capture("08-workspace")
        scenario!!.recreate()
        waitFor("jobs-notes")
        compose.onNodeWithTag("jobs-notes").performScrollTo().assertTextContains("Synthetic note: confirm location eligibility.")
        compose.onNodeWithTag("jobs-open-document").performScrollTo().performClick()
        waitFor("jobs-document-text")
        compose.onNodeWithTag("jobs-document-text").performScrollTo()
            .assertTextContains(app.getString(R.string.jobs_draft_outline, fixture.title, fixture.company))
        compose.onNodeWithTag("jobs-document-text").performTextReplacement("Synthetic application draft for device verification only.")
        compose.onNodeWithTag("jobs-export-pdf").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        capture("09-document-reviewed")
        compose.onNodeWithTag("jobs-export-pdf").performScrollTo().assertIsEnabled().performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        capture("10-system-file-picker")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        waitFor("jobs-document-text")
        compose.onNodeWithTag("jobs-document-text").performScrollTo().assertTextContains("Synthetic application draft for device verification only.")
        capture("11-picker-cancel-restored")
        scenario!!.recreate()
        waitFor("jobs-document-text")
        compose.onNodeWithTag("jobs-document-text").performScrollTo().assertTextContains("Synthetic application draft for device verification only.")
        assertFalse(runBlocking { app.jobsRepository.current().applications.any { it.status == "applied" } })

        // Recreation above verifies Android saved-instance state. Explicitly save and then
        // finish/relaunch as well, so rememberSaveable cannot masquerade as durable storage.
        compose.onNodeWithText(app.getString(R.string.jobs_save_draft)).performScrollTo().performClick()
        waitFor("jobs-notes") // Save acknowledgement returns to the workspace only after persistence.
        val expectedNote = "Synthetic note: confirm location eligibility."
        val expectedDraft = "Synthetic application draft for device verification only."
        val saved = runBlocking { app.jobsRepository.current() }.applications.single { it.jobId == fixture.id }
        assertEquals(expectedNote, saved.notes)
        assertEquals(expectedDraft, saved.draft)
        assertEquals("preparing", saved.status)
        // Inspect the actual DataStore file without opening a second DataStore on that file.
        val persisted = Json { ignoreUnknownKeys = true }.decodeFromString<JobsState>(
            app.filesDir.resolve("mebot/jobs.json").readText()
        ).applications.single { it.jobId == fixture.id }
        assertEquals(expectedNote, persisted.notes)
        assertEquals(expectedDraft, persisted.draft)
        assertEquals("preparing", persisted.status)

        scenario!!.close()
        scenario = null
        launch()
        openFixture()
        compose.onNodeWithTag("jobs-prepare").performClick()
        waitFor("jobs-notes")
        compose.onNodeWithTag("jobs-notes").performScrollTo().assertTextContains(expectedNote)
        compose.onNodeWithTag("jobs-open-document").performScrollTo().performClick()
        waitFor("jobs-document-text")
        compose.onNodeWithTag("jobs-document-text").performScrollTo().assertTextContains(expectedDraft)
        capture("13-durable-draft-fresh-launch")
    }

    @Test fun aiConsentShowsNoPersonalDataSelectedAndCancelSendsNothing() {
        runBlocking { app.jobsRepository.preferences(JobPreferences(roles = "engineer", onboarded = true)) }
        launch(); openFixture()
        compose.onNodeWithText(app.getString(R.string.jobs_understand_role)).performScrollTo().performClick()
        waitFor("jobs-ai-send")
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo()
            .assertTextEquals(app.getString(R.string.jobs_ai_nothing_shared))
        capture("12-ai-consent-not-sent")
        compose.onNodeWithText(app.getString(R.string.jobs_cancel)).performClick()
        compose.onNodeWithTag("jobs-ai-send").assertDoesNotExist()
        assertTrue(runBlocking { app.jobsRepository.current().applications.isEmpty() })
    }
}
