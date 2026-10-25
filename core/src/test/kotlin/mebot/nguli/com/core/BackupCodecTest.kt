package mebot.nguli.com.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val instant = 1_792_540_800_000L
    private val visit = DailyVisit(
        id = "visit-1",
        date = "2026-10-21",
        createdAtMillis = instant,
        updatedAtMillis = instant,
        arrival = "steady",
        note = "Nianze kwa utulivu.\nA personal note: café 🌅",
    )

    @Test fun `all fields survive a multilingual portable round trip`() {
        val outcome = Outcome(id = "outcome-1", text = "Andaa mpango")
        val state = AppState(
            profile = Profile(name = "Asha", intention = "Muda wa kutulia", onboardingComplete = true),
            settings = Settings(language = "sw", theme = "dark", checkpointLabel = "Jikoni", checkpointCode = "code-1"),
            visits = listOf(visit.copy(outcomes = listOf(outcome), selectedOutcomeId = outcome.id)),
            focus = FocusLogic.start("Andika aya moja", 10, instant),
            focusHistory = listOf(FocusRecord("record-1", "Read", 15, instant)),
            alarms = listOf(AlarmPlan(id = "alarm-1", requireCheckpoint = true, checkpointCode = "code-1")),
        )
        assertEquals(state, BackupCodec.decode(BackupCodec.encode(state)))
    }

    @Test fun `fresh defaults contain no personal profile or enabled alarm`() {
        val fresh = BackupCodec.decode(BackupCodec.encode(AppState()))
        assertEquals("", fresh.profile.name)
        assertTrue(fresh.alarms.isEmpty())
        assertTrue(fresh.visits.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `future schema is rejected instead of partially importing`() {
        BackupCodec.decode("""{"schemaVersion":2}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unversioned JSON is not mistaken for a backup`() {
        BackupCodec.decode("""{"profile":{"name":"Someone"}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid JSON is rejected`() {
        BackupCodec.decode("{\"schemaVersion\":1,")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown fields are rejected to avoid silent data loss`() {
        BackupCodec.decode("""{"schemaVersion":1,"newSecretField":"value"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-object content is rejected`() {
        BackupCodec.decode("[]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `excessive input is rejected before parsing`() {
        BackupCodec.decode(" ".repeat(AppLimits.BACKUP_BYTES + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid imported energy is rejected`() {
        val json = BackupCodec.encode(AppState(visits = listOf(visit)))
        BackupCodec.decode(json.replace("\"energy\": 3", "\"energy\": 9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate day entries are rejected`() {
        BackupCodec.encode(AppState(visits = listOf(visit, visit.copy(id = "visit-2"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate alarm identifiers are rejected`() {
        BackupCodec.encode(AppState(alarms = listOf(AlarmPlan(id = "same"), AlarmPlan(id = "same"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized note is rejected`() {
        BackupCodec.encode(AppState(visits = listOf(visit.copy(note = "x".repeat(AppLimits.NOTE_CHARACTERS + 1)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing selected outcome is rejected`() {
        BackupCodec.encode(AppState(visits = listOf(visit.copy(selectedOutcomeId = "missing"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative timestamp is rejected`() {
        BackupCodec.encode(AppState(visits = listOf(visit.copy(createdAtMillis = -1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `impossible calendar date is rejected`() {
        BackupCodec.encode(AppState(visits = listOf(visit.copy(date = "2026-02-30"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mandatory QR cannot be imported without a code`() {
        BackupCodec.encode(AppState(alarms = listOf(AlarmPlan(requireCheckpoint = true))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `completed visit cannot be fabricated without its required choices`() {
        BackupCodec.encode(AppState(visits = listOf(visit.copy(completedAtMillis = instant))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid paused duration is rejected`() {
        val focus = FocusLogic.start("Read", 5, instant).copy(pausedRemainingMillis = 300_001)
        BackupCodec.encode(AppState(focus = focus))
    }

    @Test fun `completed quick plan round trips without inferred emotions`() {
        val outcome = Outcome(id = "quick-outcome", text = "Take a short break")
        val completed = DailyLogic.completeVisit(visit.copy(
            arrival = "", departure = "", outcomes = listOf(outcome),
            selectedOutcomeId = outcome.id, nextStep = outcome.text,
        ), instant)
        val original = AppState(visits = listOf(completed))
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, restored)
        assertEquals("", restored.visits.single().arrival)
        assertEquals("", restored.visits.single().departure)
    }

    @Test fun `failed candidate import leaves original immutable state untouched`() {
        val original = AppState(profile = Profile(name = "Keep me"), visits = listOf(visit))
        val before = BackupCodec.encode(original)
        try {
            BackupCodec.decode("""{"schemaVersion":1,"settings":{"defaultFocusMinutes":0}}""")
            throw AssertionError("Invalid backup should fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, BackupCodec.encode(original))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `silent alarm volume is rejected before import can alter schedules`() {
        BackupCodec.decode("""{"schemaVersion":1,"alarms":[{"id":"silent","volumePercent":0}]}""")
    }

    @Test fun `minimum and maximum audible alarm volume round trip`() {
        val state = AppState(alarms = listOf(
            AlarmPlan(id = "quiet", volumePercent = 1),
            AlarmPlan(id = "loud", volumePercent = 100),
        ))
        assertEquals(state, BackupCodec.decode(BackupCodec.encode(state)))
    }
}
