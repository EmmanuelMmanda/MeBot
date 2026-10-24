package app.mynguli.core

import java.net.URI

/** Sound selection is portable data. An unavailable external URI always has a bundled fallback. */
object AlarmAudioSettings {
    const val LEGACY_SOUND = "classic"
    const val DEFAULT_SOUND = "pulse"
    const val MAX_MESSAGE = 240
    val bundled = listOf("pulse", "chime", "bell", "classic")
    val sounds = bundled.toSet() + setOf("system", "custom")

    fun isContentUri(value: String): Boolean = value.length in 1..2048 &&
        value.none { it.isISOControl() } && runCatching {
            val uri = URI(value)
            uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null
        }.getOrDefault(false)

    fun validate(plan: AlarmPlan) {
        require(plan.soundId in sounds) { "Unknown alarm sound." }
        require(plan.soundName.length <= 160 && plan.soundName.none { it.isISOControl() }) { "Invalid sound name." }
        require(plan.soundUri.isEmpty() || isContentUri(plan.soundUri)) { "Choose a content audio URI." }
        require(plan.soundId != "custom" || plan.soundUri.isNotBlank()) { "Choose an audio file." }
        require(plan.soundId in setOf("system", "custom") || plan.soundUri.isEmpty()) { "Bundled audio has no external URI." }
        require(plan.speechMessage.length <= MAX_MESSAGE && plan.speechMessage.none { it.isISOControl() && it != '\n' }) {
            "Wake-up message is too long or contains unsupported characters."
        }
    }
}
