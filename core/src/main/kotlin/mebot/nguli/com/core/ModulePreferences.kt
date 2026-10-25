package mebot.nguli.com.core

/** A small, explicit schema for persisted per-module choices. Empty maps retain v0.5 defaults. */
data class ModulePreferenceDefinition(
    val key: String,
    val choices: List<String> = emptyList(),
    val maximumLength: Int = 0,
) {
    val defaultValue: String get() = choices.firstOrNull().orEmpty()
    val isText: Boolean get() = maximumLength > 0
}

object ModulePreferences {
    private fun choice(key: String, vararg choices: String) = ModulePreferenceDefinition(key, choices.toList())
    private val language = choice("language", "auto", "en", "sw")
    private val location = ModulePreferenceDefinition("location", maximumLength = 160)

    fun definitions(kind: String): List<ModulePreferenceDefinition> {
        val specific = when (kind) {
            "news" -> listOf(choice("coverage", "balanced", "local", "world"), location)
            "technology" -> listOf(choice("focus", "all", "ai", "software", "infrastructure", "hardware"))
            "security" -> listOf(choice("focus", "all", "software", "cloud", "networks"), choice("urgency", "all", "critical"))
            "business" -> listOf(choice("focus", "all", "regional", "operations", "markets"), location)
            "science" -> listOf(choice("focus", "all", "ai", "health", "climate", "space"))
            "opportunities" -> listOf(choice("type", "all", "jobs", "contracts", "grants"), choice("workplace", "any", "local", "remote"), location)
            "scholarships" -> listOf(choice("level", "any", "masters", "phd"), choice("funding", "any", "full"))
            "learning" -> listOf(choice("level", "any", "beginner", "intermediate", "advanced"), choice("format", "any", "guides", "courses", "videos"), choice("cost", "any", "free"))
            "github" -> listOf(choice("focus", "all", "tools", "libraries", "self_hosted"), choice("code_language", "any", "python", "javascript", "php", "go", "rust"))
            "weather" -> listOf(location)
            "entertainment" -> listOf(choice("type", "both", "films", "series"), choice("mood", "any", "tense", "thoughtful", "light"))
            "music" -> listOf(choice("focus", "mixed", "listening", "practice"), choice("format", "any", "performances", "lessons"))
            "events" -> listOf(choice("attendance", "any", "nearby", "online"), choice("window", "month", "week"), location)
            "travel" -> listOf(choice("focus", "all", "weekend", "outdoors", "culture"), choice("cost", "any", "low_cost"), location)
            "horoscope" -> listOf(choice("focus", "general", "work", "relationships", "reflection"), choice("tone", "calm", "playful"))
            "media" -> listOf(choice("focus", "all", "explainers", "demonstrations", "interviews", "news"), choice("duration", "short", "quick", "any"))
            "watch" -> listOf(choice("format", "any", "articles", "videos"))
            else -> emptyList()
        }
        return if (kind == "weather" || specific.isEmpty()) specific else specific + language
    }

    fun value(module: FeedModule, key: String): String = module.preferences[key]
        ?: definitions(module.kind).find { it.key == key }?.defaultValue.orEmpty()

    fun location(module: FeedModule, fallback: String): String = module.preferences["location"].orEmpty().trim().ifBlank { fallback }

    fun validate(module: FeedModule) {
        val schema = definitions(module.kind).associateBy { it.key }
        require(module.preferences.size <= schema.size)
        module.preferences.forEach { (key, value) ->
            val definition = requireNotNull(schema[key]) { "Unknown module preference" }
            require(value.none { it.isISOControl() })
            if (definition.isText) require(value.length <= definition.maximumLength)
            else require(value in definition.choices)
        }
    }

    /** Selection data for discovery, not permission to send additional personal context. */
    fun discoveryGuidance(module: FeedModule): String {
        validate(module)
        val configured = definitions(module.kind).mapNotNull { definition ->
            val value = module.preferences[definition.key]?.trim().orEmpty()
            if (definition.key == "window" && (value.isBlank() || value == "month")) "Events within the next 30 days"
            else if (value.isBlank() || value == definition.defaultValue) null
            else when (definition.key) {
                "language" -> "Response language: ${if (value == "sw") "Kiswahili" else "English"}"
                "duration" -> when (value) {
                    "quick" -> "Prefer videos up to 3 minutes; only state duration when verified"
                    "any" -> "Any video length; prefer a useful direct resource"
                    else -> null
                }
                "location" -> "Selected location: $value"
                "funding" -> "Only confirmed full-funding opportunities"
                "urgency" -> "Prioritize critical vendor advisories and their documented fixes"
                "window" -> "Events within the next 7 days"
                else -> "${definition.key.replace('_', ' ')}: ${value.replace('_', ' ')}"
            }
        }
        return configured.joinToString("; ")
    }
}

/** Reordering changes only order. Missing, duplicate or stale identities fail atomically. */
object ModuleOrder {
    fun reorder(modules: List<FeedModule>, orderedIds: List<String>): List<FeedModule> {
        require(orderedIds.size == modules.size && orderedIds.distinct().size == orderedIds.size)
        val byId = modules.associateBy { it.id }
        require(byId.size == modules.size && orderedIds.toSet() == byId.keys)
        return orderedIds.map { byId.getValue(it) }
    }

    /** Keep unshown modules in their existing slots when the installed subset is dragged. */
    fun reorderSubset(allIds: List<String>, orderedSubset: List<String>): List<String> {
        require(allIds.distinct().size == allIds.size && orderedSubset.distinct().size == orderedSubset.size)
        require(orderedSubset.all { it in allIds })
        val subset = orderedSubset.toSet()
        val iterator = orderedSubset.iterator()
        return allIds.map { if (it in subset) iterator.next() else it }
    }
}
