package mebot.nguli.com.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Bounds protect imports and keep the local data file manageable; they contain no user preferences. */
object AppLimits {
    const val SCHEMA_VERSION = 1
    const val BACKUP_BYTES = 8 * 1024 * 1024
    const val VISITS = 3_650
    const val FOCUS_RECORDS = 10_000
    const val ALARMS = 32
    const val OUTCOMES = 3
    const val NOTE_CHARACTERS = 8_000
    const val OUTCOME_CHARACTERS = 300
    const val NEXT_STEP_CHARACTERS = 500
    const val MAX_FOCUS_MINUTES = 180
    const val MAX_TIMESTAMP = 253_402_300_799_999L
}

object StateValidation {
    private val modules = setOf("daily", "focus", "alarms", "history")

    fun validate(state: AppState): AppState {
        require(state.schemaVersion == AppLimits.SCHEMA_VERSION) {
            "Unsupported backup version ${state.schemaVersion}. This app supports version ${AppLimits.SCHEMA_VERSION}."
        }
        text(state.profile.name, 80, "Name")
        text(state.profile.intention, 500, "Intention")
        require(state.settings.language in setOf("en", "sw", "system")) { "Unsupported language." }
        require(state.settings.theme in setOf("system", "light", "dark")) { "Unsupported theme." }
        text(state.settings.accent, 40, "Accent", allowBlank = false)
        require(Appearance.normalizeHex(state.settings.customAccent) != null) { "Use a six-digit color such as #7251B5." }
        require(state.settings.skin in Appearance.skins) { "Unsupported appearance style." }
        require(state.settings.enabledModules.all { it in modules }) { "Backup contains an unsupported module." }
        require("daily" in state.settings.enabledModules) { "The daily module must remain available." }
        focusMinutes(state.settings.defaultFocusMinutes)
        text(state.settings.checkpointLabel, 80, "Checkpoint name")
        text(state.settings.checkpointCode, 512, "Checkpoint code")

        require(state.visits.size <= AppLimits.VISITS) { "Backup contains too many daily visits." }
        require(state.focusHistory.size <= AppLimits.FOCUS_RECORDS) { "Backup contains too many focus records." }
        require(state.alarms.size <= AppLimits.ALARMS) { "Backup contains too many alarms." }
        unique(state.visits.map { it.id }, "daily visit")
        unique(state.visits.map { it.date }, "daily date")
        unique(state.focusHistory.map { it.id }, "focus record")
        unique(state.alarms.map { it.id }, "alarm")
        state.visits.forEach(::visit)
        state.focus?.let(::focus)
        state.focusHistory.forEach { record ->
            id(record.id)
            text(record.title, AppLimits.NEXT_STEP_CHARACTERS, "Focus title", allowBlank = false)
            focusMinutes(record.minutes)
            timestamp(record.completedAtMillis)
        }
        state.alarms.forEach(::alarm)
        return state
    }

    fun visit(visit: DailyVisit) {
        id(visit.id)
        date(visit.date)
        timestamp(visit.createdAtMillis)
        timestamp(visit.updatedAtMillis)
        visit.completedAtMillis?.let(::timestamp)
        text(visit.arrival, 80, "Arrival state")
        require(visit.energy in 1..5) { "Energy must be between 1 and 5." }
        text(visit.note, AppLimits.NOTE_CHARACTERS, "Notes")
        require(visit.outcomes.size <= AppLimits.OUTCOMES) { "Choose at most ${AppLimits.OUTCOMES} outcomes." }
        unique(visit.outcomes.map { it.id }, "outcome")
        visit.outcomes.forEach {
            id(it.id)
            text(it.text, AppLimits.OUTCOME_CHARACTERS, "Outcome")
        }
        require(visit.selectedOutcomeId == null || visit.outcomes.any { it.id == visit.selectedOutcomeId }) {
            "The selected outcome is missing."
        }
        text(visit.nextStep, AppLimits.NEXT_STEP_CHARACTERS, "Next step")
        focusMinutes(visit.focusMinutes)
        text(visit.departure, 80, "Departure state")
        if (visit.completedAtMillis != null) {
            require(visit.outcomes.any { it.id == visit.selectedOutcomeId && it.text.isNotBlank() }) {
                "A completed visit needs a selected outcome."
            }
            require(visit.nextStep.isNotBlank()) { "A completed visit needs a next step." }
        }
    }

    fun focus(session: FocusSession) {
        id(session.id)
        text(session.title, AppLimits.NEXT_STEP_CHARACTERS, "Focus title", allowBlank = false)
        focusMinutes(session.durationMinutes)
        timestamp(session.startedAtMillis)
        timestamp(session.endsAtMillis)
        session.pausedRemainingMillis?.let {
            require(it in 0..session.durationMinutes * 60_000L) { "Invalid remaining focus duration." }
        }
    }

    fun alarm(alarm: AlarmPlan) {
        id(alarm.id)
        AlarmAudioSettings.validate(alarm)
        text(alarm.label, 100, "Alarm label")
        require(alarm.hour in 0..23 && alarm.minute in 0..59) { "Alarm time is invalid." }
        require(alarm.days.all { it in 1..7 }) { "Alarm weekdays must be between 1 and 7." }
        require(alarm.snoozeMinutes in 1..30) { "Snooze must be between 1 and 30 minutes." }
        require(alarm.maxSnoozes in 0..5) { "Snooze allowance must be between 0 and 5." }
        require(alarm.followUpMinutes in 0..30) { "Follow-up must be between 0 and 30 minutes." }
        require(alarm.volumePercent in 1..100) { "Alarm volume must be between 1 and 100." }
        require(alarm.rampSeconds in 0..120) { "Volume ramp must be between 0 and 120 seconds." }
        require(alarm.mathQuestionCount in 1..5) { "Math challenges need between 1 and 5 questions." }
        text(alarm.checkpointCode, 512, "Checkpoint code")
        text(alarm.checkpointLabel, 80, "Checkpoint name")
        require(alarm.effectiveDismissMode != AlarmDismissMode.QR || alarm.checkpointCode.isNotBlank()) {
            "Set a checkpoint before requiring its scan."
        }
    }

    fun date(value: String): LocalDate {
        require(value.length == 10) { "Daily dates must use yyyy-MM-dd." }
        val result = try {
            LocalDate.parse(value)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Daily date is invalid.", error)
        }
        require(result.year in 1970..9999 && result.toString() == value) { "Daily date is out of range." }
        return result
    }

    fun focusMinutes(value: Int) {
        require(value in 1..AppLimits.MAX_FOCUS_MINUTES) {
            "Focus duration must be between 1 and ${AppLimits.MAX_FOCUS_MINUTES} minutes."
        }
    }

    fun timestamp(value: Long) {
        require(value in 0..AppLimits.MAX_TIMESTAMP) { "A saved timestamp is out of range." }
    }

    private fun text(value: String, maximum: Int, field: String, allowBlank: Boolean = true) {
        require(value.length <= maximum) { "$field is too long (maximum $maximum characters)." }
        require(allowBlank || value.isNotBlank()) { "$field cannot be empty." }
        require('\u0000' !in value) { "$field contains an invalid character." }
    }

    private fun id(value: String) = text(value, 128, "Record identifier", allowBlank = false)

    private fun unique(values: List<String>, kind: String) {
        require(values.toSet().size == values.size) { "Backup contains a duplicate $kind." }
    }
}
