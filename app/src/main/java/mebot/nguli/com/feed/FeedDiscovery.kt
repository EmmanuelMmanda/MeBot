package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/** Public data adapters. No profile notes, account tokens or private connectors are sent here. */
interface PublicDiscovery {
    suspend fun invalidate(moduleIds: Set<String>) = Unit
    suspend fun discover(state: FeedState, module: FeedModule, language: String, now: Long): List<FeedItem>
}

class PublicDiscoveryProvider(private val http: FeedHttp = SecureFeedHttp()) : PublicDiscovery {
    private data class Cached(val at: Long, val items: List<FeedItem>)
    private data class CacheKey(
        val moduleId: String, val kind: String, val query: String,
        val region: String, val timezone: String, val language: String,
        val preferences: Map<String, String>,
    )
    private val cache = linkedMapOf<CacheKey, Cached>()
    private val lock = Mutex()

    override suspend fun invalidate(moduleIds: Set<String>) { lock.withLock { cache.keys.removeAll { it.moduleId in moduleIds } } }

    override suspend fun discover(state: FeedState, module: FeedModule, language: String, now: Long): List<FeedItem> {
        if (module.kind !in supportedKinds) return emptyList()
        // Map equality is independent of entry order; take a snapshot so editing a
        // module cannot mutate a key already stored in the cache.
        val key = CacheKey(module.id, module.kind, module.query, state.region, state.timezone, language, module.preferences.toMap())
        val ttl = if (module.kind == "github") 2 * 60 * 60_000L else 30 * 60_000L
        lock.withLock { cache[key] }?.takeIf { now >= it.at && now - it.at < ttl }?.let { return it.items }
        val items = try {
            when (module.kind) {
                "weather" -> weather(state, module, language, now)
                "github" -> github(module, now)
                "news" -> if (RegionalDefaults.useTechnicalNewsAdapter(state, module)) hackerNews(module, now) else emptyList()
                else -> emptyList()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: FeedFailure) { throw failure }
        catch (_: Exception) { throw FeedFailure("invalid_response") }
        items.forEach { FeedLogic.validateItem(it) }
        lock.withLock {
            cache[key] = Cached(now, items)
            while (cache.size > 32) cache.remove(cache.keys.first())
        }
        return items
    }

    private suspend fun weather(state: FeedState, module: FeedModule, language: String, now: Long): List<FeedItem> {
        val region = ModulePreferences.location(module, state.region).trim()
        if (region.isBlank()) throw FeedFailure("region_required")
        val placeUrl = query("https://geocoding-api.open-meteo.com/v1/search", mapOf(
            "name" to region, "count" to "5", "language" to if (language == "sw") "sw" else "en", "format" to "json"))
        val places = json(http.request(placeUrl))["results"] as? JsonArray ?: throw FeedFailure("location_not_found")
        val candidates = places.mapNotNull { it as? JsonObject }
        // A per-module location may be away from home; the profile timezone must
        // not override that explicit destination when resolving the place.
        val matchingZone = candidates.takeIf { ModulePreferences.value(module, "location").isBlank() }
            ?.firstOrNull { state.timezone.isNotBlank() && it.text("timezone") == state.timezone }
        if (matchingZone == null && ',' !in region && candidates.map { it.text("country_code") }.distinct().size > 1)
            throw FeedFailure("location_not_found")
        val place = matchingZone ?: candidates.firstOrNull() ?: throw FeedFailure("location_not_found")
        val lat = place.number("latitude").takeIf { it in -90.0..90.0 } ?: throw FeedFailure("invalid_response")
        val lon = place.number("longitude").takeIf { it in -180.0..180.0 } ?: throw FeedFailure("invalid_response")
        val name = listOf(place.text("name"), place.text("country")).filter { it.isNotBlank() }.distinct().joinToString(", ").take(160)
        if (name.isBlank()) throw FeedFailure("invalid_response")
        val dataUrl = query("https://api.open-meteo.com/v1/forecast", mapOf(
            "latitude" to lat.toString(), "longitude" to lon.toString(),
            "current" to "temperature_2m,apparent_temperature,weather_code",
            "hourly" to "precipitation_probability", "forecast_days" to "1",
            "timezone" to "UTC", "timeformat" to "unixtime", "temperature_unit" to "celsius"))
        val data = json(http.request(dataUrl))
        val current = data["current"] as? JsonObject ?: throw FeedFailure("invalid_response")
        val validAt = Math.multiplyExact(current.long("time"), 1000L)
        if (validAt !in (now - 24 * 60 * 60_000L)..(now + 60 * 60_000L)) throw FeedFailure("invalid_response")
        val hourly = data["hourly"] as? JsonObject ?: throw FeedFailure("invalid_response")
        val hours = hourly["time"] as? JsonArray ?: throw FeedFailure("invalid_response")
        val rain = hourly["precipitation_probability"] as? JsonArray ?: throw FeedFailure("invalid_response")
        if (hours.size != rain.size || hours.isEmpty()) throw FeedFailure("invalid_response")
        val timestamps = hours.map {
            val time = (it as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..32_503_680_000L } ?: throw FeedFailure("invalid_response")
            Math.multiplyExact(time, 1000L)
        }
        val index = timestamps.indices.minByOrNull { index -> kotlin.math.abs(timestamps[index] - validAt) }
            ?: throw FeedFailure("invalid_response")
        if (kotlin.math.abs(timestamps[index] - validAt) > 60 * 60_000L) throw FeedFailure("invalid_response")
        val probability = (rain[index] as? JsonPrimitive)?.intOrNull ?: throw FeedFailure("invalid_response")
        val sourceUrl = query("https://open-meteo.com/en/docs", mapOf("latitude" to lat.toString(), "longitude" to lon.toString(), "current" to "temperature_2m,apparent_temperature,weather_code", "hourly" to "precipitation_probability"))
        val weather = FeedWeather(current.number("temperature_2m"), current.number("apparent_temperature"), probability,
            current.long("weather_code").takeIf { it in 0..99 }?.toInt() ?: throw FeedFailure("invalid_response"), name)
        return listOf(FeedItem(
            id = FeedLogic.itemId(sourceUrl), moduleId = module.id, title = name, summary = "",
            sourceName = "Open-Meteo", sourceUrl = sourceUrl, publishedAtMillis = validAt, fetchedAtMillis = now,
            kind = module.kind, relevance = 75, contentType = "weather", weather = weather,
            citations = listOf(FeedCitation("Open-Meteo", sourceUrl), FeedCitation("GeoNames", "https://www.geonames.org/")),
        ))
    }

    private suspend fun github(module: FeedModule, now: Long): List<FeedItem> {
        val since = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().minusDays(7)
        // Custom terms are owner-entered module configuration, never inferred private profile notes.
        val terms = module.query.takeIf { it != ModuleCatalog.preset(module.kind)?.defaultQuery }.orEmpty().trim().take(180)
        val focus = when (ModulePreferences.value(module, "focus")) {
            "tools" -> "topic:developer-tools"
            "libraries" -> "topic:library"
            "self_hosted" -> "topic:self-hosted"
            else -> ""
        }
        val codeLanguage = when (val language = ModulePreferences.value(module, "code_language")) {
            "python", "javascript", "php", "go", "rust" -> "language:$language"
            else -> ""
        }
        val search = listOf(terms, "created:>=$since", "fork:false", "archived:false", focus, codeLanguage)
            .filter { it.isNotBlank() }.joinToString(" ")
        val url = query("https://api.github.com/search/repositories", mapOf("q" to search, "sort" to "stars", "order" to "desc", "per_page" to "6"))
        val response = json(http.request(url))
        if (response["incomplete_results"]?.jsonPrimitive?.booleanOrNull == true) throw FeedFailure("provider_error")
        val entries = response["items"] as? JsonArray ?: throw FeedFailure("invalid_response")
        return entries.take(6).mapNotNull { element ->
            val repo = element as? JsonObject ?: return@mapNotNull null
            val link = repo.text("html_url")
            if (!FeedLogic.safeUrl(link, true) || URI(link).host != "github.com" || repo["private"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val title = repo.text("full_name").take(300)
            if (title.isBlank()) return@mapNotNull null
            val published = parseTime(repo.text("created_at"), now)
            FeedItem(FeedLogic.itemId(link), module.id, title, repo.text("description").take(1500),
                sourceName = "GitHub", sourceUrl = link, publishedAtMillis = published, fetchedAtMillis = now,
                kind = module.kind, relevance = 60, contentType = "repository", citations = listOf(FeedCitation(title, link)))
        }
    }

    private suspend fun hackerNews(module: FeedModule, now: Long): List<FeedItem> {
        val params = linkedMapOf("tags" to "story", "hitsPerPage" to "8",
            "numericFilters" to "created_at_i>${now / 1000 - 7 * 86_400},points>5")
        val terms = module.query.takeIf { it != ModuleCatalog.preset(module.kind)?.defaultQuery }.orEmpty().trim().take(180)
        if (terms.isNotBlank()) params["query"] = terms
        val url = query("https://hn.algolia.com/api/v1/search_by_date", params)
        val entries = json(http.request(url))["hits"] as? JsonArray ?: throw FeedFailure("invalid_response")
        return entries.take(8).mapNotNull { element ->
            val story = element as? JsonObject ?: return@mapNotNull null
            val title = clean(story.text("title")).take(300)
            val objectId = story.text("objectID").takeIf { it.matches(Regex("[0-9]{1,20}")) }
            val discussion = objectId?.let { "https://news.ycombinator.com/item?id=$it" }
            val link = story.text("url").takeIf { FeedLogic.safeUrl(it) } ?: discussion ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            // Algolia's created_at_i is the HN submission time, not publication of the
            // linked article. Keep the publisher date unknown instead of relabelling it.
            FeedItem(FeedLogic.itemId(link), module.id, title, clean(story.text("story_text")).take(1000),
                sourceName = if (URI(link).host == "news.ycombinator.com") "Hacker News" else URI(link).host.removePrefix("www.").take(200), sourceUrl = link,
                publishedAtMillis = 0, fetchedAtMillis = now, kind = module.kind, relevance = 40,
                citations = (listOf(FeedCitation(title, link)) + listOfNotNull(discussion?.let { FeedCitation("Hacker News", it) })).distinctBy { it.url })
        }
    }

    private fun json(value: String): JsonObject = FeedJson.parseToJsonElement(value) as? JsonObject ?: throw FeedFailure("invalid_response")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: throw FeedFailure("invalid_response")
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: throw FeedFailure("invalid_response")
    private fun query(base: String, params: Map<String, String>): String = base + "?" + params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    private fun parseTime(value: String, now: Long): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0).takeIf { it in 0..(now + 300_000) } ?: 0
    private fun clean(value: String) = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    companion object { val supportedKinds = setOf("weather", "github", "news") }
}
