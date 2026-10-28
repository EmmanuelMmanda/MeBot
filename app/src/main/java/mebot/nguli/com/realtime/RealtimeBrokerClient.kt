package mebot.nguli.com.realtime

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import mebot.nguli.com.core.FeedLogic

/** Authenticated client for the trusted service's server-owned Realtime call broker. */
class RealtimeBrokerClient(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    suspend fun getCapability(config: RealtimeBrokerConfig?): RealtimeServerCapability {
        if (config == null) return RealtimeServerCapability(
            RealtimeCapability.Unavailable(RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED),
            RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED),
        )
        return try {
            val root = request("${config.serviceUrl}/v1/realtime/capability", "GET", config.serviceToken()).jsonObject
            RealtimeServerCapability(
                parseVoiceCapability(root["realtimeVoice"]?.jsonObject),
                parseTranscriptionCapability(root["recordingTranscription"]?.jsonObject),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RealtimeTransportException) {
            RealtimeServerCapability(
                RealtimeCapability.Unavailable(mapVoiceReason(error.reasonCode)),
                RecordingTranscriptionCapability.Unavailable(mapTranscriptionReason(error.reasonCode)),
            )
        } catch (_: Exception) {
            RealtimeServerCapability(
                RealtimeCapability.Unavailable(RealtimeCapability.Reason.TEMPORARY_ERROR),
                RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.TEMPORARY_ERROR),
            )
        }
    }

    /** Sends the one final, fully ICE-gathered SDP offer to the service—not directly to OpenAI. */
    suspend fun createRealtimeCall(
        config: RealtimeBrokerConfig,
        requestId: String,
        context: RealtimeSessionContext,
        finalIceCompleteOffer: String,
    ): RealtimeCallHandshake {
        if (!isRequestId(requestId)) throw RealtimeTransportException("invalid_input")
        if (finalIceCompleteOffer.length !in 1..MAX_SDP_CHARACTERS ||
            !finalIceCompleteOffer.startsWith("v=0") || !finalIceCompleteOffer.contains("m=audio")) {
            throw RealtimeTransportException("invalid_sdp")
        }
        val body = encodeCallRequest(requestId, context, finalIceCompleteOffer).toString()
        if (body.length > MAX_CALL_REQUEST_CHARACTERS) throw RealtimeTransportException("invalid_context")
        val root = request(
            "${config.serviceUrl}/v1/realtime/calls", "POST", config.serviceToken(), body, "application/json",
        ).jsonObject
        val answer = root["answerSdp"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length in 1..MAX_SDP_CHARACTERS && it.startsWith("v=0") && it.contains("m=audio") }
            ?: throw RealtimeTransportException("invalid_response")
        val reference = root["callReference"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it == requestId && isRequestId(it) }
            ?: throw RealtimeTransportException("invalid_response")
        return RealtimeCallHandshake(answer, RealtimeCallReference(reference))
    }

    /** Idempotent server-side hang-up; local track shutdown never depends on this request succeeding. */
    suspend fun endRealtimeCall(config: RealtimeBrokerConfig, reference: RealtimeCallReference) {
        if (!isRequestId(reference.value)) throw RealtimeTransportException("invalid_input")
        request(
            "${config.serviceUrl}/v1/realtime/calls/end", "POST", config.serviceToken(),
            buildJsonObject { put("callReference", reference.value) }.toString(), "application/json",
        )
    }

    /** Explicit upload of a local M4A recording; the returned text stays editable. */
    suspend fun transcribeRecording(config: RealtimeBrokerConfig, m4a: ByteArray): String {
        if (m4a.isEmpty() || m4a.size > MAX_AUDIO_BYTES) throw RealtimeTransportException("invalid_audio")
        val response = requestBytes(
            "${config.serviceUrl}/v1/transcriptions", "POST", config.serviceToken(), m4a, "audio/mp4",
        )
        return response.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TRANSCRIPT_CHARACTERS }
            ?: throw RealtimeTransportException("invalid_response")
    }

    private fun encodeCallRequest(requestId: String, context: RealtimeSessionContext, sdp: String): JsonObject {
        validateContext(context)
        val item = context.selectedItem
        val kind = item?.moduleKind ?: context.moduleKind
        val moduleId = item?.moduleId ?: context.moduleId
        val filteredContext = context.confirmedContext.asSequence()
            .filter { it.confirmed }
            .filter { note ->
                if (note.global) note.id == ABOUT_YOU_GLOBAL_ID && note.category == "profile" && note.scopes.isEmpty()
                else !note.global && ((kind.isNotBlank() && kind in note.scopes) || (moduleId.isNotBlank() && moduleId in note.scopes))
            }
            .distinctBy { it.id }
            .take(MAX_CONTEXT_ENTRIES)
            .toList()
        val history = context.recentHistory.takeLast(MAX_HISTORY_TURNS)
        if (history.any { it.role !in setOf("user", "assistant") || it.text.isBlank() || it.text.length > MAX_HISTORY_CHARS })
            throw RealtimeTransportException("invalid_context")
        if (history.sumOf { it.text.length } > MAX_TOTAL_HISTORY_CHARS) throw RealtimeTransportException("invalid_context")
        val itemJson = item?.let {
            buildJsonObject {
                put("moduleKind", it.moduleKind)
                put("moduleId", it.moduleId)
                put("title", it.title.take(300))
                put("summary", it.summary.take(5000))
                put("contentType", it.contentType)
                put("acquisition", it.acquisition)
                put("authorship", it.authorship)
                put("sourceName", it.sourceName.take(200))
                put("sourceUrl", it.sourceUrl.take(2048))
                put("publishedAtMillis", it.publishedAtMillis.coerceAtLeast(0))
                put("sections", buildJsonArray {
                    it.sections.take(4).forEach { section ->
                        add(buildJsonObject { put("heading", section.heading.take(120)); put("body", section.body.take(6000)) })
                    }
                })
                put("citationReferences", encodeCitations(it.citationReferences, max = 10))
                put("furtherReading", encodeCitations(it.furtherReading, max = 5))
            }
        } ?: JsonNull
        return buildJsonObject {
            put("requestId", requestId)
            put("sdp", sdp)
            put("moduleKind", kind.take(40))
            put("moduleId", moduleId.take(100))
            put("moduleTitle", context.moduleTitle.take(80))
            put("moduleQuery", context.moduleQuery.take(1000))
            put("language", if (context.language == "sw") "sw" else "en")
            put("context", buildJsonArray {
                filteredContext.forEach { note ->
                    add(buildJsonObject {
                        put("id", note.id.take(100))
                        put("text", note.text.take(MAX_CONTEXT_TEXT_CHARS))
                        put("category", note.category.take(50))
                        put("scopes", JsonArray(note.scopes.take(32).map(::JsonPrimitive)))
                        put("confirmed", true)
                        put("global", note.global)
                        put("updatedAtMillis", note.updatedAtMillis.coerceAtLeast(0))
                    })
                }
            })
            put("item", itemJson)
            put("history", buildJsonArray {
                history.forEach { turn -> add(buildJsonObject { put("role", turn.role); put("text", turn.text) }) }
            })
        }
    }

    private fun validateContext(context: RealtimeSessionContext) {
        if (context.moduleKind.length > 40 || context.moduleId.length > 100 ||
            context.moduleTitle.length > 80 || context.moduleQuery.length > 1000 ||
            context.language !in setOf("en", "sw")) throw RealtimeTransportException("invalid_context")
        val item = context.selectedItem ?: return
        if ((context.moduleKind.isNotBlank() && context.moduleKind != item.moduleKind) ||
            (context.moduleId.isNotBlank() && context.moduleId != item.moduleId)) throw RealtimeTransportException("invalid_context")
        if (item.moduleId.isBlank() || item.moduleId.length > 100 || item.title.length > 300 || item.summary.length > 5000 ||
            item.publishedAtMillis < 0 || item.citationReferences.size > 10 || item.furtherReading.size > 5 || item.sections.size > 4)
            throw RealtimeTransportException("invalid_context")
        val isOriginal = item.contentType == "original_read"
        if (isOriginal) {
            val sectionLengths = item.sections.map { it.body.length }
            if (item.acquisition != "original_read" || item.authorship != "ai" || item.sourceName.isNotEmpty() ||
                item.sourceUrl.isNotEmpty() || item.publishedAtMillis != 0L || sectionLengths.size !in 2..4 ||
                sectionLengths.any { it !in 180..6000 } || sectionLengths.sum() !in 1000..12000 ||
                item.sections.any { it.heading.isBlank() || it.heading.length > 120 }) throw RealtimeTransportException("invalid_context")
        } else {
            if (item.sourceUrl.isBlank() || !FeedLogic.safeUrl(item.sourceUrl, httpsOnly = true) ||
                item.sourceName.length > 200 || item.sections.isNotEmpty()) throw RealtimeTransportException("invalid_context")
        }
        if ((item.citationReferences + item.furtherReading).any { it.url.length > 2048 || !FeedLogic.safeUrl(it.url, httpsOnly = true) || it.title.length > 300 })
            throw RealtimeTransportException("invalid_context")
    }

    private fun encodeCitations(rows: List<RealtimeCitation>, max: Int) = buildJsonArray {
        rows.take(max).forEach { row -> add(buildJsonObject { put("title", row.title.take(300)); put("url", row.url.take(2048)) }) }
    }

    private suspend fun request(
        url: String, method: String, token: String, body: String? = null, contentType: String? = null,
    ): ParsedResponse {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return performCancellable(url, method, token, bytes, contentType)
    }

    private suspend fun requestBytes(url: String, method: String, token: String, bytes: ByteArray, contentType: String): ParsedResponse =
        performCancellable(url, method, token, bytes, contentType)

    /** Disconnect the in-flight HttpURLConnection on coroutine cancellation; never turn cancel into success. */
    private suspend fun performCancellable(
        url: String, method: String, token: String, bytes: ByteArray?, contentType: String?,
    ): ParsedResponse = withContext(Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpURLConnection?>(null)
            val worker = launch {
                try {
                    val response = perform(url, method, token, bytes, contentType) { connection ->
                        activeConnection.set(connection)
                        if (!continuation.isActive) {
                            activeConnection.getAndSet(null)?.disconnect()
                            throw CancellationException("Realtime request cancelled")
                        }
                    }
                    if (continuation.isActive) continuation.resume(response)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    activeConnection.getAndSet(null)?.disconnect()
                }
            }
            continuation.invokeOnCancellation {
                activeConnection.getAndSet(null)?.disconnect()
                worker.cancel()
            }
        }
    }

    private fun perform(
        url: String, method: String, token: String, bytes: ByteArray?, contentType: String?,
        onConnection: (HttpURLConnection) -> Unit,
    ): ParsedResponse {
        val connection = try {
            openConnection(URL(url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw RealtimeTransportException("temporary_error")
        }
        onConnection(connection)
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            if (bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val source = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = source?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw RealtimeTransportException("invalid_response")
                    output.write(buffer, 0, read)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }.orEmpty()
            if (status !in 200..299) throw RealtimeTransportException(errorCode(status, response))
            ParsedResponse(jsonObject = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrElse {
                throw RealtimeTransportException("invalid_response")
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RealtimeTransportException) {
            throw error
        } catch (_: Exception) {
            throw RealtimeTransportException("temporary_error")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVoiceCapability(value: JsonObject?): RealtimeCapability {
        if (value?.get("available")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true) return RealtimeCapability.Available
        val reason = value?.get("reason")?.jsonPrimitive?.contentOrNull ?: "temporary_error"
        return RealtimeCapability.Unavailable(mapVoiceReason(reason))
    }

    private fun parseTranscriptionCapability(value: JsonObject?): RecordingTranscriptionCapability {
        if (value?.get("available")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true) return RecordingTranscriptionCapability.Available
        val reason = value?.get("reason")?.jsonPrimitive?.contentOrNull ?: "temporary_error"
        return RecordingTranscriptionCapability.Unavailable(mapTranscriptionReason(reason))
    }

    private fun mapVoiceReason(value: String): RealtimeCapability.Reason = when (value) {
        "realtime_not_enabled", "realtime_unavailable" -> RealtimeCapability.Reason.REALTIME_NOT_ENABLED
        "unauthorized", "service_token_missing" -> RealtimeCapability.Reason.SERVICE_TOKEN_MISSING
        "service_not_configured" -> RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED
        "daily_limit_reached", "limit_reached" -> RealtimeCapability.Reason.DAILY_LIMIT_REACHED
        "active_call_limit_reached" -> RealtimeCapability.Reason.ACTIVE_CALL
        "api_access_unavailable", "provider_error" -> RealtimeCapability.Reason.API_ACCESS_UNAVAILABLE
        "transport_not_available" -> RealtimeCapability.Reason.TRANSPORT_NOT_AVAILABLE
        else -> RealtimeCapability.Reason.TEMPORARY_ERROR
    }

    private fun mapTranscriptionReason(value: String): RecordingTranscriptionCapability.Reason = when (value) {
        "transcription_not_enabled", "transcription_unavailable" -> RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED
        "unauthorized", "service_token_missing" -> RecordingTranscriptionCapability.Reason.SERVICE_TOKEN_MISSING
        "service_not_configured" -> RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED
        "daily_limit_reached", "limit_reached" -> RecordingTranscriptionCapability.Reason.DAILY_LIMIT_REACHED
        "api_access_unavailable", "provider_error" -> RecordingTranscriptionCapability.Reason.API_ACCESS_UNAVAILABLE
        else -> RecordingTranscriptionCapability.Reason.TEMPORARY_ERROR
    }

    private fun errorCode(status: Int, body: String): String {
        val code = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (code in setOf("realtime_unavailable", "transcription_unavailable", "unauthorized", "invalid_input", "invalid_response", "active_call_limit_reached", "call_request_already_used", "call_request_id_conflict")) return code!!
        if (code == "limit_reached") return "daily_limit_reached"
        if (code == "rate_limit") return "api_access_unavailable"
        return when (status) {
            401, 403 -> "unauthorized"
            429 -> "daily_limit_reached"
            in 400..499 -> "invalid_input"
            else -> "temporary_error"
        }
    }

    private fun isRequestId(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) { false }

    private data class ParsedResponse(val jsonObject: JsonObject = JsonObject(emptyMap()))

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_SDP_CHARACTERS = 512_000
        const val MAX_CALL_REQUEST_CHARACTERS = 1_000_000
        const val MAX_AUDIO_BYTES = 2 * 1024 * 1024
        const val MAX_TRANSCRIPT_CHARACTERS = 24_000
        const val MAX_CONTEXT_ENTRIES = 32
        const val MAX_CONTEXT_TEXT_CHARS = 1500
        const val MAX_HISTORY_TURNS = 8
        const val MAX_HISTORY_CHARS = 2000
        const val MAX_TOTAL_HISTORY_CHARS = 8000
        const val ABOUT_YOU_GLOBAL_ID = "about-you-global"
    }
}
