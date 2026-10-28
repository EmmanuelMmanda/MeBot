package mebot.nguli.com.feed

import kotlinx.serialization.json.*
import mebot.nguli.com.core.*
import java.security.MessageDigest

/** Optional reading output is independent of sourced candidates and their five-question sets. */
internal object OriginalReads098 {
    private val keys = setOf("title", "summary", "why", "basis", "sections", "evidenceUrls", "furtherReadingUrls", "suggestedInteractions")
    private val embeddedLink = Regex("(?i)(?:https?://|www\\.)")

    /** Recheck the optional body budget against the current atomic commit snapshot. */
    fun withinBudget(state: FeedState, items: List<FeedItem>): List<FeedItem> {
        var budgetState = state
        return items.filter { item ->
            if (item.contentType != "original_read") true
            else if (!FeedLogic.canAcceptOriginalRead(budgetState, item)) false
            else {
                budgetState = budgetState.copy(items = budgetState.items.filterNot { it.id == item.id } + item)
                true
            }
        }
    }

    fun parse(
        value: JsonElement?, state: FeedState, module: FeedModule, language: String, now: Long,
        consulted: Map<String, FeedCitation>,
        interactions: (JsonElement?) -> List<AiSuggestedInteraction>,
    ): List<FeedItem> {
        if (module.kind in setOf("weather", "horoscope")) return emptyList()
        val rows = value as? JsonArray ?: return emptyList() // Older providers remain compatible.
        if (rows.size > 1) return emptyList() // Never turn an optional reading into unbounded output.
        return rows.mapNotNull { row -> runCatching {
            val obj = row as? JsonObject ?: return@mapNotNull null
            if (obj.keys != keys) return@mapNotNull null
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("string")
            fun references(key: String): List<FeedCitation> {
                val urls = obj[key] as? JsonArray ?: error("references")
                require(urls.size <= 5)
                return urls.map { entry ->
                    val url = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("url")
                    require(FeedLogic.safeUrl(url, httpsOnly = true))
                    consulted[FeedLogic.canonicalUrl(url)] ?: error("unconsulted reference")
                }.distinctBy { FeedLogic.canonicalUrl(it.url) }
            }
            val sections = (obj["sections"] as? JsonArray ?: error("sections")).map { entry ->
                val section = entry as? JsonObject ?: error("section")
                require(section.keys == setOf("heading", "body"))
                fun part(key: String) = (section[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("section text")
                FeedContentSection(part("heading"), part("body"))
            }
            val evidence = references("evidenceUrls")
            val furtherReading = references("furtherReadingUrls")
            val basis = text("basis")
            require(basis in setOf("reflection", "source_grounded"))
            require(basis != "source_grounded" || evidence.isNotEmpty())
            require(basis != "reflection" || evidence.isEmpty())
            // References have a separate, verified destination; never manufacture inline links.
            require((listOf(text("title"), text("summary"), text("why")) + sections.flatMap { listOf(it.heading, it.body) })
                .none(embeddedLink::containsMatchIn))
            val identity = buildJsonObject {
                put("module", module.id); put("scope", AiSuggestedInteractions095.scopeProviderDigest(state, module, language))
                put("title", text("title")); put("sections", obj.getValue("sections"))
            }.toString()
            val id = "read-" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
            var item = FeedItem(
                id = id, moduleId = module.id, title = text("title"), summary = text("summary"), why = text("why"),
                fetchedAtMillis = now, publishedAtMillis = 0, kind = module.kind, aiGenerated = true,
                acquisition = FeedAcquisition.ORIGINAL_READ, authorship = FeedAuthorship.AI,
                contentType = "original_read", sections = sections, citations = evidence, furtherReading = furtherReading,
            )
            FeedLogic.validateItem(item)
            val questions = interactions(obj["suggestedInteractions"])
            if (questions.isNotEmpty()) item = item.copy(
                suggestedInteractions = questions,
                suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(item),
                suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(state, module, language),
                suggestedInteractionsGeneratedAtMillis = now,
            )
            FeedLogic.validateItem(item)
            item.takeIf { FeedLogic.canAcceptOriginalRead(state, it) }
        }.getOrNull() }
    }

    fun addToSchema(base: JsonObject, interactionSchema: JsonObject): JsonObject {
        fun string() = buildJsonObject { put("type", "string") }
        fun strings() = buildJsonObject { put("type", "array"); put("maxItems", 5); put("items", string()) }
        val read = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("required", JsonArray(keys.map(::JsonPrimitive)))
            put("properties", buildJsonObject {
                put("title", string()); put("summary", string()); put("why", string())
                put("basis", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("reflection"), JsonPrimitive("source_grounded")))) })
                put("sections", buildJsonObject {
                    put("type", "array"); put("minItems", 2); put("maxItems", 4)
                    put("items", buildJsonObject {
                        put("type", "object"); put("additionalProperties", false)
                        put("required", JsonArray(listOf(JsonPrimitive("heading"), JsonPrimitive("body"))))
                        put("properties", buildJsonObject { put("heading", string()); put("body", string()) })
                    })
                })
                put("evidenceUrls", strings()); put("furtherReadingUrls", strings()); put("suggestedInteractions", interactionSchema)
            })
        }
        val array = buildJsonObject { put("type", "array"); put("maxItems", 1); put("items", read) }
        return JsonObject(base + mapOf(
            "required" to JsonArray(base.getValue("required").jsonArray + JsonPrimitive("originalReads")),
            "properties" to JsonObject(base.getValue("properties").jsonObject + ("originalReads" to array)),
        ))
    }
}
