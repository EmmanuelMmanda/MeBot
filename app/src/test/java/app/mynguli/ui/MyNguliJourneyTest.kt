package app.mynguli.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.mynguli.MainActivity
import app.mynguli.MyNguliApplication
import app.mynguli.MyNguliViewModel
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedTestApplication
import app.mynguli.feed.FeedViewModel
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Exercises the installed activity's navigation, storage and native rendering.
 * Fixtures are synthetic and remain in test source. PNGs are review artifacts,
 * not pixel baselines or evidence of phone-specific alarm delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyNguliJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val application: MyNguliApplication
        get() = compose.activity.application as MyNguliApplication

    @Before
    fun resetTestInstallation() {
        compose.waitForIdle()
        val model = ViewModelProvider(compose.activity)[MyNguliViewModel::class.java]
        assertSame(application, model.getApplication<MyNguliApplication>())
        runBlocking { application.repository.erase(); application.feedRepository.erase() }
        application.alarms.clearAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        waitForTag("onboarding-screen")
    }

    @Test
    fun welcomeAndSuggestedPlanNeedNoPersonalQuestionsOrTyping() {
        screenshot("01-mebot-welcome")
        explore()
        assertTrue(current().profile.onboardingComplete)
        assertEquals("", current().profile.name)
        assertEquals("", current().profile.intention)
        assertTrue(current().alarms.isEmpty())
        screenshot("02-mebot-home-empty")

        openCheckIn()
        screenshot("03-mebot-check-in")
        val suggestion = text(R.string.mb_suggestion_focus)
        clickContent(text(R.string.mb_use_plan))
        waitForTag("nav-modules")
        val visit = current().visits.single()
        assertEquals(suggestion, visit.nextStep)
        assertEquals("", visit.arrival)
        assertEquals("", visit.departure)
        assertEquals("", visit.note)
        assertEquals(current().settings.defaultFocusMinutes, visit.focusMinutes)
        assertEquals(suggestion, visit.outcomes.single().text)
        assertNotNull(visit.completedAtMillis)

        compose.activityRule.scenario.recreate()
        waitForTag("nav-modules")
        assertEquals(visit, current().visits.single())

        // Explicit synthetic records demonstrate a populated dashboard. No
        // personal identity or generated history is a production default.
        val now = System.currentTimeMillis()
        val date = LocalDate.now()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        runBlocking {
            application.repository.update { state ->
                state.copy(
                    profile = state.profile.copy(name = "Alex"),
                    alarms = listOf(AlarmPlan(
                        id = "dashboard-morning", label = "Morning", hour = 7, minute = 0,
                        days = (1..7).toSet(), dismissMode = AlarmDismissMode.MATH,
                    )),
                    visits = state.visits + listOf(1L, 3L, 5L).map { offset ->
                        val outcome = Outcome(text = "Read a few pages")
                        DailyVisit(
                            id = "dashboard-day-$offset", date = date.minusDays(offset).toString(),
                            createdAtMillis = now - offset * 86_400_000L,
                            updatedAtMillis = now - offset * 86_400_000L,
                            outcomes = listOf(outcome), selectedOutcomeId = outcome.id,
                            nextStep = outcome.text, completedAtMillis = now - offset * 86_400_000L,
                        )
                    },
                )
            }
        }
        runBlocking { application.feedRepository.update { it.copy(name = "Alex") } }
        navigation(R.string.today)
        waitForText(text(R.string.feed_greeting, "Alex"))
        waitForText(text(R.string.feed_next_alarm))
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.feed_greeting, "Alex"))
        screenshot("04-mebot-home-with-plan")
        navigation(R.string.tools)
        waitForTag("module-alarm")
        screenshot("08-mebot-tools")
        navigation(R.string.you)
        waitForText(text(R.string.feed_you_title))
        screenshot("09-mebot-preferences")
    }

    @Test
    fun optionalMoodAndEditedStepSurviveRecreationAndSave() {
        explore()
        openCheckIn()
        clickContent(text(R.string.arrival_scattered))
        compose.onNodeWithText(text(R.string.mb_suggestion_clear)).assertIsDisplayed()
        clickContent(text(R.string.mb_make_it_mine))
        compose.onNode(hasText(text(R.string.next_step)) and hasSetTextAction())
            .performScrollTo().performTextReplacement("Open the document and write one paragraph")
        clickContent(text(R.string.mb_add_note))
        typeInto(R.string.mb_note_optional, "A few unfinished things are competing for my attention.")
        waitForState { it.visits.singleOrNull()?.note?.isNotBlank() == true }
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.mb_check_in_title))
        compose.onNode(hasText(text(R.string.next_step)) and hasSetTextAction())
            .performScrollTo().assertTextContains("Open the document and write one paragraph")
        clickContent(text(R.string.mb_use_plan))
        waitForTag("nav-modules")
        val saved = current().visits.single()
        assertEquals("scattered", saved.arrival)
        assertEquals("", saved.departure)
        assertEquals(5, saved.focusMinutes)
        assertEquals("Open the document and write one paragraph", saved.nextStep)
        assertEquals("A few unfinished things are competing for my attention.", saved.note)
        assertNotNull(saved.completedAtMillis)

        // An edited completed plan stays local until explicit Save.
        openCheckIn()
        if (compose.onAllNodes(hasText(text(R.string.next_step)) and hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            clickContent(text(R.string.mb_make_it_mine))
        }
        compose.onNode(hasText(text(R.string.next_step)) and hasSetTextAction())
            .performScrollTo().performTextReplacement("Review the opening paragraph")
        assertEquals(saved.nextStep, current().visits.single().nextStep)
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.mb_check_in_title))
        compose.onNode(hasText(text(R.string.next_step)) and hasSetTextAction())
            .performScrollTo().assertTextContains("Review the opening paragraph")
        clickContent(text(R.string.mb_use_plan))
        waitForTag("nav-modules")
        assertEquals("Review the opening paragraph", current().visits.single().nextStep)
        assertEquals(saved.completedAtMillis, current().visits.single().completedAtMillis)
        assertEquals(1, current().visits.size)
    }

    @Test
    fun firstAlarmDefaultsToMathAndCanBeSavedOffWithoutExactAccess() {
        explore()
        navigation(R.string.alarms)
        compose.onNodeWithTag("alarm-create").performClick()
        waitForText(text(R.string.mebot_time_title))
        compose.onNodeWithTag("alarm-mode-math").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.mebot_time_title))
        screenshot("05-mebot-alarm-editor")
        clickContent(text(R.string.mebot_medium))
        compose.onNodeWithTag("alarm-more-questions").performScrollTo().performClick()
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        waitForText(text(R.string.mebot_alarm_access))
        compose.onNodeWithText(text(R.string.mebot_save_off)).performClick()
        waitForState { it.alarms.size == 1 }
        waitForText(text(R.string.mebot_alarm_access_needed))
        val alarm = current().alarms.single()
        assertFalse(alarm.enabled)
        assertEquals(AlarmDismissMode.MATH, alarm.dismissMode)
        assertEquals(MathDifficulty.MEDIUM, alarm.mathDifficulty)
        assertEquals(4, alarm.mathQuestionCount)
        assertEquals((1..5).toSet(), alarm.days)
        assertEquals("", alarm.label)
        assertTrue(current().profile.onboardingComplete)
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.mebot_alarm_access_needed))
        assertEquals(alarm, current().alarms.single())
        screenshot("06-mebot-alarms")
    }

    @Test
    fun alarmDraftSurvivesCheckpointSetupAndActivityRecreation() {
        explore()
        navigation(R.string.alarms)
        compose.onNodeWithTag("alarm-create").performClick()
        waitForText(text(R.string.mebot_time_title))
        compose.onNodeWithTag("alarm-advanced").performScrollTo().performClick()
        typeInto(R.string.mebot_alarm_name, "Morning walk")
        compose.onNodeWithTag("alarm-mode-qr").performScrollTo().performClick()
        clickContent(text(R.string.mebot_checkpoint_generate))
        waitForText(text(R.string.mebot_checkpoint_short))
        typeInto(R.string.checkpoint_name, "Hallway")
        clickContent(text(R.string.mebot_checkpoint_generate))
        waitForText(text(R.string.mebot_checkpoint_export))
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.mebot_time_title))
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.mebot_time_title))
        compose.onNode(hasText(text(R.string.mebot_alarm_name)) and hasSetTextAction())
            .performScrollTo().assertTextContains("Morning walk")
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        waitForText(text(R.string.mebot_alarm_access))
        compose.onNodeWithText(text(R.string.mebot_save_off)).performClick()
        waitForState { it.alarms.size == 1 }
        val state = current()
        val alarm = state.alarms.single()
        assertEquals("Morning walk", alarm.label)
        assertTrue(alarm.requireCheckpoint)
        assertEquals(AlarmDismissMode.QR, alarm.dismissMode)
        assertFalse(alarm.enabled)
        assertEquals("Hallway", alarm.checkpointLabel)
        assertEquals(state.settings.checkpointCode, alarm.checkpointCode)
        assertTrue(alarm.checkpointCode.isNotBlank())
    }

    @Test
    fun reviewingAlarmAccessDoesNotSaveAnUnsavedAlarm() {
        explore()
        navigation(R.string.alarms)
        compose.onNodeWithTag("alarm-create").performClick()
        waitForText(text(R.string.mebot_time_title))
        clickContent(text(R.string.mebot_alarm_access_needed))
        waitForText(text(R.string.mebot_alarm_access))
        compose.onNodeWithText(text(R.string.mebot_save_off)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.onNodeWithTag("alarm-save").performScrollTo().assertIsDisplayed()
        assertTrue(current().alarms.isEmpty())
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.mebot_empty_alarm))
        assertTrue(current().alarms.isEmpty())
    }

    @Test
    fun newAlarmCanBeSavedOffWhileAnUnrelatedAlarmNeedsExactAccess() {
        explore()
        val existing = AlarmPlan(id = "existing", label = "Existing alarm", enabled = true)
        runBlocking { application.repository.update { it.copy(alarms = listOf(existing)) } }
        navigation(R.string.alarms)
        compose.onNodeWithTag("alarm-create").performClick()
        waitForText(text(R.string.mebot_time_title))
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        waitForText(text(R.string.mebot_alarm_access))
        compose.onNodeWithText(text(R.string.mebot_save_off)).performClick()
        waitForState { it.alarms.size == 2 }
        // Persistence precedes scheduling reconciliation and the onSaved navigation callback.
        waitForTag("alarm-create")
        compose.onNodeWithTag("alarm-create").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-save").assertDoesNotExist()
        assertEquals(existing, current().alarms.single { it.id == existing.id })
        val added = current().alarms.single { it.id != existing.id }
        assertFalse(added.enabled)
        assertEquals(AlarmDismissMode.MATH, added.dismissMode)
    }

    @Test
    fun editingLegacyQrAlarmPreservesItsOwnCheckpointUntilExplicitlyChanged() {
        explore()
        val original = AlarmPlan(
            id = "legacy-qr", label = "Legacy QR", enabled = false, requireCheckpoint = true,
            checkpointCode = "legacy-hallway-marker", checkpointLabel = "Hallway",
        )
        runBlocking {
            application.repository.update { state -> state.copy(
                alarms = listOf(original),
                settings = state.settings.copy(checkpointCode = "new-desk-marker", checkpointLabel = "Desk"),
            ) }
        }
        navigation(R.string.alarms)
        clickContent("Legacy QR")
        waitForText(text(R.string.mebot_time_title))
        compose.onNodeWithText("Hallway").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-advanced").performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.mebot_alarm_name)) and hasSetTextAction())
            .performScrollTo().performTextReplacement("Updated QR")
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        waitForState { it.alarms.single().label == "Updated QR" }
        compose.onNodeWithTag("alarm-create").assertIsDisplayed()
        val saved = current().alarms.single()
        assertEquals("legacy-hallway-marker", saved.checkpointCode)
        assertEquals("Hallway", saved.checkpointLabel)
        assertEquals(AlarmDismissMode.QR, saved.effectiveDismissMode)

        waitForTransientMessages()
        clickContent("Updated QR")
        waitForText(text(R.string.mebot_time_title))
        clickContent(text(R.string.mebot_use_checkpoint, "Desk"))
        compose.onNodeWithText("Desk").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-save").performScrollTo().performClick()
        waitForState { it.alarms.single().checkpointCode == "new-desk-marker" }
        assertEquals("Desk", current().alarms.single().checkpointLabel)
    }

    @Test
    fun focusCanPauseRestoreResumeCancelAndSaveOnlyACompletedInterval() {
        explore()
        navigation(R.string.focus_tab)
        waitForText(text(R.string.focus_start))
        clickContent(text(R.string.minutes_value, 5))
        typeInto(R.string.mb_focus_task_optional, "Read two pages")
        clickContent(text(R.string.focus_start))
        waitForText(text(R.string.pause))
        assertEquals("Read two pages", current().focus?.title)
        assertEquals(5, current().focus?.durationMinutes)
        screenshot("07-mebot-focus")
        clickContent(text(R.string.pause))
        waitForText(text(R.string.resume))
        val paused = current().focus
        assertNotNull(paused?.pausedRemainingMillis)
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.resume))
        assertEquals(paused, current().focus)
        clickContent(text(R.string.resume))
        waitForText(text(R.string.pause))
        assertNull(current().focus?.pausedRemainingMillis)
        clickContent(text(R.string.end_session))
        waitForText(text(R.string.focus_cancel_title))
        compose.onAllNodesWithText(text(R.string.end_session)).onLast().performClick()
        waitForText(text(R.string.focus_start))
        assertNull(current().focus)
        assertTrue(current().focusHistory.isEmpty())

        clickContent(text(R.string.focus_start))
        waitForText(text(R.string.pause))
        // Simulate reopening after a real deadline, without a five-minute wait.
        val elapsed = System.currentTimeMillis() - 10_000L
        runBlocking {
            application.repository.update { state -> state.copy(focus = state.focus?.copy(
                startedAtMillis = elapsed - 5 * 60_000L, endsAtMillis = elapsed,
            )) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        waitForText(text(R.string.save_session))
        clickContent(text(R.string.save_session))
        waitForText(text(R.string.focus_start))
        val saved = current()
        assertNull(saved.focus)
        assertEquals(1, saved.focusHistory.size)
        assertEquals("Read two pages", saved.focusHistory.single().title)
        assertEquals(5, saved.focusHistory.single().minutes)
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun compactSwahiliDarkScreenKeepsOptionalModulesAndShortVisitUsable() {
        runBlocking {
            application.feedRepository.update { it.copy(onboardingComplete = true, name = "Alex") }
            application.repository.update { state -> state.copy(
                profile = Profile(name = "Alex", onboardingComplete = true),
                settings = state.settings.copy(language = "sw", theme = "dark", accent = "sage",
                    enabledModules = setOf("daily", "focus", "history")),
            ) }
        }
        waitForTag("feed-screen")
        compose.activityRule.scenario.recreate()
        waitForTag("feed-screen")
        screenshot("11-mebot-small-swahili-dark")
        openCheckIn()
        waitForText(localizedText(R.string.mb_check_in_title, "sw"))
        clickContent(localizedText(R.string.mb_use_plan, "sw"))
        waitForTag("nav-modules")
        assertEquals(localizedText(R.string.mb_suggestion_focus, "sw"), current().visits.single().nextStep)
        assertEquals("", current().visits.single().arrival)
        compose.onNodeWithTag("module-alarm").performScrollTo().assertIsNotEnabled()
        val restored = current()
        assertEquals("sw", restored.settings.language)
        assertEquals("dark", restored.settings.theme)
        assertEquals("sage", restored.settings.accent)
        assertTrue("alarms" !in restored.settings.enabledModules)
    }

    private fun current(): AppState = runBlocking { application.repository.current() }
    private fun text(resource: Int, vararg arguments: Any): String = compose.activity.getString(resource, *arguments)
    private fun localizedText(resource: Int, language: String): String {
        val config = Configuration(compose.activity.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return compose.activity.createConfigurationContext(config).getString(resource)
    }
    private fun explore() {
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        waitForTag("feed-screen")
        waitForState { it.profile.onboardingComplete }
        compose.waitUntil(15_000) {
            val feed = ViewModelProvider(compose.activity)[FeedViewModel::class.java].ui.value
            !feed.refreshing && (feed.state?.lastRefreshMillis ?: 0L) > 0L
        }
        compose.waitForIdle()
        waitForTransientMessages()
    }
    private fun openCheckIn() {
        waitForTransientMessages()
        compose.onNodeWithTag("nav-modules").performClick()
        compose.onNodeWithTag("module-checkin").performScrollTo().performClick()
        waitForTag("daily-screen")
    }
    private fun navigation(resource: Int) {
        val utility = when (resource) { R.string.alarms -> "module-alarm"; R.string.focus_tab -> "module-focus"; else -> null }
        val tab = when (resource) { R.string.today -> "feed"; R.string.you -> "you"; else -> "modules" }
        compose.onNodeWithTag("nav-$tab").performClick()
        utility?.let {
            waitForTag(it)
            compose.onNodeWithTag(it).performScrollTo().performClick()
            if (resource == R.string.alarms) waitForTag("alarm-create") else waitForText(text(R.string.mb_focus_silent))
        }
    }
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForState(predicate: (AppState) -> Boolean) {
        try { compose.waitUntil(15_000) { predicate(current()) } }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("State condition timed out: " + current() + "\n" + compose.onRoot().printToString(), failure)
        }
    }
    private fun waitForText(value: String) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Expected text: " + value + "\n" + compose.onRoot().printToString(), failure)
        }
    }
    private fun clickContent(value: String) {
        compose.onNodeWithText(value).performScrollTo().assertIsEnabled().performClick()
    }
    private fun typeInto(resource: Int, value: String) {
        compose.onNode(hasText(text(resource)) and hasSetTextAction()).performScrollTo().performTextInput(value)
    }
    private fun screenshot(name: String) {
        waitForTransientMessages()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val output = File("build/outputs/screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 0)
    }
    private fun waitForTransientMessages() {
        compose.mainClock.advanceTimeBy(5_000)
        val transientMessages = listOf(R.string.plan_saved, R.string.alarm_saved, R.string.focus_saved,
            R.string.feed_no_results).map { text(it) }
        compose.waitUntil(8_000) {
            transientMessages.all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty() }
        }
    }
}
