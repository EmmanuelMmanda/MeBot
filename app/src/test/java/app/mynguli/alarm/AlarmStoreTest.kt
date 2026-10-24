package app.mynguli.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.AlarmPlan
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmStoreTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AlarmStore(context)
        store.clear()
    }

    @After fun cleanUp() { store.clear() }

    @Test fun newControllerReadsTheWholeInProgressSession() {
        val plan = AlarmPlan(id = "a", label = "Asubuhi · café", requireCheckpoint = true, checkpointCode = "marker-123", maxSnoozes = 2)
        val session = AlarmSession(id = "s", plan = plan, occurrenceId = "o", startedAtMillis = 100_000L,
            phase = AlarmPhase.WAITING_FOLLOWUP, snoozesUsed = 2, walkUsed = true, verificationStage = 1,
            deadlineAtMillis = 280_000L, deadlineElapsedMillis = 15_000L, bootCount = 8, isTest = true)
        store.session = session
        assertEquals(session, AlarmController(context).session())
    }

    @Test fun noDeadlineRemainsNullAcrossPersistence() {
        val session = AlarmSession(plan = AlarmPlan(id = "a"), occurrenceId = "o", startedAtMillis = 1L)
        store.session = session
        assertEquals(session, AlarmStore(context).session)
        assertNull(AlarmStore(context).session?.deadlineAtMillis)
    }

    @Test fun soundSelectionAndSpeechSurviveEveryOperationalSnapshot() {
        val plan = AlarmPlan(id = "audio", soundId = "custom", soundUri = "content://test/document/123",
            soundName = "Selected bell.wav", speechEnabled = true, speechMessage = "Time to get up.")
        store.plans = listOf(plan)
        store.scheduled = listOf(ScheduledAlarm("scheduled", 10L, plan))
        store.pending = listOf(ScheduledAlarm("pending", 20L, plan))
        store.session = AlarmSession(plan = plan, occurrenceId = "active", startedAtMillis = 1L)
        val reopened = AlarmStore(context)
        assertEquals(plan, reopened.plans.single())
        assertEquals(plan, reopened.scheduled.single().plan)
        assertEquals(plan, reopened.pending.single().plan)
        assertEquals(plan, reopened.session?.plan)
    }

    @Test fun legacyOperationalSnapshotKeepsClassicAndSpeechOff() {
        val json = planToJson(AlarmPlan(id = "old", volumePercent = 60, rampSeconds = 30))
        listOf("soundId", "soundUri", "soundName", "speechEnabled", "speechMessage").forEach(json::remove)
        val plan = planFromJson(json)
        assertEquals("classic", plan.soundId)
        assertFalse(plan.speechEnabled)
        assertEquals(60, plan.volumePercent)
        assertEquals(30, plan.rampSeconds)
    }

    @Test fun completedOneTimeIntentIsPersistedIndependentlyOfActivity() {
        store.consumed = mapOf("a" to "7:0:")
        assertEquals(setOf("a"), AlarmController(context).completedOneTimeIds())
    }

    @Test fun operationalLogIsBoundedAndContainsNoQrPayload() {
        repeat(205) { store.log("checkpoint_completed", "alarm-a", "event=$it") }
        val events = AlarmStore(context).events()
        assertEquals(200, events.size)
        assertEquals("event=5", events.first().detail)
        assertEquals("event=204", events.last().detail)
    }

    @Test fun clearRemovesSessionsPendingOccurrencesAndDiagnostics() {
        val plan = AlarmPlan(id = "a")
        store.plans = listOf(plan)
        store.pending = listOf(ScheduledAlarm("token", 10L, plan))
        store.session = AlarmSession(plan = plan, occurrenceId = "token", startedAtMillis = 10L)
        store.log("test", "a")
        store.language = "sw"
        store.clear()
        val reopened = AlarmStore(context)
        assertNull(reopened.session)
        assertTrue(reopened.pending.isEmpty())
        assertTrue(reopened.plans.isEmpty())
        assertTrue(reopened.events().isEmpty())
        assertEquals("en", reopened.language)
    }
}
