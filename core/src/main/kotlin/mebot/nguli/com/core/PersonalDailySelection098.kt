package mebot.nguli.com.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure, bounded daily recommendation projection. It never changes an item's source dates. */
object PersonalDailySelectionLogic098 {
    const val ALGORITHM_VERSION = 2
    const val MAX_SELECTION_SIZE = 5
    const val MAX_CANDIDATES = 256
    private const val MAX_TIMESTAMP = 32_503_680_000_000L
    private val digestPattern = Regex("[0-9a-f]{64}")

    /**
     * Retain current targets in order. Only explicit refresh reranks a still-valid selection; new
     * candidates, saves, and passive reading do not reshuffle it. Invalid targets leave FeedState
     * content untouched and are replaced from the bounded current ranking when slots are open.
     */
    fun ensure(state: FeedState, now: Long, refresh: Boolean = false): FeedState {
        require(now in 0..MAX_TIMESTAMP)
        val zone = effectiveZone(state.timezone)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val eligible = eligibleItems(state, now)
        val byId = eligible.associateBy { it.id }
        val ranked = FeedLogic.editionEligible(state, now, boundedCandidates(eligible))
        val previous = state.personalDailySelection098
        val reusable = previous?.takeIf {
            !refresh && isValidRecord(it) && it.selectionDate == date && it.timezone == zone.id &&
                it.algorithmVersion == ALGORITHM_VERSION
        }
        val retained = reusable?.itemIds?.zip(reusable.itemRevisions)?.mapNotNull { (id, revision) ->
            val item = byId[id] ?: return@mapNotNull null
            item.takeIf { itemRevision(state, it, date, zone.id) == revision }
        }.orEmpty()
        val survivors = enforceReadLimit(retained, eligible.any { !isOriginalRead(it) })
        val selected = if (reusable == null) balancedSelection(ranked) else fillSelection(survivors, ranked)
        val ids = selected.map { it.id }
        val revisions = selected.map { itemRevision(state, it, date, zone.id) }
        val inputDigest = digestRecord(date, zone.id, ALGORITHM_VERSION, ids, revisions)

        if (!refresh && reusable != null && ids == reusable.itemIds && revisions == reusable.itemRevisions &&
            inputDigest == reusable.inputDigest) return state

        val record = PersonalDailySelection098(
            selectionDate = date,
            timezone = zone.id,
            algorithmVersion = ALGORITHM_VERSION,
            itemIds = ids,
            itemRevisions = revisions,
            inputDigest = inputDigest,
            selectedAtMillis = now,
        )
        return state.copy(personalDailySelection098 = record)
    }

    /** Resolve valid survivors in persisted order; one stale ID never blanks the whole selection. */
    fun resolve(state: FeedState, now: Long): List<FeedItem> {
        if (now !in 0..MAX_TIMESTAMP) return emptyList()
        val record = state.personalDailySelection098?.takeIf(::isValidRecord) ?: return emptyList()
        val zone = effectiveZone(state.timezone)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        if (record.selectionDate != date || record.timezone != zone.id || record.algorithmVersion != ALGORITHM_VERSION) return emptyList()
        val eligible = eligibleItems(state, now).associateBy { it.id }
        val selected = record.itemIds.zip(record.itemRevisions).mapNotNull { (id, revision) ->
            val item = eligible[id] ?: return@mapNotNull null
            item.takeIf { itemRevision(state, it, date, zone.id) == revision }
        }
        return enforceReadLimit(selected, eligible.values.any { !isOriginalRead(it) })
    }

    /** Derived-cache corruption/version changes are ignored without rejecting user feed data. */
    fun isValidRecord(record: PersonalDailySelection098): Boolean =
        record.algorithmVersion == ALGORITHM_VERSION &&
            runCatching { LocalDate.parse(record.selectionDate).toString() == record.selectionDate }.getOrDefault(false) &&
            record.timezone.length in 1..80 && runCatching { ZoneId.of(record.timezone) }.isSuccess &&
            record.itemIds.size <= MAX_SELECTION_SIZE && record.itemIds.size == record.itemRevisions.size &&
            record.itemIds.all { it.length in 1..100 } && record.itemIds.distinct().size == record.itemIds.size &&
            record.itemRevisions.all(digestPattern::matches) &&
            record.selectedAtMillis in 0..MAX_TIMESTAMP && digestPattern.matches(record.inputDigest) &&
            record.inputDigest == digestRecord(
                record.selectionDate, record.timezone, record.algorithmVersion, record.itemIds, record.itemRevisions,
            )

    private fun eligibleItems(state: FeedState, now: Long): List<FeedItem> =
        FeedLogic.visible(state, now).filter { item ->
            val moduleKind = state.modules.firstOrNull { it.id == item.moduleId }?.kind.orEmpty()
            val utilityKind = item.kind.lowercase() in setOf("weather", "horoscope") ||
                moduleKind.lowercase() in setOf("weather", "horoscope") ||
                item.contentType == "weather" || item.weather != null
            !utilityKind && if (item.contentType == "original_read") FeedLogic.isValidOriginalReadShape(item)
            else FeedLogic.safeUrl(item.sourceUrl)
        }

    /** Keep ranking bounded while ensuring the pool doesn't miss every source-backed candidate. */
    private fun boundedCandidates(eligible: List<FeedItem>): List<FeedItem> {
        if (eligible.size <= MAX_CANDIDATES) return eligible
        val bounded = eligible.take(MAX_CANDIDATES)
        if (bounded.any { !isOriginalRead(it) }) return bounded
        val firstSource = eligible.firstOrNull { !isOriginalRead(it) } ?: return bounded
        return bounded.dropLast(1) + firstSource
    }

    /** Existing FeedLogic ranking supplies module/source diversity; Reads get at most one slot. */
    private fun balancedSelection(ranked: List<FeedItem>): List<FeedItem> {
        val reads = ranked.filter(::isOriginalRead)
        val sources = ranked.filterNot(::isOriginalRead)
        if (reads.isEmpty() || sources.isEmpty()) return ranked.take(MAX_SELECTION_SIZE)

        val sourceCount = minOf(4, sources.size)
        val readCount = minOf(1, reads.size)
        val chosen = mutableListOf<FeedItem>()
        var sourceIndex = 0
        var readIndex = 0
        while (chosen.size < MAX_SELECTION_SIZE && (sourceIndex < sourceCount || readIndex < readCount)) {
            if (sourceIndex < sourceCount) chosen += sources[sourceIndex++]
            if (readIndex < readCount && chosen.size < MAX_SELECTION_SIZE) chosen += reads[readIndex++]
        }
        return chosen
    }

    /** Fill holes after preserving all valid survivors and their order. */
    private fun fillSelection(survivors: List<FeedItem>, ranked: List<FeedItem>): List<FeedItem> {
        if (survivors.isEmpty()) return balancedSelection(ranked)
        val sourceAvailable = ranked.any { !isOriginalRead(it) }
        val result = enforceReadLimit(survivors, sourceAvailable).take(MAX_SELECTION_SIZE).toMutableList()
        val selectedIds = result.mapTo(HashSet()) { it.id }
        val remaining = ranked.filterNot { it.id in selectedIds }
        fun appendFirst(predicate: (FeedItem) -> Boolean) {
            if (result.size < MAX_SELECTION_SIZE) remaining.firstOrNull(predicate)?.let {
                if (sourceAvailable && isOriginalRead(it) && result.any(::isOriginalRead)) return
                result += it
                selectedIds += it.id
            }
        }
        if (sourceAvailable && result.none(::isOriginalRead)) appendFirst(::isOriginalRead)
        remaining.forEach {
            if (result.size < MAX_SELECTION_SIZE && selectedIds.add(it.id) &&
                (!sourceAvailable || !isOriginalRead(it) || result.none(::isOriginalRead))) result += it
        }
        return result
    }

    /** Guard the display boundary too, including imported or hand-edited derived cache records. */
    private fun enforceReadLimit(items: List<FeedItem>, sourceAvailable: Boolean): List<FeedItem> {
        if (!sourceAvailable) return items
        var keptRead = false
        return items.filter { item ->
            if (!isOriginalRead(item)) true
            else if (keptRead) false else { keptRead = true; true }
        }
    }

    private fun isOriginalRead(item: FeedItem): Boolean =
        item.contentType == "original_read" && FeedLogic.isValidOriginalReadShape(item)

    private fun itemRevision(state: FeedState, item: FeedItem, date: String, timezone: String): String {
        val module = state.modules.firstOrNull { it.id == item.moduleId && it.enabled }
            ?: return digest(listOf("missing-module", item.id, date, timezone))
        val parts = mutableListOf(
            "mebot-personal-daily-item-revision-v1", date, timezone,
            AiSuggestedInteractions095.contentDigest(item), item.id, item.moduleId, item.kind,
            item.relevance.toString(), item.publishedAtMillis.toString(), item.fetchedAtMillis.toString(),
            item.deadline, item.contentType, item.acquisition, item.authorship, item.aiGenerated.toString(),
            item.temporal?.provenance?.name.orEmpty(), item.temporal?.verifiedAtMillis?.toString().orEmpty(),
            item.temporal?.publishedAtMillis?.toString().orEmpty(), item.temporal?.substantiveUpdatedAtMillis?.toString().orEmpty(),
            item.temporal?.validFromMillis?.toString().orEmpty(), item.temporal?.validUntilMillis?.toString().orEmpty(),
            item.temporal?.relevanceDate.orEmpty(), item.temporal?.sourceTimezone.orEmpty(),
            item.temporal?.relevanceKind?.name.orEmpty(), module.id, module.kind, module.title, module.query,
            module.enabled.toString(), module.alerts.toString(), module.zodiacSign,
            RegionalDefaults.region(state), ModuleCatalog.discoveryQuery(module), ModuleDefaults.requestContext(module),
            state.connection.mode, state.connection.model, state.connection.serviceUrl, state.connection.accountId,
            state.connection.webSearch.toString(), state.connection.allowLocalHttp.toString(),
        )
        module.sources.forEach { parts += listOf("source", it.name, FeedLogic.canonicalUrl(it.url)) }
        module.preferences.toSortedMap().forEach { (key, value) -> parts += listOf("preference", key, value) }
        FeedLogic.scopedContext(state, module).forEach { entry ->
            parts += listOf("context", entry.id, entry.text, entry.category, entry.origin, entry.global.toString(), entry.scopes.sorted().joinToString(","))
        }
        item.citations.forEach { parts += listOf("evidence", it.title, FeedLogic.canonicalUrl(it.url)) }
        item.furtherReading.forEach { parts += listOf("further-reading", it.title, FeedLogic.canonicalUrl(it.url)) }
        item.aiRelevance?.let { estimate ->
            parts += listOf("ai-fit", estimate.fitEstimate.toString(), estimate.assessedAtMillis.toString(), estimate.contentDigest, estimate.scopeProviderDigest)
        }
        item.media?.let { media -> parts += listOf("media", media.url, media.sourceUrl, media.credit) }
        item.weather?.let { weather ->
            parts += listOf("weather", weather.temperatureC.toString(), weather.feelsLikeC.toString(),
                weather.precipitationChance.toString(), weather.weatherCode.toString(), weather.location)
        }
        return digest(parts)
    }

    private fun digestRecord(date: String, timezone: String, version: Int, ids: List<String>, revisions: List<String>): String {
        val parts = mutableListOf("mebot-personal-daily-selection-record-v1", date, timezone, version.toString())
        ids.indices.forEach { index -> parts += listOf("item", ids[index], revisions[index]) }
        return digest(parts)
    }

    private fun digest(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> parts.forEach { value ->
            val encoded = value.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        } }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun effectiveZone(value: String): ZoneId = runCatching { ZoneId.of(value) }.getOrElse { ZoneId.systemDefault() }
}
