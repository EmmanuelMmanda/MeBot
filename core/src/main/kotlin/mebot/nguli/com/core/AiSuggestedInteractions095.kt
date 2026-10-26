package mebot.nguli.com.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Validation and cache identity for optional item-bound, explicitly user-triggered questions. */
object AiSuggestedInteractions095 {
    const val MAX_INTERACTIONS = 2
    const val MAX_LABEL_LENGTH = 48
    const val MAX_PROMPT_LENGTH = 160
    const val CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_TIMESTAMP = 32_503_680_000_000L
    private val digestPattern = Regex("[0-9a-f]{64}")
    // Conservative domain-like syntax rejection applies only to optional model questions,
    // never to source attribution or the user's own editable text.
    private val urlPattern = Regex("""(?i)(?:\b[a-z][a-z0-9+.-]*://|\b(?:mailto|tel|sms|data|javascript):|\bwww\.|\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}\b(?:/[^\s]*)?)""")

    /** Hash the exact bounded facts an item-bound question may discuss, never local cache metadata. */
    fun contentDigest(item: FeedItem): String = digest(buildList {
        add("mebot-ai-suggested-interactions-content-v1")
        addAll(listOf(item.id, item.moduleId, item.kind, FeedLogic.canonicalUrl(item.sourceUrl), item.title,
            item.summary, item.publishedAtMillis.toString(), item.deadline, item.contentType, item.sourceName,
            item.nextStep, item.caveat))
        item.keyPoints.forEach { add("keyPoint:$it") }
        item.citations.map { FeedLogic.canonicalUrl(it.url) }.distinct().sorted().forEach { add("citation:$it") }
    })

    /** Fingerprint only approved module scope and explicit provider identity; no credential or impression history. */
    fun scopeProviderDigest(state: FeedState, module: FeedModule, appLanguage: String): String = digest(buildList {
        addAll(listOf("mebot-ai-suggested-interactions-scope-v1", module.id, module.kind, module.title,
            module.query, module.enabled.toString(), module.alerts.toString(), module.zodiacSign,
            RegionalDefaults.region(state), state.timezone, ModuleCatalog.discoveryQuery(module),
            ModuleDefaults.requestContext(module), responseLanguage(module, appLanguage), "provider", state.connection.mode, state.connection.model,
            state.connection.serviceUrl, state.connection.accountId, state.connection.webSearch.toString(),
            state.connection.allowLocalHttp.toString()))
        module.sources.forEach { addAll(listOf("source", it.name, FeedLogic.canonicalUrl(it.url))) }
        module.preferences.toSortedMap().forEach { (key, value) -> addAll(listOf("preference", key, value)) }
        FeedLogic.scopedContext(state, module).forEach { entry ->
            addAll(listOf("context", entry.id, entry.text, entry.category, entry.origin, entry.global.toString(),
                entry.scopes.sorted().joinToString(",")))
        }
        RelevanceFeedback.forModule(state, module).forEach { signal ->
            addAll(listOf("feedback", signal.itemId, signal.moduleId, signal.relevant.toString(), signal.title,
                signal.terms.sorted().joinToString(" "), signal.updatedAtMillis.toString()))
        }
    })

    fun areValid(interactions: List<AiSuggestedInteraction>): Boolean =
        interactions.size <= MAX_INTERACTIONS && interactions.all { interaction ->
            interaction.intent == AiSuggestedInteractionIntent.ASK_ABOUT_ITEM &&
                interaction.label.isNotBlank() && interaction.label.length <= MAX_LABEL_LENGTH && safeText(interaction.label) &&
                interaction.prompt.isNotBlank() && interaction.prompt.length <= MAX_PROMPT_LENGTH && safeText(interaction.prompt) &&
                '?' in interaction.prompt && !urlPattern.containsMatchIn(interaction.label) && !urlPattern.containsMatchIn(interaction.prompt)
        } && interactions.distinctBy { it.prompt.trim().lowercase() }.size == interactions.size

    fun validate(item: FeedItem) {
        require(areValid(item.suggestedInteractions))
        if (item.suggestedInteractions.isEmpty()) {
            require(item.suggestedInteractionsContentDigest.isEmpty())
            require(item.suggestedInteractionsScopeProviderDigest.isEmpty())
            require(item.suggestedInteractionsGeneratedAtMillis == 0L)
            return
        }
        require(item.kind !in setOf("weather", "horoscope") && item.weather == null && item.sourceUrl.isNotBlank())
        require(digestPattern.matches(item.suggestedInteractionsContentDigest))
        require(digestPattern.matches(item.suggestedInteractionsScopeProviderDigest))
        require(item.suggestedInteractionsGeneratedAtMillis in 0..MAX_TIMESTAMP)
    }

    fun isApplicable(state: FeedState, item: FeedItem, now: Long, appLanguage: String): Boolean {
        if (item.suggestedInteractions.isEmpty() || !areValid(item.suggestedInteractions) ||
            item.kind in setOf("weather", "horoscope") || item.weather != null || !FeedLogic.safeUrl(item.sourceUrl) ||
            now !in 0..MAX_TIMESTAMP || item.suggestedInteractionsGeneratedAtMillis !in 0..MAX_TIMESTAMP ||
            now - item.suggestedInteractionsGeneratedAtMillis !in 0..CACHE_TTL_MILLIS ||
            item.suggestedInteractionsContentDigest != contentDigest(item)) return false
        val module = state.modules.firstOrNull { it.id == item.moduleId && it.enabled } ?: return false
        if (item.kind != module.kind) return false
        return item.suggestedInteractionsScopeProviderDigest == scopeProviderDigest(state, module, appLanguage)
    }

    /** Stable UI key derived locally; the model cannot choose action IDs or destinations. */
    fun suggestionId(item: FeedItem, suggestion: AiSuggestedInteraction): String = digest(listOf(
        "mebot-ai-suggested-interaction-id-v1", item.id, contentDigest(item),
        item.suggestedInteractionsScopeProviderDigest, suggestion.intent.name, suggestion.label, suggestion.prompt,
    )).take(24)

    /** Resolve a stale-safe chip against the latest caller-supplied state; never executes or sends it. */
    fun resolve(
        state: FeedState,
        itemId: String,
        stableSuggestionId: String,
        now: Long,
        appLanguage: String,
    ): AiSuggestedInteraction? {
        val item = state.items.singleOrNull { it.id == itemId } ?: return null
        if (!isApplicable(state, item, now, appLanguage)) return null
        return item.suggestedInteractions.singleOrNull { suggestionId(item, it) == stableSuggestionId }
    }

    /** Drop only derived interaction cache, preserving source facts and every user-controlled field. */
    fun clear(item: FeedItem): FeedItem = item.copy(
        suggestedInteractions = emptyList(),
        suggestedInteractionsContentDigest = "",
        suggestedInteractionsScopeProviderDigest = "",
        suggestedInteractionsGeneratedAtMillis = 0,
    )

    fun clearAll(state: FeedState): FeedState = state.copy(items = state.items.map(::clear))

    fun responseLanguage(module: FeedModule, appLanguage: String): String =
        ModulePreferences.value(module, "language").takeIf { it in setOf("en", "sw") }
            ?: appLanguage.takeIf { it in setOf("en", "sw") }
            ?: "en"

    private fun safeText(value: String): Boolean = value.none { it.isISOControl() } && value == value.trim()

    private fun digest(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            parts.forEach { part ->
                val encoded = part.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
