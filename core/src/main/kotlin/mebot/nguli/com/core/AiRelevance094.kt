package mebot.nguli.com.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Identity and bounded-use policy for optional AI-assisted matching of public feed candidates. */
object AiRelevance094 {
    const val CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    const val MAX_ADJUSTMENT = 10
    private const val MAX_TIMESTAMP = 32_503_680_000_000L
    private val digestPattern = Regex("[0-9a-f]{64}")

    /** Hash only the public content presented to the model and its stable source identity. */
    fun contentDigest(item: FeedItem): String = digest(listOf(
        "mebot-ai-fit-content-v1", item.id, item.moduleId, item.kind,
        FeedLogic.canonicalUrl(item.sourceUrl), item.title, item.summary, item.publishedAtMillis.toString(),
    ))

    /** A non-secret fingerprint of the exact module scope, explicit feedback and selected provider identity. */
    fun scopeProviderDigest(state: FeedState, module: FeedModule): String {
        val parts = mutableListOf(
            "mebot-ai-fit-scope-v1", module.id, module.kind, module.title, module.query, module.enabled.toString(),
            module.alerts.toString(), module.zodiacSign, RegionalDefaults.region(state), state.timezone,
            ModuleCatalog.discoveryQuery(module), ModuleDefaults.requestContext(module),
            "provider", state.connection.mode, state.connection.model, state.connection.serviceUrl,
            state.connection.accountId, state.connection.webSearch.toString(), state.connection.allowLocalHttp.toString(),
        )
        module.sources.forEach { parts += listOf("source", it.name, it.url) }
        module.preferences.toSortedMap().forEach { (key, value) -> parts += listOf("preference", key, value) }
        FeedLogic.scopedContext(state, module).forEach { entry ->
            parts += listOf("context", entry.id, entry.text, entry.category, entry.origin, entry.global.toString(), entry.scopes.sorted().joinToString(","))
        }
        RelevanceFeedback.forModule(state, module).forEach { signal ->
            parts += listOf("feedback", signal.itemId, signal.moduleId, signal.relevant.toString(), signal.title,
                signal.terms.sorted().joinToString(" "), signal.updatedAtMillis.toString())
        }
        return digest(parts)
    }

    fun isValidEstimate(estimate: AiRelevanceEstimate): Boolean =
        estimate.fitEstimate in 0..100 && estimate.assessedAtMillis in 0..MAX_TIMESTAMP &&
            digestPattern.matches(estimate.contentDigest) && digestPattern.matches(estimate.scopeProviderDigest)

    fun validate(estimate: AiRelevanceEstimate) {
        require(isValidEstimate(estimate))
    }

    /** Explicitly an interest-fit component, not a calibrated probability or click prediction. */
    fun adjustment(state: FeedState, item: FeedItem, now: Long, expectedScopeProviderDigest: String? = null): Int {
        val estimate = item.aiRelevance ?: return 0
        if (!isApplicable(state, item, now, expectedScopeProviderDigest)) return 0
        return ((estimate.fitEstimate - 50) / 5).coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
    }

    fun isApplicable(state: FeedState, item: FeedItem, now: Long, expectedScopeProviderDigest: String? = null): Boolean {
        val estimate = item.aiRelevance ?: return false
        if (!isValidEstimate(estimate) || now !in 0..MAX_TIMESTAMP || item.aiGenerated || item.weather != null || item.contentType == "weather" ||
            item.kind in setOf("weather", "horoscope") || !FeedLogic.safeUrl(item.sourceUrl)) return false
        // User-controlled actions remain authoritative; never use an AI boost to move protected items.
        if (item.id in state.savedIds || item.id in state.hiddenIds || state.starredStories.any { it.itemId == item.id }) return false
        val module = state.modules.firstOrNull { it.id == item.moduleId } ?: return false
        val expectedScope = expectedScopeProviderDigest ?: scopeProviderDigest(state, module)
        if (estimate.scopeProviderDigest != expectedScope || estimate.contentDigest != contentDigest(item)) return false
        return now - estimate.assessedAtMillis in 0..CACHE_TTL_MILLIS
    }

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
