package app.mynguli.feed

import app.mynguli.BuildConfig
import app.mynguli.feed.auth.ChatGptDiagnostic
import app.mynguli.feed.auth.safeRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only the documented public Responses endpoint is used for subscription inference. */
interface ChatGptResponses {
    suspend fun complete(body: JsonObject, accessToken: String): JsonObject
    suspend fun stream(body: JsonObject, accessToken: String, onText: (String) -> Unit): JsonObject {
        val response = complete(body, accessToken)
        if ((response["status"] as? JsonPrimitive)?.contentOrNull != "completed") throw FeedFailure("invalid_response")
        onText(responseOutputText(response) ?: throw FeedFailure("invalid_response"))
        return response
    }
}

/** Reasoning and tool items may have null content; they are not assistant answers. */
internal fun responseOutputText(response: JsonObject): String? = (response["output"] as? JsonArray).orEmpty()
    .mapNotNull { it as? JsonObject }
    .filter {
        (it["type"] as? JsonPrimitive)?.contentOrNull == "message" &&
            (it["role"] as? JsonPrimitive)?.contentOrNull.let { role -> role == null || role == "assistant" } &&
            (it["status"] as? JsonPrimitive)?.contentOrNull.let { status -> status == null || status == "completed" }
    }
    .flatMap { (it["content"] as? JsonArray).orEmpty() }
    .mapNotNull { it as? JsonObject }
    .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "output_text" }
    .joinToString("\n") { (it["text"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull.orEmpty() }
    .takeIf { it.isNotBlank() }

object ChatGptRequest {
    fun build(model: String, instructions: String, input: String, search: Boolean, schema: JsonObject? = null): JsonObject {
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")))
        return buildJsonObject {
            put("model", model)
            put("store", false)
            put("stream", true)
            // Do not assume API-key capabilities (including JSON schema mode) on this route.
            put("instructions", instructions + if (schema != null) "\nReturn only a JSON object matching this schema, without markdown fences:\n$schema" else "")
            put("input", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", input) })
            })
            if (search) {
                put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
                put("include", buildJsonArray { add("web_search_call.action.sources") })
                put("tool_choice", if (schema != null) "required" else "auto")
            }
        }
    }
}

/** No provider message is displayed verbatim: it can contain request data or secrets. */
object ChatGptErrors {
    private val diagnosticCodes = setOf(
        "subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable",
        "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported",
        "subscription_sharing_invalid_user", "invalid_token", "token_expired",
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context",
        "rate_limit_exceeded", "invalid_api_key", "insufficient_quota", "model_not_found",
        "invalid_request_error", "server_error", "authentication_error", "permission_denied",
    )
    private val diagnosticParams = setOf(
        "model", "input", "instructions", "tools", "tool_choice", "stream", "store", "include",
        "max_output_tokens", "max_tool_calls", "text", "reasoning", "service_tier", "background",
        "previous_response_id", "conversation", "prompt", "temperature", "top_p",
    )

    fun diagnosticCode(raw: String?): String? = raw?.takeIf { it in diagnosticCodes }
    fun diagnosticParam(raw: String?): String? = raw?.substringBefore('.')?.substringBefore('[')
        ?.takeIf { it in diagnosticParams }

    fun diagnostic(error: JsonObject?, base: ChatGptDiagnostic): ChatGptDiagnostic = base.copy(
        providerCode = diagnosticCode((error?.get("code") as? JsonPrimitive)?.contentOrNull),
        providerParam = diagnosticParam((error?.get("param") as? JsonPrimitive)?.contentOrNull),
    )

    fun code(raw: String?): String = when (raw) {
        "subscription_sharing_user_not_eligible" -> "chatgpt_ineligible"
        "subscription_sharing_usage_limit_exceeded" -> "chatgpt_limit"
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "chatgpt_unavailable"
        "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported" -> "chatgpt_capability"
        "subscription_sharing_invalid_user", "invalid_token", "token_expired", "invalid_api_key", "authentication_error" -> "chatgpt_reauth"
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "chatgpt_permission"
        "model_not_found" -> "chatgpt_models"
        "permission_denied" -> "chatgpt_policy"
        "server_error" -> "chatgpt_unavailable"
        "rate_limit_exceeded" -> "rate_limit"
        else -> "provider_error"
    }

    fun code(status: Int, body: JsonObject?): String {
        val raw = (body?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive
            ?: body?.get("code") as? JsonPrimitive
        val mapped = code(raw?.contentOrNull)
        if (mapped != "provider_error") return mapped
        return when (status) {
            400, 404, 422 -> "chatgpt_capability"
            401 -> "chatgpt_reauth"
            403 -> "chatgpt_policy"
            429 -> "rate_limit"
            in 500..599 -> "chatgpt_unavailable"
            else -> "provider_error"
        }
    }
}

/** Bounded SSE decoding. Finalized items remain provisional until response.completed. */
object ChatGptSse {
    private const val MAX_CHARACTERS = 4 * 1024 * 1024
    private const val MAX_EVENT = 1024 * 1024
    private const val MAX_EVENTS = 20000
    private const val MAX_OUTPUT_ITEMS = 256

    fun read(reader: Reader, diagnostic: ChatGptDiagnostic = ChatGptDiagnostic("inference"), onText: ((String) -> Unit)? = null): JsonObject {
        val buffered = reader.buffered(8192)
        val line = StringBuilder()
        val data = StringBuilder()
        var event = ""
        var characters = 0
        var events = 0
        var firstLine = true
        var previousWasCr = false
        var responseId: String? = null
        // Some streaming routes send the final output in item events instead of
        // repeating it in the terminal envelope. Never reconstruct from deltas.
        val completedItems = sortedMapOf<Int, JsonObject>()
        val itemIds = mutableMapOf<Int, String>()
        val assistantItems = mutableSetOf<Int>()
        val textParts = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        var textCharacters = 0

        fun rememberItem(parsed: JsonObject, finished: Boolean) {
            val index = (parsed["output_index"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0 until MAX_OUTPUT_ITEMS } ?: throw FeedFailure("invalid_response")
            val item = parsed["item"] as? JsonObject ?: throw FeedFailure("invalid_response")
            val id = (item["id"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() && it.length <= 200 } ?: throw FeedFailure("invalid_response")
            if (itemIds[index]?.let { it != id } == true) throw FeedFailure("invalid_response")
            itemIds[index] = id
            if ((item["type"] as? JsonPrimitive)?.contentOrNull == "message" &&
                (item["role"] as? JsonPrimitive)?.contentOrNull.let { it == null || it == "assistant" }) assistantItems += index
            else assistantItems -= index
            if (finished) {
                val previous = completedItems[index]
                if (previous != null && previous != item) throw FeedFailure("invalid_response")
                completedItems[index] = item
            }
        }

        fun completedOutput(response: JsonObject): JsonObject {
            val output = response["output"]
            if (output != null && output != JsonNull && output !is JsonArray) throw FeedFailure("invalid_response")
            val items = (output as? JsonArray).orEmpty()
            if (items.size > MAX_OUTPUT_ITEMS) throw FeedFailure("invalid_response")
            if (items.isNotEmpty() || completedItems.isEmpty()) return response
            // A terminal envelope without output cannot certify an unfinished
            // trailing item merely because earlier finalized indices are contiguous.
            if (completedItems.keys != itemIds.keys) throw FeedFailure("invalid_response")
            // Indices describe an ordered response, not a sparse set of unrelated items.
            if (completedItems.keys.toList() != (0 until completedItems.size).toList()) throw FeedFailure("invalid_response")
            return JsonObject(response + ("output" to JsonArray(completedItems.values.toList())))
        }

        fun dispatch(): JsonObject? {
            if (data.isEmpty()) { event = ""; return null }
            if (++events > MAX_EVENTS) throw FeedFailure("invalid_response")
            val payload = data.toString().removeSuffix("\n")
            data.setLength(0)
            val eventName = event
            event = ""
            if (payload == "[DONE]") throw FeedFailure("chatgpt_interrupted")
            val parsed = try { FeedJson.parseToJsonElement(payload) as? JsonObject }
                catch (_: Exception) { null } ?: throw FeedFailure("invalid_response")
            val type = (parsed["type"] as? JsonPrimitive)?.contentOrNull ?: eventName
            // "message" is the standard default SSE event name. The JSON type
            // remains authoritative when that generic event name is explicit.
            if (eventName.isNotBlank() && eventName != "message" && type != eventName) throw FeedFailure("invalid_response")
            val response = parsed["response"] as? JsonObject
            val id = (response?.get("id") as? JsonPrimitive)?.contentOrNull
            if (id != null) {
                if (responseId != null && responseId != id) throw FeedFailure("invalid_response")
                responseId = id
            }
            AiActivityRegistry.observe(type, parsed)
            when (type) {
                "response.output_item.added" -> rememberItem(parsed, finished = false)
                "response.output_item.done" -> rememberItem(parsed, finished = true)
                "response.output_text.delta" -> if (onText != null) {
                    val index = (parsed["output_index"] as? JsonPrimitive)?.intOrNull
                    val contentIndex = (parsed["content_index"] as? JsonPrimitive)?.intOrNull
                    val id = (parsed["item_id"] as? JsonPrimitive)?.contentOrNull
                    // Reasoning/tool content and unidentified early fragments are never
                    // published as assistant prose. The final response still validates all output.
                    if (index != null && index in assistantItems && contentIndex != null && contentIndex in 0..255 && itemIds[index] == id) {
                        val delta = (parsed["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            ?: throw FeedFailure("invalid_response")
                        textCharacters += delta.length
                        if (textCharacters > MAX_EVENT) throw FeedFailure("invalid_response")
                        textParts.getOrPut(index to contentIndex) { StringBuilder() }.append(delta)
                        onText(textParts.values.joinToString("\n"))
                    }
                }
                "response.completed" -> {
                    if (response == null || (response["status"] as? JsonPrimitive)?.contentOrNull != "completed")
                        throw FeedFailure("invalid_response")
                    // A contradictory terminal error cannot certify working inference.
                    if (response["error"] != null && response["error"] != JsonNull)
                        throw FeedFailure("invalid_response", ChatGptErrors.diagnostic(response["error"] as? JsonObject,
                            diagnostic.copy(responseShape = "completed_with_error", responseStatus = "completed")))
                    return completedOutput(response)
                }
                "response.failed" -> {
                    val error = response?.get("error") as? JsonObject
                    throw FeedFailure(ChatGptErrors.code((error?.get("code") as? JsonPrimitive)?.contentOrNull),
                        ChatGptErrors.diagnostic(error, diagnostic.copy(responseStatus = "failed")))
                }
                "response.incomplete" -> throw FeedFailure("chatgpt_incomplete", diagnostic.copy(responseStatus = "incomplete"))
                "error" -> {
                    val error = parsed["error"] as? JsonObject
                    throw FeedFailure(ChatGptErrors.code(((error ?: parsed)["code"] as? JsonPrimitive)?.contentOrNull),
                        ChatGptErrors.diagnostic(error ?: parsed, diagnostic.copy(responseStatus = "failed")))
                }
            }
            return null
        }

        fun acceptLine(): JsonObject? {
            var value = line.toString().removeSuffix("\r")
            line.setLength(0)
            if (firstLine) { value = value.removePrefix("\uFEFF"); firstLine = false }
            if (value.isEmpty()) return dispatch()
            if (value.startsWith(":")) return null
            val colon = value.indexOf(':')
            val field = if (colon < 0) value else value.substring(0, colon)
            val content = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> { if (content.length > 128) throw FeedFailure("invalid_response"); event = content }
                "data" -> {
                    if (data.length + content.length + 1 > MAX_EVENT) throw FeedFailure("invalid_response")
                    data.append(content).append('\n')
                }
            }
            return null
        }

        while (true) {
            val next = buffered.read()
            if (next < 0) break
            if (++characters > MAX_CHARACTERS) throw FeedFailure("invalid_response")
            // SSE permits LF, CRLF and CR line endings, including across read boundaries.
            if (next == '\n'.code && previousWasCr) { previousWasCr = false; continue }
            previousWasCr = next == '\r'.code
            if (next == '\n'.code || previousWasCr) acceptLine()?.let { return it }
            else { if (line.length >= MAX_EVENT) throw FeedFailure("invalid_response"); line.append(next.toChar()) }
        }
        // A last event without the empty-line delimiter is not a completed SSE event.
        throw FeedFailure("chatgpt_interrupted")
    }
}

class StreamingChatGptResponses(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val requestTimeoutMillis: Long = 150_000L,
    private val planUsage: Boolean = true,
) : ChatGptResponses {
    override suspend fun complete(body: JsonObject, accessToken: String): JsonObject = perform(body, accessToken, null)

    override suspend fun stream(body: JsonObject, accessToken: String, onText: (String) -> Unit): JsonObject = perform(body, accessToken, onText)

    private suspend fun perform(body: JsonObject, accessToken: String, onText: ((String) -> Unit)?): JsonObject {
        if (accessToken.isBlank() || accessToken.length > 32768 || accessToken.any { it == '\r' || it == '\n' })
            throw mappedFailure(FeedFailure("chatgpt_reauth"))
        // Streaming is explicit for either credential path; API-key nonstream calls
        // use the separate Responses JSON request path.
        if ((body["stream"] as? JsonPrimitive)?.booleanOrNull != true ||
            (body["store"] as? JsonPrimitive)?.booleanOrNull != false) throw FeedFailure("invalid_input")
        return withTimeoutOrNull(requestTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val active = AtomicReference<HttpURLConnection?>()
                    continuation.invokeOnCancellation { active.get()?.disconnect() }
                    CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                        var diagnostic = ChatGptDiagnostic("inference")
                        try {
                            val connection = openConnection(URI("https://api.openai.com/v1/responses"))
                            active.set(connection)
                            try {
                                currentCoroutineContext().ensureActive()
                                connection.instanceFollowRedirects = false
                                connection.useCaches = false
                                connection.requestMethod = "POST"
                                connection.connectTimeout = 15_000
                                connection.readTimeout = 60_000
                                connection.doOutput = true
                                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                connection.setRequestProperty("Accept", "text/event-stream")
                                connection.setRequestProperty("User-Agent", "MeBot/${BuildConfig.VERSION_NAME}")
                                connection.setRequestProperty("Cache-Control", "no-store, no-transform")
                                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                                if (bytes.size > 512 * 1024) throw FeedFailure("invalid_input")
                                connection.setFixedLengthStreamingMode(bytes.size)
                                connection.outputStream.use { it.write(bytes) }
                                val status = connection.responseCode
                                diagnostic = diagnostic.copy(httpStatus = status.takeIf { it in 100..599 },
                                    requestId = safeRequestId(connection.getHeaderField("x-request-id")),
                                    responseMediaType = mediaType(connection.contentType))
                                if (status !in 200..299) {
                                    val error = connection.errorStream?.use { stream ->
                                        runCatching { FeedJson.parseToJsonElement(String(readBounded(stream, 65536), Charsets.UTF_8)) as? JsonObject }.getOrNull()
                                    }
                                    diagnostic = diagnostic.copy(responseShape = when {
                                        error == null -> "non_json"
                                        error["error"] is JsonObject -> "error_object"
                                        error["detail"] is JsonPrimitive -> "detail"
                                        else -> "other_json"
                                    })
                                    diagnostic = ChatGptErrors.diagnostic(error?.get("error") as? JsonObject ?: error, diagnostic)
                                    throw FeedFailure(ChatGptErrors.code(status, error), diagnostic)
                                }
                                // Content-Type is a useful hint, not evidence of inference. Some
                                // intermediaries omit or rewrite it; inspect a bounded prefix and
                                // still require the complete, validated terminal event.
                                val result = connection.inputStream.use { stream ->
                                    val reader = InputStreamReader(stream, Charsets.UTF_8).buffered(8192)
                                    when (val shape = wireShape(reader)) {
                                        "event_stream" -> {
                                            diagnostic = diagnostic.copy(responseShape = if (diagnostic.responseMediaType == "text/event-stream")
                                                "event_stream" else "event_stream_mislabeled")
                                            ChatGptSse.read(reader, diagnostic, onText)
                                        }
                                        "json_body" -> throw jsonFailure(reader, diagnostic)
                                        else -> throw FeedFailure("chatgpt_invalid_response", diagnostic.copy(responseShape = shape))
                                    }
                                }
                                diagnostic = diagnostic.copy(responseStatus = "completed")
                                // Keep a successful HTTP exchange distinguishable from a
                                // completed response whose output cannot be used by this app.
                                if (result["output"] !is JsonArray || (result["output"] as JsonArray).isEmpty())
                                    throw FeedFailure("invalid_response", diagnostic.copy(responseShape = "completed_without_output"))
                                val finalText = responseOutputText(result)
                                    ?: throw FeedFailure("invalid_response", diagnostic.copy(responseShape = "completed_without_text"))
                                currentCoroutineContext().ensureActive()
                                // Deltas are provisional. Reconcile against the authoritative
                                // completed text before returning a successful response.
                                if (onText != null) onText(finalText)
                                if (continuation.isActive) continuation.resume(result)
                            } finally { connection.disconnect(); active.compareAndSet(connection, null) }
                        } catch (cancelled: CancellationException) {
                            if (continuation.isActive) continuation.cancel(cancelled)
                        } catch (failure: FeedFailure) {
                            if (continuation.isActive) continuation.resumeWithException(
                                mappedFailure(FeedFailure(failure.code, failure.diagnostic ?: diagnostic)))
                        } catch (_: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(FeedFailure("offline", diagnostic))
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(FeedFailure("invalid_response", diagnostic))
                        }
                    }
                }
        } ?: throw mappedFailure(FeedFailure("chatgpt_interrupted", ChatGptDiagnostic("inference", responseShape = "deadline")))
    }

    private fun mappedFailure(failure: FeedFailure): FeedFailure {
        if (planUsage) return failure
        val code = when (failure.diagnostic?.providerCode) {
            "insufficient_quota" -> "quota"
            "rate_limit_exceeded" -> "rate_limit"
            "invalid_api_key", "authentication_error" -> "unauthorized"
            else -> when (failure.code) {
                "chatgpt_reauth" -> "unauthorized"
                "chatgpt_limit" -> "quota"
                "chatgpt_invalid_response" -> "invalid_response"
                "chatgpt_interrupted" -> "ai_interrupted"
                "chatgpt_incomplete" -> "ai_incomplete"
                "chatgpt_unavailable", "chatgpt_capability", "chatgpt_models", "chatgpt_permission", "chatgpt_policy" -> "provider_error"
                else -> failure.code
            }
        }
        return FeedFailure(code, failure.diagnostic)
    }

    /** Only known media types enter diagnostics; arbitrary header values are discarded. */
    private fun mediaType(raw: String?): String = when (val value = raw?.take(200)?.substringBefore(';')?.trim()?.lowercase()) {
        null, "" -> "missing"
        "text/event-stream", "application/json", "text/html", "text/plain", "application/octet-stream" -> value
        "application/problem+json" -> "application/json"
        else -> "other"
    }

    /** Peek without consuming any event, including a BOM or an initial heartbeat. */
    private fun wireShape(reader: BufferedReader): String {
        reader.mark(4097)
        return try {
            val first = StringBuilder()
            var start = true
            repeat(4096) {
                val next = reader.read()
                if (next < 0) return if (first.isEmpty()) "empty_body" else "text_body"
                val character = next.toChar()
                if (start && character == '\uFEFF') { start = false; return@repeat }
                start = false
                if (first.isEmpty() && character.isWhitespace()) return@repeat
                if (first.isEmpty() && character == '<') return "html_body"
                if (first.isEmpty() && character in "{[") return "json_body"
                if (first.isEmpty() && character == ':') return "event_stream"
                if (character == '\n' || character == '\r') return "text_body"
                first.append(character)
                if (character == ':') return if (first.toString() in setOf("data:", "event:", "id:", "retry:")) "event_stream" else "text_body"
                if (first.length > 64) return "text_body"
            }
            "unrecognized_body"
        } finally { reader.reset() }
    }

    private fun jsonFailure(reader: Reader, diagnostic: ChatGptDiagnostic): FeedFailure {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (text.length <= 65536) {
            val count = reader.read(buffer, 0, minOf(buffer.size, 65537 - text.length))
            if (count < 0) break
            text.append(buffer, 0, count)
        }
        if (text.length > 65536) return FeedFailure("chatgpt_invalid_response", diagnostic.copy(responseShape = "oversized_json"))
        val parsed = runCatching { FeedJson.parseToJsonElement(text.toString().removePrefix("\uFEFF")) as? JsonObject }.getOrNull()
            ?: return FeedFailure("chatgpt_invalid_response", diagnostic.copy(responseShape = "invalid_json"))
        val response = parsed["response"] as? JsonObject ?: parsed
        val status = (response["status"] as? JsonPrimitive)?.contentOrNull?.takeIf {
            it in setOf("completed", "failed", "incomplete", "in_progress", "queued", "cancelled")
        }
        val error = response["error"] as? JsonObject
            ?: parsed.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "error" || ChatGptErrors.diagnosticCode((it["code"] as? JsonPrimitive)?.contentOrNull) != null }
        val shape = when {
            error != null -> "error_object"
            parsed["detail"] is JsonPrimitive -> "detail"
            status != null -> "nonstream_response"
            else -> "other_json"
        }
        val details = ChatGptErrors.diagnostic(error, diagnostic.copy(responseShape = shape, responseStatus = status))
        if (error != null) return FeedFailure(ChatGptErrors.code((error["code"] as? JsonPrimitive)?.contentOrNull), details)
        // A normal JSON response is supported by API-key mode, not this plan
        // route. Do not manufacture response.completed from HTTP 200/status text.
        return FeedFailure("chatgpt_invalid_response", details)
    }

    private fun readBounded(stream: InputStream, maximum: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < maximum) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maximum - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
