package app.mynguli.feed.auth

import kotlinx.serialization.json.*

/**
 * Account catalogs and the public Models endpoint have different documented envelopes.
 * Availability here is not a claim that a model supports the requested task: the engine
 * still completes an inference check before it makes this account/model active.
 */
internal object ChatGptModelCatalog {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 1024
    private val slugPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

    fun parse(result: AuthHttpResult): List<ChatGptModel> {
        val diagnostic = result.diagnostic("models")
        val root = try { parseAuthJson(result.body, MAX_BYTES) }
            catch (_: ChatGptAuthException) { throw ChatGptAuthException("chatgpt_models", diagnostic) }
        // A malformed SIWC envelope must never fall through to another catalog's data.
        val accountCatalog = root.containsKey("models")
        val entries = if (accountCatalog) root["models"] as? JsonArray
            else if (root.string("object") == "list") root["data"] as? JsonArray else null
        if (entries == null || entries.size > MAX_ENTRIES) throw ChatGptAuthException("chatgpt_models", diagnostic)
        if (entries.isEmpty()) throw ChatGptAuthException("chatgpt_no_models", diagnostic)
        val resultModels = entries.mapNotNull { entry ->
            val model = entry as? JsonObject ?: return@mapNotNull null
            if (accountCatalog && model.text("visibility") != "list") return@mapNotNull null
            if (!accountCatalog && model.text("object") != "model") return@mapNotNull null
            // This fallback handles basic API metadata only; it does not invent a model.
            val slug = model.text(if (accountCatalog) "slug" else "id")?.takeIf { it.matches(slugPattern) }
                ?: return@mapNotNull null
            val name = model.text("display_name")?.trim()?.takeIf { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) }
                ?: slug
            ChatGptModel(slug, name)
        }.distinctBy(ChatGptModel::slug)
        if (resultModels.isEmpty()) throw ChatGptAuthException("chatgpt_no_models", diagnostic)
        return resultModels
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Only a small allowlisted description of the response shape reaches support UI. */
internal fun AuthHttpResult.diagnostic(stage: String): ChatGptDiagnostic {
    val root = if (body.length <= ChatGptModelCatalog.MAX_BYTES) runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() else null
    val shape = when {
        root == null -> "non_json"
        root["models"] is JsonArray -> "models_catalog"
        root["data"] is JsonArray && root.string("object") == "list" -> "api_catalog"
        root["error"] is JsonObject -> "error_object"
        root["error"] is JsonPrimitive -> "error_string"
        root["detail"] is JsonPrimitive -> "detail"
        else -> "other_json"
    }
    return ChatGptDiagnostic(stage, status.takeIf { it in 100..599 }, safeRequestId(requestId), shape)
}

internal fun safeRequestId(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
