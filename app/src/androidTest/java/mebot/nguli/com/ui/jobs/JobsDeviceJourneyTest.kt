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

    @Before fun seedFreshSyntheticState(): Unit = runBlocking {
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

    @Test fun discoverySaveAndDetailSurviveActivityRecreationWithoutLegacyDataLoss() {
        val legacy = JobApplication(fixture.id, status = "preparing", notes = "Synthetic retained note", draft = "Synthetic retained draft", snapshot = fixture)
        runBlocking {
            app.jobsRepository.preferences(JobPreferences(roles = "engineer", skills = "SQL", onboarded = true))
            app.jobsRepository.update { it.copy(applications = listOf(legacy)) }
        }
        launch(); openFixture(); capture("07-detail")
        compose.onNodeWithTag("jobs-apply-source").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("jobs-prepare").assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.jobs_unsave)).performClick()
        compose.waitUntil(5000) { !runBlocking { app.jobsRepository.current().applications.single().bookmarked } }
        scenario!!.recreate(); waitFor("jobs-detail")
        capture("08-restored-detail")
        compose.onNodeWithContentDescription(app.getString(R.string.jobs_save)).performClick()
        compose.waitUntil(5000) { runBlocking { app.jobsRepository.current().applications.single().bookmarked } }
        compose.onNodeWithContentDescription(app.getString(R.string.jobs_back)).performClick()
        waitFor("jobs-screen")
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("jobs-tab-saved"))
        compose.onNodeWithTag("jobs-tab-saved").performClick()
        compose.onNodeWithTag("jobs-screen").performScrollToNode(hasTestTag("job-card-${fixture.id}"))
        compose.onNodeWithTag("job-source-${fixture.id}", useUnmergedTree = true).assertExists()
        capture("09-saved")
        scenario!!.close(); scenario = null
        launch(); openFixture()
        val stored = runBlocking { app.jobsRepository.current() }.applications.single()
        assertTrue(stored.bookmarked)
        assertEquals(legacy.notes, stored.notes)
        assertEquals(legacy.draft, stored.draft)
        assertEquals(legacy.status, stored.status)
        capture("10-fresh-launch-detail")
    }

    @Test fun aiConsentShowsNoPersonalDataSelectedAndCancelSendsNothing() {
        runBlocking { app.jobsRepository.preferences(JobPreferences(roles = "engineer", onboarded = true)) }
        launch(); openFixture()
        compose.onNodeWithText(app.getString(R.string.jobs_understand_role)).performScrollTo().performClick()
        // This fixture deliberately has no provider credentials. The dialog must offer
        // connection, never an enabled send action or an implicit connection attempt.
        waitFor("jobs-ai-connect")
        compose.onNodeWithTag("jobs-ai-send").assertDoesNotExist()
        compose.onNodeWithTag("jobs-ai-context-preview").performScrollTo()
            .assertTextEquals(app.getString(R.string.jobs_ai_nothing_shared))
        capture("12-ai-consent-not-sent")
        compose.onNodeWithText(app.getString(R.string.jobs_cancel)).performClick()
        compose.onNodeWithTag("jobs-ai-send").assertDoesNotExist()
        compose.onNodeWithTag("jobs-ai-connect").assertDoesNotExist()
        compose.onNodeWithTag("jobs-ai-context-preview").assertDoesNotExist()
        assertTrue(runBlocking { app.jobsRepository.current().applications.isEmpty() })
        val feed = runBlocking { app.feedRepository.current() }
        assertTrue(feed.conversations.isEmpty())
        assertEquals(0, feed.requestsToday)
        assertEquals(0L, feed.lastAiRefreshMillis)
        assertTrue(feed.connection.accountId.isBlank())
    }
}
