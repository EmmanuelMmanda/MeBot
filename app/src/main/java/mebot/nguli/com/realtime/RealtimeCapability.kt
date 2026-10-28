package mebot.nguli.com.realtime

/**
 * Whether a two-way Realtime call may be offered independently of any text/ChatGPT connection.
 * This describes configuration/access only; it is not evidence that an active call is connected.
 * User-facing wording is supplied by localized UI resources from [Unavailable.reason].
 */
sealed interface RealtimeCapability {
    val canOfferCall: Boolean

    data object Available : RealtimeCapability {
        override val canOfferCall: Boolean = true
    }

    data class Unavailable(val reason: Reason) : RealtimeCapability {
        override val canOfferCall: Boolean = false
    }

    enum class Reason {
        SERVICE_NOT_CONFIGURED,
        SERVICE_TOKEN_MISSING,
        REALTIME_NOT_ENABLED,
        API_ACCESS_NOT_CONFIRMED,
        API_ACCESS_UNAVAILABLE,
        TRANSPORT_NOT_AVAILABLE,
        DAILY_LIMIT_REACHED,
        ACTIVE_CALL,
        TEMPORARY_ERROR,
    }
}

/** Explicit recorded-audio upload availability, separate from live Realtime calls. */
sealed interface RecordingTranscriptionCapability {
    val canTranscribe: Boolean

    data object Available : RecordingTranscriptionCapability {
        override val canTranscribe: Boolean = true
    }

    data class Unavailable(val reason: Reason) : RecordingTranscriptionCapability {
        override val canTranscribe: Boolean = false
    }

    enum class Reason {
        SERVICE_NOT_CONFIGURED,
        SERVICE_TOKEN_MISSING,
        TRANSCRIPTION_NOT_CONFIGURED,
        API_ACCESS_UNAVAILABLE,
        DAILY_LIMIT_REACHED,
        TEMPORARY_ERROR,
    }
}
