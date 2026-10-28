package mebot.nguli.com.core

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

object FeedLogic {
    const val ORIGINAL_READ_MIN_BODY_CHARS_098 = 1_000
    const val ORIGINAL_READ_MAX_BODY_CHARS_098 = 12_000
    const val ORIGINAL_READ_MAX_COUNT_098 = 128
    const val ORIGINAL_READ_MAX_TOTAL_BODY_CHARS_098 = ORIGINAL_READ_MAX_BODY_CHARS_098 * ORIGINAL_READ_MAX_COUNT_098
    const val ORIGINAL_READ_MAX_TOTAL_BODY_BYTES_098 = 1_000_000

    fun safeUrl(value: String, httpsOnly: Boolean = false): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme in (if (httpsOnly) setOf("https") else setOf("https", "http")) &&
            uri.userInfo == null && host.contains('.') && !host.endsWith(".local") &&
            !host.endsWith(".localhost") && !host.endsWith(".internal") &&
            host != "localhost" && !host.matches(Regex("[0-9.]+")) && !host.contains(':') &&
            uri.port in setOf(-1, 443, 80) && value.length <= 2048
    }.getOrDefault(false)

    fun canonicalUrl(value: String): String = runCatching {
        val u = URI(value)
        val query = u.rawQuery?.split('&')?.filterNot {
            it.substringBefore('=').lowercase().let { key -> key.startsWith("utm_") || key in setOf("fbclid", "gclid") }
        }?.joinToString("&")?.ifBlank { null }
        val authority = u.host?.lowercase().orEmpty() + if (u.port >= 0) ":${u.port}" else ""
        val path = u.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
        "${u.scheme?.lowercase()}://$authority$path" + (query?.let { "?$it" } ?: "")
    }.getOrDefault(value)

    fun itemId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalUrl(url).toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    fun scopedContext(state: FeedState, module: FeedModule): List<ContextEntry> =
        contextForKinds(state, setOf(module.kind))

    /** Explicit About-you consent applies to future/disabled/custom modules too; old notes remain scoped. */
    fun contextForKinds(state: FeedState, kinds: Set<String>, limit: Int = 32): List<ContextEntry> {
        require(limit >= 0)
        return state.context.filter { it.confirmed && (PersonalProfile085.isGlobal(it) || (!it.global && it.scopes.any(kinds::contains))) }
            .sortedByDescending { PersonalProfile085.isGlobal(it) }.take(limit)
    }

    fun visible(state: FeedState, now: Long = System.currentTimeMillis()): List<FeedItem> {
        val enabled = state.modules.filter { it.enabled }.map { it.id }.toSet()
        val today = dateAt(now, state.timezone)
        return state.items.filter {
            it.moduleId in enabled && it.id !in state.hiddenIds &&
                FreshnessPolicy094.isActive(it, now) &&
                (it.kind != "horoscope" || dateAt(it.fetchedAtMillis, state.timezone) == today)
        }
            .sortedWith(compareByDescending<FeedItem> { it.relevance }.thenByDescending { it.publishedAtMillis.takeIf { t -> t > 0 } ?: it.fetchedAtMillis })
    }

    /** One useful item per module before repeats; freshness and source variety break the tie. */
    fun edition(state: FeedState, now: Long = System.currentTimeMillis()): List<FeedItem> =
        editionEligible(state, now, visible(state, now))

    /** Filter candidates before diversity selection; retain full state for personalization evidence. */
    internal fun editionEligible(state: FeedState, now: Long, eligible: List<FeedItem>): List<FeedItem> {
        // Small editions avoid snapshot setup; measured host fixtures show its break-even above five items.
        val ranking = if (eligible.size > 10) RecommendationPolicy.prepare(state, now) else null
        val scores = eligible.associate { it.id to (ranking?.score(it) ?: RecommendationPolicy.score(state, it, now)).total }
        fun score(item: FeedItem): Int = scores.getValue(item.id)
        fun source(item: FeedItem): String = runCatching { URI(item.sourceUrl).host?.removePrefix("www.") }
            .getOrNull().orEmpty().ifBlank { item.moduleId }
        val queues = eligible.sortedWith(
            compareByDescending<FeedItem> { score(it) }
                .thenByDescending { it.publishedAtMillis.takeIf { date -> date > 0 } ?: it.fetchedAtMillis }
                .thenBy { (it.id.hashCode().toLong() xor (state.editionSequence * 31)).and(Long.MAX_VALUE) }
                .thenBy { it.id },
        ).groupBy { it.moduleId }.mapValues { it.value.toMutableList() }.toMutableMap()
        val result = mutableListOf<FeedItem>()
        val recentSources = ArrayDeque<String>()
        while (queues.isNotEmpty()) {
            val remainingModules = queues.keys.toMutableSet()
            while (remainingModules.isNotEmpty()) {
                val candidates = remainingModules.mapNotNull { id ->
                    val queue = queues[id].orEmpty()
                    // Stay close to the strongest result while avoiding five items from one publisher.
                    val bestScore = queue.firstOrNull()?.let(::score) ?: return@mapNotNull null
                    queue.firstOrNull { source(it) !in recentSources && score(it) >= bestScore - 10 } ?: queue.first()
                }
                val selected = candidates.maxWithOrNull(
                    compareBy<FeedItem> { if (source(it) !in recentSources) 1 else 0 }
                        .thenBy { score(it) }
                        .thenBy { it.publishedAtMillis.takeIf { date -> date > 0 } ?: it.fetchedAtMillis },
                ) ?: break
                result += selected
                val queue = queues.getValue(selected.moduleId)
                queue.remove(selected)
                if (queue.isEmpty()) queues.remove(selected.moduleId)
                remainingModules.remove(selected.moduleId)
                recentSources.addLast(source(selected))
                if (recentSources.size > 3) recentSources.removeFirst()
            }
        }
        return result
    }

    fun merge(state: FeedState, incoming: List<FeedItem>, now: Long): FeedState {
        val existing = state.items.associateBy { it.id }.toMutableMap()
        incoming.forEach { item ->
            val old = existing[item.id]
            // Preserve a sourced AI explanation when a later plain RSS sync sees the same URL.
            val merged = if (old?.aiGenerated == true && !item.aiGenerated)
                old.copy(fetchedAtMillis = now, media = old.media ?: item.media,
                    temporal = if (canonicalUrl(old.sourceUrl) == canonicalUrl(item.sourceUrl)) item.temporal ?: old.temporal else old.temporal)
            else if (item.media == null && old?.media != null && canonicalUrl(old.sourceUrl) == canonicalUrl(item.sourceUrl))
                item.copy(media = old.media)
            else item
            // Preserve only our own local estimate across an exact public-content refresh.
            // A new incoming object cannot inject or refresh an estimate by carrying metadata.
            val reusableAiRelevance = old?.aiRelevance?.takeIf {
                fun publicFactual(candidate: FeedItem) = !candidate.aiGenerated && candidate.weather == null &&
                    candidate.kind !in setOf("weather", "horoscope") && candidate.contentType != "weather" && safeUrl(candidate.sourceUrl)
                publicFactual(old) && publicFactual(item) && AiRelevance094.contentDigest(old) == AiRelevance094.contentDigest(item)
            }
            val mergedDigest = AiSuggestedInteractions095.contentDigest(merged)
            val reusableInteractions = listOfNotNull(merged, old).firstOrNull {
                it.suggestedInteractions.isNotEmpty() &&
                    AiSuggestedInteractions095.contentDigest(it) == mergedDigest &&
                    runCatching { AiSuggestedInteractions095.validate(it) }.isSuccess
            }
            existing[item.id] = merged.copy(
                aiRelevance = reusableAiRelevance,
                suggestedInteractions = reusableInteractions?.suggestedInteractions.orEmpty(),
                suggestedInteractionsContentDigest = reusableInteractions?.suggestedInteractionsContentDigest.orEmpty(),
                suggestedInteractionsScopeProviderDigest = reusableInteractions?.suggestedInteractionsScopeProviderDigest.orEmpty(),
                suggestedInteractionsGeneratedAtMillis = reusableInteractions?.suggestedInteractionsGeneratedAtMillis ?: 0L,
            )
        }
        val retainedIds = state.savedIds + state.starredStories.map { it.itemId }
        val saved = existing.values.filter { it.id in retainedIds }
        val recent = existing.values.filter { it.id !in retainedIds }
            .sortedByDescending { it.publishedAtMillis.takeIf { n -> n > 0 } ?: it.fetchedAtMillis }.take(250)
        return state.copy(items = (saved + recent).distinctBy { it.id }, lastRefreshMillis = now)
    }

    fun dateAt(now: Long, timezone: String): String = Instant.ofEpochMilli(now)
        .atZone(runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }).toLocalDate().toString()

    fun quietAt(now: Long, state: FeedState): Boolean {
        val hour = Instant.ofEpochMilli(now).atZone(runCatching { ZoneId.of(state.timezone) }.getOrElse { ZoneId.systemDefault() }).hour
        val start = state.quietStartHour; val end = state.quietEndHour
        return if (start == end) false else if (start < end) hour in start until end else hour >= start || hour < end
    }

    fun validate(s: FeedState): FeedState {
        require(s.schemaVersion == 1)
        require(s.prefetchBatches in 1..3)
        RelevanceFeedback.validate(s.relevanceSignals)
        StarredStories.validate(s)
        require(s.speechRate.isFinite() && s.speechRate in 0.6f..1.5f && s.speechVoice.length <= 200)
        require(s.dismissedNotices.size <= 8 && s.dismissedNotices.all { (k, v) -> k in setOf("connection", "preferences") && v in 0..32_503_680_000_000L })
        require(s.name.length <= 80 && s.region.length <= 100 && s.timezone.length <= 80)
        if (s.timezone.isNotBlank()) ZoneId.of(s.timezone)
        require(s.modules.size <= ModuleCatalog.maxModules && s.modules.map { it.id }.distinct().size == s.modules.size)
        s.modules.forEach(::validateModule)
        require(s.context.size <= 80 && s.context.map { it.id }.distinct().size == s.context.size)
        s.context.forEach { require(it.id.length in 1..100 && it.text.length in 1..1000 && it.category.length <= 40 && it.origin.length <= 40 && it.scopes.all { scope -> scope in ModuleCatalog.kinds }) }
        s.context.forEach { require(!it.global || PersonalProfile085.isGlobal(it)) }
        require(s.items.size <= 1000 && s.items.map { it.id }.distinct().size == s.items.size)
        s.items.forEach(::validateItem)
        val originalReads = s.items.filter { it.contentType == "original_read" }
        require(originalReads.size <= ORIGINAL_READ_MAX_COUNT_098)
        require(originalReads.sumOf(::originalReadBodyChars) <= ORIGINAL_READ_MAX_TOTAL_BODY_CHARS_098)
        require(originalReads.sumOf(::originalReadBodyBytes) <= ORIGINAL_READ_MAX_TOTAL_BODY_BYTES_098)
        require(s.savedIds.size <= 500 && s.hiddenIds.size <= 1000)
        require(s.viewedAtMillis.size <= 1000 && s.viewedAtMillis.all { (id, at) -> id.length in 1..100 && at in 0..32_503_680_000_000L })
        require(s.editionSequence in 0..1_000_000_000L)
        ConversationLogic.validate(s.conversations)
        ConversationLogic.validateBriefs(s.briefs)
        require(s.pendingNotificationIds.size <= 1000 && s.pendingNotificationIds.all { it.length in 1..100 })
        require((s.savedIds + s.hiddenIds).all { it.length in 1..100 })
        AiProviders.validate(s.connection)
        require(s.aiPauseCode in setOf("", "chatgpt_limit", "chatgpt_permission", "chatgpt_ineligible", "chatgpt_policy", "chatgpt_capability", "chatgpt_reauth"))
        require(s.refreshHours in 1..24 && RequestBudget.isValidLimit(s.dailyRequestLimit))
        require(s.quietStartHour in 0..23 && s.quietEndHour in 0..23 && s.requestsToday >= 0)
        // Suggestion records are disposable derived cache. Old/corrupt/incomplete records are
        // dropped without making otherwise usable feed, profile, or conversation state unreadable.
        val cleanSuggestionSets = s.suggestionSets097.asReversed().asSequence()
            .take(64)
            .filter(AiSuggestionCache097::isValid)
            .distinctBy { AiSuggestionCache097.key(it.targetKind, it.targetId) }
            .take(32).toList().asReversed()
        val cleanDailySelection = s.personalDailySelection098?.takeIf(PersonalDailySelectionLogic098::isValidRecord)
        return if (cleanSuggestionSets == s.suggestionSets097 && cleanDailySelection == s.personalDailySelection098) s
        else s.copy(suggestionSets097 = cleanSuggestionSets, personalDailySelection098 = cleanDailySelection)
    }

    fun markViewed(state: FeedState, id: String, now: Long): FeedState {
        if (!state.readingHistoryEnabled || state.items.none { it.id == id }) return state
        // Rotation and quick back-and-forth navigation should not trigger repeated disk writes.
        state.viewedAtMillis[id]?.let { previous -> if (now - previous in 0 until 300_000L) return state }
        return state.copy(viewedAtMillis = (state.viewedAtMillis + (id to now)).entries
            .sortedByDescending { it.value }.take(1000).associate { it.toPair() })
    }

    fun validateModule(m: FeedModule) {
        require(m.id.length in 1..100 && m.kind in ModuleCatalog.kinds)
        require(m.title.length <= 80 && m.query.length <= 1000)
        require(m.sources.size <= 6 && m.sources.all { it.name.length in 1..80 && safeUrl(it.url, true) })
        require(m.zodiacSign.length <= 40)
        ModulePreferences.validate(m)
    }

    fun validateItem(i: FeedItem) {
        i.temporal?.let(TodayFeed::validate)
        i.aiRelevance?.let(AiRelevance094::validate)
        AiSuggestedInteractions095.validate(i)
        require(i.keyPoints.size <= 4 && i.keyPoints.all { it.isNotBlank() && it.length <= 400 })
        require(i.nextStep.length <= 800 && i.caveat.length <= 600)
        require(i.acquisition.isNotBlank() && i.acquisition.length <= 40 && i.acquisition.none { it.isISOControl() })
        require(i.authorship.isNotBlank() && i.authorship.length <= 40 && i.authorship.none { it.isISOControl() })
        require(i.sections.size <= 8 && i.sections.all {
            it.heading.length in 1..120 && it.heading.isNotBlank() && it.heading.none { c -> c.isISOControl() } &&
                it.body.length in 1..6000 && it.body.isNotBlank() && it.body.none { c -> c.isISOControl() && c !in setOf('\n', '\r', '\t') }
        })
        require(i.sections.sumOf { it.body.trim().length } <= ORIGINAL_READ_MAX_BODY_CHARS_098)
        require(i.furtherReading.size <= 5 && i.furtherReading.all { it.title.length <= 300 && it.title.none { c -> c.isISOControl() } && safeUrl(it.url) })
        require(i.id.length in 1..100 && i.moduleId.length in 1..100 && i.title.length in 1..300)
        require(i.summary.length <= 5000 && i.why.length <= 1200 && i.sourceName.length <= 200)
        require(i.kind in ModuleCatalog.kinds && i.relevance in 0..100 && i.deadline.length <= 100)
        require(i.citations.size <= 10 && i.citations.all { it.title.length <= 300 && safeUrl(it.url) })
        require(i.sourceUrl.isBlank() || safeUrl(i.sourceUrl))
        require(i.publishedAtMillis >= 0 && i.fetchedAtMillis >= 0)
        require(i.contentType in ModuleCatalog.contentTypes)
        when (i.contentType) {
            "original_read" -> require(isValidOriginalReadShape(i))
            else -> {
                require(i.acquisition != FeedAcquisition.ORIGINAL_READ)
                require(i.kind == "horoscope" || i.sourceUrl.isNotBlank())
                when (i.acquisition) {
                    FeedAcquisition.PUBLISHER_RSS, FeedAcquisition.PUBLIC_API -> require(i.sourceName.isNotBlank())
                    FeedAcquisition.AI_WEB_GROUNDED, FeedAcquisition.AI_SOURCE_GROUNDED -> {
                        require(i.aiGenerated && i.sourceName.isNotBlank() && i.sourceUrl.isNotBlank())
                        // Both new web research and cached-source AI transformations require direct evidence;
                        // further-reading suggestions are not evidence for factual claims.
                        require(i.citations.isNotEmpty())
                    }
                }
            }
        }
        i.media?.let { media ->
            require(safeUrl(media.url, httpsOnly = true) && safeUrl(media.sourceUrl, httpsOnly = true))
            require(media.credit.length <= 200 && media.credit.none { it.isISOControl() })
        }
        i.weather?.let { weather ->
            require(i.kind == "weather" && i.contentType == "weather")
            require(weather.temperatureC.isFinite() && weather.temperatureC in -100.0..70.0)
            require(weather.feelsLikeC.isFinite() && weather.feelsLikeC in -120.0..85.0)
            require(weather.precipitationChance in 0..100 && weather.weatherCode in 0..99)
            require(weather.location.length in 1..160 && weather.location.none { it.isISOControl() })
        }
    }

    /** Number of canonical section-body characters; headings/teaser never satisfy read length. */
    fun originalReadBodyChars(item: FeedItem): Int = item.sections.sumOf { it.body.trim().length }

    /** UTF-8 body budget is separate from the per-read character budget for the persisted store. */
    fun originalReadBodyBytes(item: FeedItem): Int = item.sections.sumOf { it.body.trim().toByteArray(Charsets.UTF_8).size }

    /**
     * Check an optional new/replacement read without changing state or evicting saved content. The
     * caller can skip only the read when this returns false and still commit source-backed items.
     */
    fun canAcceptOriginalRead(state: FeedState, candidate: FeedItem): Boolean {
        if (!runCatching { validateItem(candidate) }.isSuccess || !isValidOriginalReadShape(candidate)) return false
        if (state.items.count { it.id == candidate.id } > 1) return false
        val currentReads = state.items.filter { it.contentType == "original_read" }
        if (currentReads.any { !isValidOriginalReadShape(it) }) return false
        val afterReads = currentReads.filterNot { it.id == candidate.id } + candidate
        return afterReads.size <= ORIGINAL_READ_MAX_COUNT_098 &&
            afterReads.sumOf(::originalReadBodyChars) <= ORIGINAL_READ_MAX_TOTAL_BODY_CHARS_098 &&
            afterReads.sumOf(::originalReadBodyBytes) <= ORIGINAL_READ_MAX_TOTAL_BODY_BYTES_098
    }

    /**
     * Strict, non-recursive shape test shared with item-question validation. It deliberately does
     * not attempt to verify whether an AI citation was actually consulted; acquisition parsers do
     * that against their request's source metadata before constructing a record.
     */
    fun isValidOriginalReadShape(item: FeedItem): Boolean =
        item.contentType == "original_read" &&
            item.acquisition == FeedAcquisition.ORIGINAL_READ && item.authorship == FeedAuthorship.AI &&
            item.aiGenerated && item.kind in ModuleCatalog.kinds && item.kind !in setOf("weather", "horoscope") && item.deadline.isBlank() &&
            item.title.isNotBlank() && item.title.length in 1..300 &&
            item.summary.isNotBlank() && item.summary.length <= 5_000 &&
            item.sourceUrl.isEmpty() && item.sourceName.isEmpty() && item.publishedAtMillis == 0L &&
            item.fetchedAtMillis in 1..32_503_680_000_000L && item.temporal == null && item.media == null && item.weather == null &&
            item.sections.size in 2..4 && item.sections.all {
                it.heading.length in 1..120 && it.heading.isNotBlank() && it.heading.none { c -> c.isISOControl() } &&
                    it.body.length in 1..6_000 && it.body.isNotBlank() &&
                    it.body.none { c -> c.isISOControl() && c !in setOf('\n', '\r', '\t') } && it.body.trim().length >= 180
            } &&
            item.citations.size <= 10 && item.citations.all { it.title.length <= 300 && safeUrl(it.url) } &&
            item.furtherReading.size <= 5 && item.furtherReading.all { it.title.length <= 300 && safeUrl(it.url) } &&
            originalReadBodyChars(item) in ORIGINAL_READ_MIN_BODY_CHARS_098..ORIGINAL_READ_MAX_BODY_CHARS_098
}
