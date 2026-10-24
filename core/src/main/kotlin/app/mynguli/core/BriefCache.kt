package app.mynguli.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Completed explanations are reusable only for the same source, settings and explicit context. */
object BriefCache {
    fun fingerprint(state: FeedState, item: FeedItem, language: String): String {
        val module = state.modules.find { it.id == item.moduleId }
        val scoped = ConversationLogic.context(state, AiConversation(itemId = item.id, moduleId = item.moduleId))
        val parts = listOf(Json.encodeToString(item.copy(fetchedAtMillis = 0, relevance = 0, why = "", media = null)),
            module?.let { Json.encodeToString(it) }.orEmpty(), Json.encodeToString(scoped),
            Json.encodeToString(state.connection), state.region, state.timezone, language)
        val bytes = Json.encodeToString(parts).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun reusable(brief: AiItemBrief, fingerprint: String, now: Long): Boolean =
        brief.fingerprint == fingerprint && now - brief.generatedAtMillis in 0 until 6 * 3_600_000L
}
