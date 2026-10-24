package app.mynguli.core

import org.junit.Assert.*
import org.junit.Test

class AlarmAudioSettingsTest {
    @Test fun oldBackupRetainsItsSoundAndDoesNotEnableSpeech() {
        val decoded = BackupCodec.decode("""{"schemaVersion":1,"alarms":[{"id":"existing","volumePercent":60,"rampSeconds":30}]}""")
        val plan = decoded.alarms.single()
        assertEquals("classic", plan.soundId)
        assertEquals("", plan.soundUri)
        assertFalse(plan.speechEnabled)
        assertEquals("", plan.speechMessage)
        assertEquals(60, plan.volumePercent)
        assertEquals(30, plan.rampSeconds)
    }

    @Test fun selectedAudioAndOptionalMessageSurvivePortableBackup() {
        val plan = AlarmPlan(id = "audio", soundId = "custom", soundUri = "content://documents/audio%3A123",
            soundName = "Morning bells.wav", speechEnabled = true, speechMessage = "Time to get up.\nOpen the curtains.")
        val state = AppState(alarms = listOf(plan))
        assertEquals(state, BackupCodec.decode(BackupCodec.encode(state)))
        AlarmAudioSettings.bundled.forEach { StateValidation.alarm(plan.copy(soundId = it, soundUri = "", soundName = "")) }
        StateValidation.alarm(plan.copy(soundId = "system", soundUri = "", soundName = ""))
    }

    @Test fun unsupportedUrisAndIncompleteSelectionsCannotEnterImportedPlans() {
        listOf("https://example.com/alarm.mp3", "file:///sdcard/alarm.wav", "content://", "content://user@provider/file",
            "content://provider:80/file", "content://provider/file#fragment", "content://provider/\nfile").forEach { uri ->
            assertFalse(uri, AlarmAudioSettings.isContentUri(uri))
            rejected(AlarmPlan(soundId = "custom", soundUri = uri))
        }
        rejected(AlarmPlan(soundId = "custom"))
        rejected(AlarmPlan(soundId = "missing"))
        rejected(AlarmPlan(soundId = "pulse", soundUri = "content://provider/audio"))
        rejected(AlarmPlan(speechMessage = "x".repeat(241)))
        rejected(AlarmPlan(speechMessage = "Hidden\u0000message"))
        rejected(AlarmPlan(soundName = "x".repeat(161)))
        StateValidation.alarm(AlarmPlan(speechEnabled = true, speechMessage = ""))
    }

    private fun rejected(plan: AlarmPlan) {
        assertThrows(IllegalArgumentException::class.java) { StateValidation.alarm(plan) }
    }
}
