package app.mynguli.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DailyLogicTest {
    private val timestamp = 1_792_540_800_000L
    private val outcome = Outcome(id = "outcome-1", text = " Prepare the proposal ")
    private fun readyVisit() = DailyVisit(
        id = "visit-1",
        date = "2026-10-21",
        createdAtMillis = timestamp,
        updatedAtMillis = timestamp,
        arrival = " scattered ",
        note = " A note in my own words. ",
        outcomes = listOf(outcome, Outcome(id = "empty-slot", text = " ")),
        selectedOutcomeId = outcome.id,
        nextStep = " Open the outline and write the first paragraph. ",
        departure = " clearer ",
    )

    @Test fun `draft uses configured duration without inventing content`() {
        val state = AppState(settings = Settings(defaultFocusMinutes = 25))
        val draft = DailyLogic.draftForDate(state, LocalDate.of(2026, 10, 21), timestamp)
        assertEquals(25, draft.focusMinutes)
        assertEquals(timestamp, draft.createdAtMillis)
        assertEquals("", draft.arrival)
        assertEquals(emptyList<Outcome>(), draft.outcomes)
        assertNull(draft.completedAtMillis)
    }

    @Test fun `same day resumes the exact saved visit`() {
        val saved = readyVisit()
        assertSame(saved, DailyLogic.draftForDate(AppState(visits = listOf(saved)), saved.date, timestamp + 2_000))
    }

    @Test fun `completed day stays complete on reopen`() {
        val saved = DailyLogic.completeVisit(readyVisit(), timestamp + 1_000)
        val reopened = DailyLogic.draftForDate(AppState(visits = listOf(saved)), saved.date, timestamp + 2_000)
        assertSame(saved, reopened)
        assertEquals(saved.completedAtMillis, reopened.completedAtMillis)
    }

    @Test fun `new day starts blank even after yesterday was completed`() {
        val saved = DailyLogic.completeVisit(readyVisit(), timestamp + 1_000)
        val next = DailyLogic.draftForDate(AppState(visits = listOf(saved)), "2026-10-22", timestamp + 86_400_000)
        assertEquals("2026-10-22", next.date)
        assertEquals("", next.note)
        assertNull(next.selectedOutcomeId)
        assertNull(next.completedAtMillis)
    }

    @Test fun `completion trims text removes blank slots and keeps outcome unfinished`() {
        val completed = DailyLogic.completeVisit(readyVisit(), timestamp + 5_000)
        assertEquals("scattered", completed.arrival)
        assertEquals("clearer", completed.departure)
        assertEquals("A note in my own words.", completed.note)
        assertEquals(1, completed.outcomes.size)
        assertEquals("Prepare the proposal", completed.outcomes.single().text)
        assertFalse(completed.outcomes.single().done)
        assertEquals(timestamp + 5_000, completed.completedAtMillis)
    }

    @Test fun `recompleting a visit preserves the first completion timestamp`() {
        val completed = DailyLogic.completeVisit(readyVisit(), timestamp + 5_000)
        val updated = DailyLogic.completeVisit(completed, timestamp + 10_000)
        assertEquals(completed.completedAtMillis, updated.completedAtMillis)
        assertEquals(timestamp + 10_000, updated.updatedAtMillis)
    }

    @Test fun `completion can omit arrival without inventing a mood`() {
        val result = DailyLogic.completeVisit(readyVisit().copy(arrival = ""), timestamp)
        assertEquals("", result.arrival)
        assertEquals(timestamp, result.completedAtMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `completion requires a selected substantive outcome`() {
        DailyLogic.completeVisit(readyVisit().copy(selectedOutcomeId = "empty-slot"), timestamp)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `completion requires an actionable next step`() {
        DailyLogic.completeVisit(readyVisit().copy(nextStep = " "), timestamp)
    }

    @Test fun `completion can omit departure without claiming an improvement`() {
        val result = DailyLogic.completeVisit(readyVisit().copy(departure = ""), timestamp)
        assertEquals("", result.departure)
        assertEquals(timestamp, result.completedAtMillis)
    }

    @Test fun `low-energy suggestion respects a shorter personal default`() {
        assertEquals(3, DailyLogic.suggestFocusMinutes(1, 3))
        assertEquals(5, DailyLogic.suggestFocusMinutes(1, 25))
        assertEquals(10, DailyLogic.suggestFocusMinutes(2, 25))
        assertEquals(25, DailyLogic.suggestFocusMinutes(3, 25))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid energy does not produce a suggestion`() {
        DailyLogic.suggestFocusMinutes(0, 15)
    }

    @Test fun `custom arrival and departure labels remain configurable`() {
        val custom = readyVisit().copy(arrival = "my-custom-state", departure = "ready-to-rest")
        assertEquals("ready-to-rest", DailyLogic.completeVisit(custom, timestamp).departure)
    }

    @Test fun `recreated drafts share one canonical visit for the date`() {
        val original = readyVisit()
        val recreated = original.copy(id = "new-ui-draft", createdAtMillis = timestamp + 1_000, note = "Latest words")
        val updated = DailyLogic.upsertVisit(AppState(visits = listOf(original)), recreated, timestamp + 2_000)
        val saved = updated.visits.single()
        assertEquals(original.id, saved.id)
        assertEquals(original.createdAtMillis, saved.createdAtMillis)
        assertEquals("Latest words", saved.note)
        assertEquals(timestamp + 2_000, saved.updatedAtMillis)
        BackupCodec.encode(updated)
    }

    @Test fun `late autosave cannot undo explicit completion on the same date`() {
        val completed = DailyLogic.completeVisit(readyVisit(), timestamp + 1_000)
        val state = AppState(visits = listOf(completed))
        val stale = readyVisit().copy(id = "recreated-draft", note = "Older note")
        assertSame(state, DailyLogic.upsertVisit(state, stale, timestamp + 2_000))
    }

    @Test fun `second completion keeps original visit identity and completion time`() {
        val completed = DailyLogic.completeVisit(readyVisit(), timestamp + 1_000)
        val second = DailyLogic.completeVisit(readyVisit().copy(id = "another-id", nextStep = "Revise the outline"), timestamp + 3_000)
        val result = DailyLogic.upsertVisit(AppState(visits = listOf(completed)), second, timestamp + 4_000).visits.single()
        assertEquals(completed.id, result.id)
        assertEquals(completed.completedAtMillis, result.completedAtMillis)
        assertEquals("Revise the outline", result.nextStep)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `existing visit identity cannot be moved to another date`() {
        val original = readyVisit()
        DailyLogic.upsertVisit(AppState(visits = listOf(original)), original.copy(date = "2026-10-22"), timestamp)
    }

    @Test fun `saving a new day keeps previous daily records`() {
        val original = readyVisit()
        val tomorrow = original.copy(id = "tomorrow", date = "2026-10-22", note = "New day")
        val result = DailyLogic.upsertVisit(AppState(visits = listOf(original)), tomorrow, timestamp + 86_400_000)
        assertEquals(listOf(original, tomorrow.copy(updatedAtMillis = timestamp + 86_400_000)), result.visits)
    }
}
