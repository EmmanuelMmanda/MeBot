package mebot.nguli.com.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Typed subset of server events used by the voice UI; unrecognized events are safely ignored. */
sealed interface RealtimeServerEvent {
    data class SessionCreated(val sessionId: String) : RealtimeServerEvent
    data class UserTranscriptDelta(val itemId: String, val text: String) : RealtimeServerEvent
    data class UserTranscriptCompleted(val itemId: String, val text: String) : RealtimeServerEvent
    data class AssistantTranscriptDelta(val itemId: String, val text: String) : RealtimeServerEvent
    data class AssistantTranscriptCompleted(val itemId: String, val text: String) : RealtimeServerEvent
    data class TextDelta(val itemId: String, val text: String) : RealtimeServerEvent
    data class ResponseCompleted(val status: String) : RealtimeServerEvent
    data object UserSpeechStarted : RealtimeServerEvent
    data object UserSpeechStopped : RealtimeServerEvent
    data object ResponseCancelled : RealtimeServerEvent
    data class Error(val code: String) : RealtimeServerEvent
}

/**
 * Parses GA Realtime JSON events carried by the `oai-events` data channel. Audio bytes are
 * intentionally not handled here: WebRTC remote media tracks carry model speech audio.
 */
object RealtimeEventParser {
    private const val MAX_EVENT_CHARS = 128_000
    private val codePattern = Regex("[A-Za-z0-9_.-]{1,80}")

    fun parse(raw: String): RealtimeServerEvent? {
        if (raw.isEmpty() || raw.length > MAX_EVENT_CHARS) return null
        return runCatching { parseObject(Json.parseToJsonElement(raw).jsonObject) }.getOrNull()
    }

    private fun parseObject(event: kotlinx.serialization.json.JsonObject): RealtimeServerEvent? {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return null
        fun string(name: String, max: Int = 24_000): String? = event[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= max }
        fun id(name: String = "item_id"): String = string(name, 200).orEmpty()

        return when (type) {
            "session.created" -> {
                val session = event["session"]?.jsonObject
                RealtimeServerEvent.SessionCreated(session?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty())
            }
            "conversation.item.input_audio_transcription.delta" -> string("delta")?.let {
                RealtimeServerEvent.UserTranscriptDelta(id(), it)
            }
            "conversation.item.input_audio_transcription.completed" -> string("transcript")?.let {
                RealtimeServerEvent.UserTranscriptCompleted(id(), it)
            }
            "response.output_audio_transcript.delta" -> string("delta")?.let {
                RealtimeServerEvent.AssistantTranscriptDelta(id(), it)
            }
            "response.output_audio_transcript.done" -> string("transcript")?.let {
                RealtimeServerEvent.AssistantTranscriptCompleted(id(), it)
            }
            "response.output_text.delta" -> string("delta")?.let {
                RealtimeServerEvent.TextDelta(id(), it)
            }
            "response.output_text.done" -> string("text")?.let {
                RealtimeServerEvent.AssistantTranscriptCompleted(id(), it)
            }
            "response.done" -> {
                val response = event["response"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val status = response?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (status == "cancelled" || status == "canceled") RealtimeServerEvent.ResponseCancelled
                else RealtimeServerEvent.ResponseCompleted(status)
            }
            "response.cancelled" -> RealtimeServerEvent.ResponseCancelled
            "input_audio_buffer.speech_started" -> RealtimeServerEvent.UserSpeechStarted
            "input_audio_buffer.speech_stopped" -> RealtimeServerEvent.UserSpeechStopped
            "error", "invalid_request_error" -> {
                val nested = event["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val code = nested?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: string("code", 80)
                    ?: "provider_error"
                RealtimeServerEvent.Error(code.takeIf(codePattern::matches) ?: "provider_error")
            }
            else -> null
        }
    }
}

/** Client events supported by the Realtime GA protocol. Never sends arbitrary caller event JSON. */
object RealtimeClientEvents {
    fun cancelResponse(): String = """{"type":"response.cancel"}"""
    fun clearOutputAudio(): String = """{"type":"output_audio_buffer.clear"}"""
}
