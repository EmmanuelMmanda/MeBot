package app.mynguli.core

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

object FeedLogic {
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
        state.context.filter { it.confirmed && module.kind in it.scopes }.take(32)

    fun visible(state: FeedState, now: Long = System.currentTimeMillis()): List<FeedItem> {
        val enabled = state.modules.filter { it.enabled }.map { it.id }.toSet()
        val today = dateAt(now, state.timezone)
        return state.items.filter {
            it.moduleId in enabled && it.id !in state.hiddenIds &&
                (it.kind != "horoscope" || dateAt(it.fetchedAtMillis, state.timezone) == today)
        }
            .sortedWith(compareByDescending<FeedItem> { it.relevance }.thenByDescending { it.publishedAtMillis.takeIf { t -> t > 0 } ?: it.fetchedAtMillis })
    }

    /** One useful item per module before repeats; freshness and source variety break the tie. */
    fun edition(state: FeedState, now: Long = System.currentTimeMillis()): List<FeedItem> {
        val eligible = visible(state, now)
        val scores = eligible.associate { it.id to RecommendationPolicy.score(state, it, now).total }
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
            existing[item.id] = if (old?.aiGenerated == true && !item.aiGenerated)
                old.copy(fetchedAtMillis = now, media = old.media ?: item.media)
            else if (item.media == null && old?.media != null && canonicalUrl(old.sourceUrl) == canonicalUrl(item.sourceUrl))
                item.copy(media = old.media)
            else item
        }
        val saved = existing.values.filter { it.id in state.savedIds }
        val recent = existing.values.filter { it.id !in state.savedIds }
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
        require(s.name.length <= 80 && s.region.length <= 100 && s.timezone.length <= 80)
        if (s.timezone.isNotBlank()) ZoneId.of(s.timezone)
        require(s.modules.size <= ModuleCatalog.maxModules && s.modules.map { it.id }.distinct().size == s.modules.size)
        s.modules.forEach(::validateModule)
        require(s.context.size <= 80 && s.context.map { it.id }.distinct().size == s.context.size)
        s.context.forEach { require(it.id.length in 1..100 && it.text.length in 1..1000 && it.category.length <= 40 && it.origin.length <= 40 && it.scopes.all { scope -> scope in ModuleCatalog.kinds }) }
        require(s.items.size <= 1000 && s.items.map { it.id }.distinct().size == s.items.size)
        s.items.forEach(::validateItem)
        require(s.savedIds.size <= 500 && s.hiddenIds.size <= 1000)
        require(s.viewedAtMillis.size <= 1000 && s.viewedAtMillis.all { (id, at) -> id.length in 1..100 && at in 0..32_503_680_000_000L })
        require(s.editionSequence in 0..1_000_000_000L)
        ConversationLogic.validate(s.conversations)
        ConversationLogic.validateBriefs(s.briefs)
        require(s.pendingNotificationIds.size <= 1000 && s.pendingNotificationIds.all { it.length in 1..100 })
        require((s.savedIds + s.hiddenIds).all { it.length in 1..100 })
        require(s.connection.mode in setOf("direct", "service", "chatgpt"))
        require(s.connection.model.matches(if (s.connection.mode == "chatgpt")
            Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") else Regex("[a-zA-Z0-9._-]{1,100}")))
        require(s.connection.serviceUrl.isBlank() || safeUrl(s.connection.serviceUrl, true))
        require(s.connection.accountId.length <= 200 && s.connection.accountId.none { it.isISOControl() })
        require(s.connection.mode == "chatgpt" || s.connection.accountId.isBlank())
        require(s.aiPauseCode in setOf("", "chatgpt_limit", "chatgpt_permission", "chatgpt_ineligible", "chatgpt_policy", "chatgpt_capability", "chatgpt_reauth"))
        require(s.refreshHours in 1..24 && RequestBudget.isValidLimit(s.dailyRequestLimit))
        require(s.quietStartHour in 0..23 && s.quietEndHour in 0..23 && s.requestsToday >= 0)
        return s
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
        require(i.keyPoints.size <= 4 && i.keyPoints.all { it.isNotBlank() && it.length <= 400 })
        require(i.nextStep.length <= 800 && i.caveat.length <= 600)
        require(i.id.length in 1..100 && i.moduleId.length in 1..100 && i.title.length in 1..300)
        require(i.summary.length <= 5000 && i.why.length <= 1200 && i.sourceName.length <= 200)
        require(i.kind in ModuleCatalog.kinds && i.relevance in 0..100 && i.deadline.length <= 100)
        require(i.citations.size <= 10 && i.citations.all { it.title.length <= 300 && safeUrl(it.url) })
        require(i.sourceUrl.isBlank() || safeUrl(i.sourceUrl))
        require(i.kind == "horoscope" || i.sourceUrl.isNotBlank())
        require(i.publishedAtMillis >= 0 && i.fetchedAtMillis >= 0)
        require(i.contentType in ModuleCatalog.contentTypes)
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
}
