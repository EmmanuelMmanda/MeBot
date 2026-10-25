package mebot.nguli.com.core

import kotlinx.serialization.Serializable

/** Presentation-only preferences. They never grant notification permission or provider access. */
@Serializable
data class ReadingPreferences(
    val autoLoadMore: Boolean = true,
    val showActivityDetails: Boolean = true,
    val notificationImages: Boolean = true,
    val notificationPreview: Boolean = true,
)
