package mebot.nguli.com.feed

import mebot.nguli.com.core.AiConversation
import mebot.nguli.com.core.AiSuggestedInteractions095
import mebot.nguli.com.core.ConversationLogic
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.PersonalProfile085
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Explicit destination captured when a suggested next step is rendered. Null IDs never mean "whatever is active." */
enum class SuggestionTargetKind097 { ITEM, CONVERSATION, MODULE, GENERAL }

/**
 * Immutable, non-secret render-time binding for a suggestion. `revision` is a digest of the
 * approved scope and current destination content; callers must re-create it from persisted state
 * before either Send or Edit. The object intentionally carries no prompt text.
 */
data class SuggestionTargetSnapshot097(
    val kind: SuggestionTargetKind097,
    val targetId: String?,
    val moduleId: String? = null,
    val itemId: String? = null,
    val revision: String,
)

/** The only renderable option is a question returned and validated from the configured AI provider. */
data class SuggestionOptionSnapshot097(
    val id: String,
    val questionText: String,
    val generatedAtMillis: Long,
)

enum class SuggestionSheetStatus097 {
    READY,
    NEEDS_GENERATION,
    INCOMPLETE,
    GENERATING,
    GENERATING_MORE,
    UNAVAILABLE,
    ERROR,
    STALE_TARGET,
    LIMIT_REACHED,
}

/** Immutable render-time result. Empty options are intentional unless status is READY. */
data class SuggestionSheetSnapshot097(
    val target: SuggestionTargetSnapshot097,
    val status: SuggestionSheetStatus097,
    val options: List<SuggestionOptionSnapshot097> = emptyList(),
    val requestId: String? = null,
    val failureCode: String? = null,
    val validUntilMillis: Long? = null,
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreFailureCode: String? = null,
)

data class SuggestionGenerationState097(
    val requestId: String,
    val target: SuggestionTargetSnapshot097,
    val status: SuggestionSheetStatus097,
    val failureCode: String? = null,
    val appending: Boolean = false,
    val prefetch: Boolean = false,
)

/** Target fingerprints do not expose confirmed context, message text, credentials, or server settings. */
object SuggestionTargets097 {
    /** Bump when the provider question contract or prepared-cache semantics change. */
    private const val REVISION_FORMAT = "mebot-ai-suggestions-097-v1"
    fun item(state: FeedState, itemId: String, appLanguage: String): SuggestionTargetSnapshot097? {
        val item = state.items.singleOrNull { it.id == itemId } ?: return null
        val module = state.modules.singleOrNull { it.id == item.moduleId && it.enabled } ?: return null
        if (item.kind != module.kind || item.kind in setOf("weather", "horoscope") || item.weather != null) return null
        return snapshot(SuggestionTargetKind097.ITEM, item.id, module.id, item.id,
            listOf("item", AiSuggestedInteractions095.contentDigest(item),
                AiSuggestedInteractions095.scopeProviderDigest(state, module, language(appLanguage))))
    }

    fun conversation(state: FeedState, conversationId: String, appLanguage: String): SuggestionTargetSnapshot097? {
        val thread = state.conversations.singleOrNull { it.id == conversationId } ?: return null
        val module = thread.moduleId?.let { id -> state.modules.singleOrNull { it.id == id && it.enabled } }
        if (thread.moduleId != null && module == null) return null
        if (module?.kind in setOf("weather", "horoscope")) return null
        val item = thread.itemId?.let { id -> state.items.singleOrNull { it.id == id } } ?: if (thread.itemId == null) null else return null
        if (item != null && (module == null || item.moduleId != module.id || item.kind != module.kind ||
                item.kind in setOf("weather", "horoscope") || item.weather != null)) return null
        val scope = module?.let { AiSuggestedInteractions095.scopeProviderDigest(state, it, language(appLanguage)) }
            ?: globalScopeDigest(state, appLanguage)
        return snapshot(SuggestionTargetKind097.CONVERSATION, thread.id, thread.moduleId, thread.itemId,
            buildList {
                add("conversation"); add(thread.id); add(thread.title); add(thread.itemId.orEmpty()); add(thread.moduleId.orEmpty())
                add(scope); add(thread.updatedAtMillis.toString()); add(thread.turns.size.toString())
                if (item != null) add(AiSuggestedInteractions095.contentDigest(item))
                // Match the exact bounded transcript the provider receives; full local chat may
                // be long, but its request window is capped at 16 turns / 36k characters.
                ConversationLogic.requestHistory(thread).forEach { turn -> addAll(listOf("turn", turn.role, turn.text)) }
                thread.attachments.forEach { attachment ->
                    addAll(listOf("attachment", attachment.id, attachment.name, attachment.text, attachment.usedAtMillis.toString()))
                }
            })
    }

    fun module(state: FeedState, moduleId: String, appLanguage: String): SuggestionTargetSnapshot097? {
        val module = state.modules.singleOrNull { it.id == moduleId && it.enabled } ?: return null
        if (module.kind in setOf("weather", "horoscope")) return null
        return snapshot(SuggestionTargetKind097.MODULE, module.id, module.id, null,
            listOf("module", AiSuggestedInteractions095.scopeProviderDigest(state, module, language(appLanguage))))
    }

    /** General is a deliberate scope and fingerprints only confirmed global About You context. */
    fun general(state: FeedState, appLanguage: String): SuggestionTargetSnapshot097 =
        snapshot(SuggestionTargetKind097.GENERAL, null, null, null, listOf("general", globalScopeDigest(state, appLanguage)))

    fun current(state: FeedState, expected: SuggestionTargetSnapshot097, appLanguage: String): Boolean {
        val actual = when (expected.kind) {
            SuggestionTargetKind097.ITEM -> expected.targetId?.let { item(state, it, appLanguage) }
            SuggestionTargetKind097.CONVERSATION -> expected.targetId?.let { conversation(state, it, appLanguage) }
            SuggestionTargetKind097.MODULE -> expected.targetId?.let { module(state, it, appLanguage) }
            SuggestionTargetKind097.GENERAL -> if (expected.targetId == null && expected.moduleId == null && expected.itemId == null)
                general(state, appLanguage) else null
        }
        return actual == expected
    }

    private fun language(value: String): String = value.takeIf { it == "sw" } ?: "en"

    private fun globalScopeDigest(state: FeedState, appLanguage: String): String = digest(buildList {
        add("mebot-suggestion-global-scope-v1"); add(language(appLanguage))
        val connection = state.connection
        addAll(listOf(connection.mode, connection.model, connection.accountId, connection.serviceUrl,
            connection.webSearch.toString(), connection.allowLocalHttp.toString()))
        state.context.filter { it.confirmed && PersonalProfile085.isGlobal(it) }
            .sortedBy { it.id }
            .forEach { entry -> addAll(listOf("global-context", entry.id, entry.text, entry.category, entry.origin,
                entry.updatedAtMillis.toString(), entry.scopes.sorted().joinToString(","))) }
        // General/chat questions may route into any enabled module's approved context. Include
        // every enabled module in the local digest so changed scoped notes invalidate the sheet.
        state.modules.filter { it.enabled }
            .forEach { module -> add(AiSuggestedInteractions095.scopeProviderDigest(state, module, language(appLanguage))) }
    })

    private fun snapshot(
        kind: SuggestionTargetKind097,
        targetId: String?,
        moduleId: String?,
        itemId: String?,
        revisionParts: List<String>,
    ) = SuggestionTargetSnapshot097(kind, targetId, moduleId, itemId, digest(listOf(REVISION_FORMAT) + revisionParts))

    private fun digest(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> parts.forEach { part ->
            val value = part.toByteArray(Charsets.UTF_8)
            output.writeInt(value.size); output.write(value)
        } }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
