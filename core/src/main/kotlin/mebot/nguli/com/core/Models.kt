package mebot.nguli.com.core

import java.util.UUID
import kotlinx.serialization.Serializable

/** Versioned, portable user data. Defaults contain no personal identity or scheduled alarm. */
@Serializable
data class AppState(
    val schemaVersion: Int = 1,
    val profile: Profile = Profile(),
    val settings: Settings = Settings(),
    val visits: List<DailyVisit> = emptyList(),
    val focus: FocusSession? = null,
    val focusHistory: List<FocusRecord> = emptyList(),
    val alarms: List<AlarmPlan> = emptyList(),
)

@Serializable
data class Profile(
    val name: String = "",
    val intention: String = "",
    val onboardingComplete: Boolean = false,
)

@Serializable
data class Settings(
    val language: String = "en",
    val theme: String = "system",
    val accent: String = "ember",
    val customAccent: String = "#7251B5",
    val skin: String = "classic",
    val enabledModules: Set<String> = setOf("daily", "focus", "alarms", "history"),
    val defaultFocusMinutes: Int = 15,
    val checkpointLabel: String = "",
    val checkpointCode: String = "",
)

@Serializable
data class DailyVisit(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val arrival: String = "",
    val energy: Int = 3,
    val note: String = "",
    val outcomes: List<Outcome> = emptyList(),
    val selectedOutcomeId: String? = null,
    val nextStep: String = "",
    val focusMinutes: Int = 15,
    val departure: String = "",
    val completedAtMillis: Long? = null,
)

@Serializable
data class Outcome(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val done: Boolean = false,
)

@Serializable
data class FocusSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val durationMinutes: Int,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    val pausedRemainingMillis: Long? = null,
)

@Serializable
data class FocusRecord(
    val id: String,
    val title: String,
    val minutes: Int,
    val completedAtMillis: Long,
)

/** ISO weekday numbers: Monday = 1 ... Sunday = 7. Empty days means the next occurrence only. */
@Serializable
data class AlarmPlan(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val enabled: Boolean = true,
    val snoozeMinutes: Int = 3,
    val maxSnoozes: Int = 1,
    val requireCheckpoint: Boolean = false,
    val checkpointCode: String = "",
    val checkpointLabel: String = "",
    val followUpMinutes: Int = 3,
    val volumePercent: Int = 80,
    val rampSeconds: Int = 20,
    val vibrate: Boolean = true,
    // STANDARD preserves pre-MeBot backups. New alarm creation chooses MATH explicitly.
    val dismissMode: AlarmDismissMode = AlarmDismissMode.STANDARD,
    val mathDifficulty: MathDifficulty = MathDifficulty.EASY,
    val mathQuestionCount: Int = 3,
    // Missing fields retain the sound and silent-voice behavior of older saved alarms.
    val soundId: String = AlarmAudioSettings.LEGACY_SOUND,
    val soundUri: String = "",
    val soundName: String = "",
    val speechEnabled: Boolean = false,
    val speechMessage: String = "",
)

@Serializable
enum class AlarmDismissMode { STANDARD, MATH, QR }

/** Legacy checkpoint alarms retain their behavior when loaded from a version-one backup. */
val AlarmPlan.effectiveDismissMode: AlarmDismissMode
    get() = when {
        dismissMode == AlarmDismissMode.MATH -> AlarmDismissMode.MATH
        dismissMode == AlarmDismissMode.QR || requireCheckpoint -> AlarmDismissMode.QR
        else -> AlarmDismissMode.STANDARD
    }
