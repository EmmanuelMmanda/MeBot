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
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmControllerTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    private lateinit var controller: AlarmController

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AlarmStore(context)
        store.clear()
        controller = AlarmController(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanUp() { controller.clearAll() }

    @Test fun repeatedSyncDoesNotDuplicateAnOccurrence() {
        val plan = AlarmPlan(id = "a")
        controller.sync(listOf(plan))
        val first = store.scheduled.single()
        controller.sync(listOf(plan))
        assertEquals(first.token, store.scheduled.single().token)
        assertEquals(first.atMillis, store.scheduled.single().atMillis)
    }

    @Test fun obsoleteOccurrenceTokenCannotRing() {
        controller.sync(listOf(AlarmPlan(id = "a")))
        controller.receiveAlarm("a", "old-token")
        assertNull(controller.session())
        assertEquals(1, store.scheduled.size)
    }

    @Test fun completedOneTimeAlarmDoesNotRearmOnReopen() {
        val plan = AlarmPlan(id = "a", days = emptySet())
        controller.sync(listOf(plan))
        controller.receiveAlarm("a", store.scheduled.single().token)
        assertEquals(setOf("a"), controller.completedOneTimeIds())
        controller.sync(listOf(plan))
        assertTrue(store.scheduled.isEmpty())
        assertNotNull(controller.session())
    }

    @Test fun consumedModelReconciliationDoesNotSilenceAnActiveOneTimeAlarm() {
        val plan = AlarmPlan(id = "a", days = emptySet())
        controller.sync(listOf(plan))
        controller.receiveAlarm("a", store.scheduled.single().token)
        val sessionId = requireNotNull(controller.session()).id
        controller.sync(listOf(plan.copy(enabled = false)))
        assertEquals(sessionId, controller.session()?.id)
        assertTrue(store.scheduled.isEmpty())
    }

    @Test fun explicitDisableCancelsActiveAndPendingOccurrence() {
        val plan = AlarmPlan(id = "a", days = emptySet())
        val queued = AlarmPlan(id = "b", days = emptySet())
        controller.sync(listOf(plan, queued))
        val occurrences = store.scheduled.associateBy { it.plan.id }
        controller.receiveAlarm("a", occurrences.getValue("a").token)
        controller.receiveAlarm("b", occurrences.getValue("b").token)
        val old = requireNotNull(controller.session())
        assertEquals(1, store.pending.size)
        controller.cancel("b")
        assertTrue(store.pending.isEmpty())
        assertEquals(old.id, controller.session()?.id)
        controller.cancel("a")
        assertNull(controller.session())
        assertTrue(store.pending.isEmpty())
        assertTrue(store.scheduled.isEmpty())
        controller.receiveContinuation(old.id, System.currentTimeMillis() - 1)
        assertNull(controller.session())
    }

    @Test fun consumedReconciliationRetainsAnAlreadyQueuedOneTimeOccurrence() {
        val a = AlarmPlan(id = "a", days = emptySet(), requireCheckpoint = false)
        val b = AlarmPlan(id = "b", days = emptySet(), requireCheckpoint = false)
        controller.sync(listOf(a, b))
        val occurrences = store.scheduled.associateBy { it.plan.id }
        controller.receiveAlarm("a", occurrences.getValue("a").token)
        controller.receiveAlarm("b", occurrences.getValue("b").token)
        controller.sync(listOf(a.copy(enabled = false), b.copy(enabled = false)))
        assertEquals("a", controller.session()?.plan?.id)
        assertEquals("b", store.pending.single().plan.id)
        controller.dismiss(requireNotNull(controller.session()).id)
        assertEquals("b", controller.session()?.plan?.id)
        assertTrue(store.pending.isEmpty())
        assertTrue(store.scheduled.isEmpty())
    }

    @Test fun emergencyStopDoesNotImmediatelyStartAQueuedAlarm() {
        val a = AlarmPlan(id = "a", days = emptySet())
        val b = AlarmPlan(id = "b", days = emptySet())
        controller.sync(listOf(a, b))
        val occurrences = store.scheduled.associateBy { it.plan.id }
        controller.receiveAlarm("a", occurrences.getValue("a").token)
        controller.receiveAlarm("b", occurrences.getValue("b").token)
        assertEquals(1, store.pending.size)
        controller.emergencyStop(requireNotNull(controller.session()).id)
        assertNull(controller.session())
        assertTrue(store.pending.isEmpty())
    }

    @Test fun turningAConsumedAlarmOffThenOnIntentionallyRearmsIt() {
        val plan = AlarmPlan(id = "a", days = emptySet())
        controller.sync(listOf(plan))
        controller.receiveAlarm("a", store.scheduled.single().token)
        controller.emergencyStop(requireNotNull(controller.session()).id)
        controller.sync(listOf(plan.copy(enabled = false)))
        controller.sync(listOf(plan))
        assertEquals(1, store.scheduled.size)
        assertTrue(controller.completedOneTimeIds().isEmpty())
    }

    @Test fun missingExactPermissionNeverReportsScheduled() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val results = controller.sync(listOf(AlarmPlan(id = "a")))
        assertNotNull(results.single().error)
        assertNull(results.single().scheduledAtMillis)
        assertTrue(store.scheduled.isEmpty())
    }

    @Test fun recentMissedAlarmIsRescheduledAfterBootBeforeItIsPlayed() {
        val plan = AlarmPlan(id = "a", days = emptySet())
        store.plans = listOf(plan)
        store.scheduled = listOf(ScheduledAlarm("missed-token", System.currentTimeMillis() - 60_000L, plan))
        controller.restore("boot_test")
        assertNull(controller.session())
        assertEquals("missed-token", store.scheduled.single().token)
        assertTrue(store.scheduled.single().atMillis > System.currentTimeMillis())
    }

    @Test fun moduleDisableCancelsTestsAndSessionsWithoutErasingPreferencesOrDiagnostics() {
        controller.setLanguage("sw")
        controller.testAlarm(AlarmPlan(id = "a"))
        val activeTest = store.scheduled.single()
        controller.receiveAlarm(activeTest.plan.id, activeTest.token)
        controller.testAlarm(AlarmPlan(id = "b"))
        val queuedTest = store.scheduled.single()
        controller.receiveAlarm(queuedTest.plan.id, queuedTest.token)
        controller.testAlarm(AlarmPlan(id = "c"))
        val upcomingTest = store.scheduled.single()
        controller.cancelAll()
        assertNull(controller.session())
        assertTrue(store.scheduled.isEmpty())
        assertTrue(store.pending.isEmpty())
        assertTrue(store.plans.isEmpty())
        assertEquals("sw", controller.language())
        assertTrue(controller.events().any { it.kind == "module_disabled" })
        controller.receiveAlarm(upcomingTest.plan.id, upcomingTest.token)
        controller.restore("boot_after_module_disabled")
        assertNull(controller.session())
        assertTrue(store.scheduled.isEmpty())
    }

    @Test fun deletingAnAlarmWithATestLikeIdentifierStillCancelsIt() {
        val plan = AlarmPlan(id = "test-personal", days = emptySet())
        controller.sync(listOf(plan))
        controller.receiveAlarm(plan.id, store.scheduled.single().token)
        assertFalse(requireNotNull(controller.session()).isTest)
        controller.sync(emptyList())
        assertNull(controller.session())
    }
}
