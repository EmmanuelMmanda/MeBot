package mebot.nguli.com.core

import java.time.LocalDate

object DailyLogic {
    /** Reopening the app resumes the existing day, including a completed visit. */
    fun draftForDate(
        state: AppState,
        date: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): DailyVisit {
        StateValidation.date(date)
        StateValidation.timestamp(nowMillis)
        return state.visits.firstOrNull { it.date == date } ?: DailyVisit(
            date = date,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            focusMinutes = state.settings.defaultFocusMinutes,
        )
    }

    fun draftForDate(
        state: AppState,
        date: LocalDate,
        nowMillis: Long = System.currentTimeMillis(),
    ): DailyVisit = draftForDate(state, date.toString(), nowMillis)

    /** A transparent suggestion; callers show it as an option and never change the user's plan silently. */
    fun suggestFocusMinutes(energy: Int, defaultMinutes: Int): Int {
        require(energy in 1..5) { "Energy must be between 1 and 5." }
        StateValidation.focusMinutes(defaultMinutes)
        return when (energy) {
            1 -> minOf(defaultMinutes, 5)
            2 -> minOf(defaultMinutes, 10)
            else -> defaultMinutes
        }
    }

    fun selectedOutcome(visit: DailyVisit): Outcome? =
        visit.outcomes.firstOrNull { it.id == visit.selectedOutcomeId }

    /** One visit per local date, even when two UI drafts were created before either was persisted. */
    fun upsertVisit(
        state: AppState,
        visit: DailyVisit,
        nowMillis: Long = System.currentTimeMillis(),
    ): AppState {
        StateValidation.visit(visit)
        StateValidation.timestamp(nowMillis)
        require(state.visits.none { it.id == visit.id && it.date != visit.date }) {
            "A daily visit cannot move to another date."
        }
        val existing = state.visits.firstOrNull { it.date == visit.date }
        // A queued autosave from the final screen must not undo explicit completion.
        if (existing?.completedAtMillis != null && visit.completedAtMillis == null) return state
        require(existing != null || state.visits.size < AppLimits.VISITS) { "Daily visit limit reached." }
        val saved = visit.copy(
            id = existing?.id ?: visit.id,
            createdAtMillis = existing?.createdAtMillis ?: visit.createdAtMillis,
            updatedAtMillis = nowMillis,
            completedAtMillis = existing?.completedAtMillis ?: visit.completedAtMillis,
        )
        return state.copy(visits = state.visits.filterNot { it.date == visit.date } + saved)
    }

    /** Completion records an accepted next step. Mood is optional and is never inferred. */
    fun completeVisit(
        visit: DailyVisit,
        nowMillis: Long = System.currentTimeMillis(),
    ): DailyVisit {
        StateValidation.visit(visit)
        StateValidation.timestamp(nowMillis)
        require(selectedOutcome(visit)?.text?.isNotBlank() == true) { "Choose one outcome to begin with." }
        require(visit.nextStep.isNotBlank()) { "Write a concrete next step." }
        return visit.copy(
            arrival = visit.arrival.trim(),
            note = visit.note.trim(),
            outcomes = visit.outcomes.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() },
            nextStep = visit.nextStep.trim(),
            departure = visit.departure.trim(),
            updatedAtMillis = nowMillis,
            completedAtMillis = visit.completedAtMillis ?: nowMillis,
        )
    }
}
