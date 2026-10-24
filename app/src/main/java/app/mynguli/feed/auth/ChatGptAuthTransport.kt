package app.mynguli.feed.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AuthHttpResult(val status: Int, val body: String, val requestId: String? = null) {
    override fun toString() = "AuthHttpResult(status=$status, body=[redacted])"
}

internal interface ChatGptAuthTransport {
    suspend fun request(url: String, form: Map<String, String>? = null, bearer: String? = null): AuthHttpResult
}

internal class SecureChatGptAuthTransport(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val requestTimeoutMillis: Long = 45_000L,
) : ChatGptAuthTransport {
    override suspend fun request(url: String, form: Map<String, String>?, bearer: String?): AuthHttpResult {
            val uri = try { URI(url) } catch (_: Exception) { throw ChatGptAuthException("chatgpt_configuration") }
            if (uri.scheme != "https" || uri.host !in setOf("auth.openai.com", "api.openai.com") ||
                uri.port !in setOf(-1, 443) || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null)
                throw ChatGptAuthException("chatgpt_configuration")
            if (form != null && uri.host != "auth.openai.com") throw ChatGptAuthException("chatgpt_configuration")
            if (bearer != null && (uri.host != "api.openai.com" || uri.path != "/v1/models"))
                throw ChatGptAuthException("chatgpt_configuration")
            return withTimeoutOrNull(requestTimeoutMillis) {
                suspendCancellableCoroutine<AuthHttpResult> { continuation ->
                    val active = AtomicReference<HttpURLConnection?>()
                    continuation.invokeOnCancellation { active.get()?.disconnect() }
                    CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                        try {
                            val connection = openConnection(uri)
                            active.set(connection)
                            try {
                                if (!continuation.isActive) throw CancellationException()
                                val result = perform(connection, form, bearer, uri.path == "/v1/models") { continuation.isActive }
                                if (continuation.isActive) continuation.resume(result)
                            } finally { connection.disconnect(); active.compareAndSet(connection, null) }
                        } catch (cancel: CancellationException) {
                            if (continuation.isActive) continuation.cancel(cancel)
                        } catch (failure: ChatGptAuthException) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } catch (_: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(ChatGptAuthException("offline"))
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(ChatGptAuthException("chatgpt_invalid_response"))
                        }
                    }
                }
            } ?: throw ChatGptAuthException("chatgpt_unavailable")
    }

    private fun perform(connection: HttpURLConnection, form: Map<String, String>?, bearer: String?, models: Boolean, active: () -> Boolean): AuthHttpResult {
            val startedAt = System.nanoTime()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.requestMethod = if (form == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MeBot/0.5")
                connection.setRequestProperty("Cache-Control", "no-store")
                if (bearer != null) {
                    if (bearer.any { it == '\r' || it == '\n' } || bearer.length > 32768)
                        throw ChatGptAuthException("chatgpt_invalid_response")
                    connection.setRequestProperty("Authorization", "Bearer $bearer")
                }
                if (form != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = form.entries.joinToString("&") { encodeForm(it.key) + "=" + encodeForm(it.value) }
                        .toByteArray(Charsets.UTF_8)
                    if (body.size > 128 * 1024) throw ChatGptAuthException("chatgpt_invalid_response")
                    try { connection.outputStream.use { it.write(body) } } finally { body.fill(0) }
                }
                val status = connection.responseCode
                val requestId = safeRequestId(connection.getHeaderField("x-request-id"))
                if (status in 300..399) throw ChatGptAuthException("chatgpt_configuration")
                val limit = if (status !in 200..299) 64 * 1024 else if (models) ChatGptModelCatalog.MAX_BYTES else 512 * 1024
                val output = ByteArrayOutputStream()
                (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        if (!active()) throw CancellationException()
                        if (System.nanoTime() - startedAt > requestTimeoutMillis * 1_000_000) throw ChatGptAuthException("chatgpt_unavailable")
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > limit) throw ChatGptAuthException(
                            if (models) "chatgpt_models" else "chatgpt_invalid_response",
                            ChatGptDiagnostic(if (models) "models" else "sign_in", status, requestId))
                        output.write(buffer, 0, read)
                    }
                }
                return AuthHttpResult(status, output.toString("UTF-8"), requestId)
    }
}

internal fun encodeForm(value: String): String = URLEncoder.encode(value, "UTF-8")

internal fun parseAuthJson(text: String, maximum: Int = 512 * 1024): JsonObject = try {
    if (text.length > maximum) throw ChatGptAuthException("chatgpt_invalid_response")
    Json.parseToJsonElement(text).jsonObject
} catch (failure: ChatGptAuthException) { throw failure }
catch (_: Exception) { throw ChatGptAuthException("chatgpt_invalid_response") }

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

/** Provider descriptions are deliberately discarded: they can contain sensitive request details. */
internal fun authFailure(result: AuthHttpResult, stage: String = "sign_in"): ChatGptAuthException {
    val root = runCatching { parseAuthJson(result.body) }.getOrNull()
    val code = (root?.get("error") as? JsonObject)?.string("code")
        ?: (root?.get("error") as? JsonPrimitive)?.contentOrNull
        ?: root?.string("code")
    return ChatGptAuthException(when (code) {
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused" -> "chatgpt_reauth"
        "invalid_client" -> "chatgpt_configuration"
        "subscription_sharing_user_not_eligible" -> "chatgpt_ineligible"
        "subscription_sharing_usage_limit_exceeded" -> "chatgpt_limit"
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "chatgpt_unavailable"
        "subscription_sharing_invalid_user" -> "chatgpt_reauth"
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "chatgpt_permission"
        "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported" -> "chatgpt_capability"
        else -> when (result.status) {
            401 -> "chatgpt_reauth"
            403 -> "chatgpt_policy"
            429 -> "rate_limit"
            in 500..599 -> "chatgpt_unavailable"
            else -> "chatgpt_invalid_response"
        }
    }, result.diagnostic(stage))
}
