package mebot.nguli.com.feed

import kotlinx.serialization.json.*
import mebot.nguli.com.core.*
import java.time.Instant

/** Reads explicit publisher dates, not model prose, fetch time or metadata modification time. */
object PublisherTemporalParser {
    private val tags = Regex("<meta\\b[^>]{0,4096}>", RegexOption.IGNORE_CASE)
    private val attributes = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val scripts = Regex("<script\\b([^>]{0,4096})>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
    private val articleTypes = setOf("Article", "NewsArticle", "BlogPosting", "TechArticle", "VideoObject")

    fun parse(html: String, sourceUrl: String, now: Long): FeedTemporal? {
        if (!FeedLogic.safeUrl(sourceUrl, true)) return null
        val bounded = html.take(1024 * 1024)
        fun publication(raw: String?): FeedTemporal? {
            val value = raw?.takeIf { it.length <= 100 } ?: return null
            val time = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() ?: return null
            if (time <= 0 || time > now + TodayFeed.CLOCK_SKEW_MILLIS) return null
            return FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = time, verifiedAtMillis = now)
        }
        tags.findAll(bounded).take(200).forEach { match ->
            val attrs = attrs(match.value)
            if ((attrs["property"] ?: attrs["name"])?.lowercase() == "article:published_time")
                publication(attrs["content"])?.let { return it }
        }
        // Only a structured object explicitly bound to this canonical page qualifies.
        // Related stories, generic dateModified and crawl/update metadata never qualify.
        fun structured(value: JsonElement, depth: Int): FeedTemporal? {
            if (depth > 6) return null
            if (value is JsonArray) return value.take(30).firstNotNullOfOrNull { structured(it, depth + 1) }
            val obj = value as? JsonObject ?: return null
            val type = obj["@type"]
            val accepted = when (type) {
                is JsonPrimitive -> type.contentOrNull in articleTypes
                is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull in articleTypes }
                else -> false
            }
            val urls = listOfNotNull((obj["url"] as? JsonPrimitive)?.contentOrNull,
                (obj["@id"] as? JsonPrimitive)?.contentOrNull,
                (obj["mainEntityOfPage"] as? JsonPrimitive)?.contentOrNull,
                ((obj["mainEntityOfPage"] as? JsonObject)?.get("@id") as? JsonPrimitive)?.contentOrNull)
            if (accepted && urls.any { FeedLogic.safeUrl(it, true) && FeedLogic.canonicalUrl(it) == FeedLogic.canonicalUrl(sourceUrl) })
                publication((obj["datePublished"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
            return obj["@graph"]?.let { structured(it, depth + 1) }
        }
        scripts.findAll(bounded).take(20).forEach { match ->
            if (attrs(match.groupValues[1])["type"]?.lowercase()?.substringBefore(';') != "application/ld+json") return@forEach
            val raw = match.groupValues[2].takeIf { it.length <= 256 * 1024 } ?: return@forEach
            val json = runCatching { FeedJson.parseToJsonElement(raw) }.getOrNull() ?: return@forEach
            structured(json, 0)?.let { return it }
        }
        return null
    }

    private fun attrs(tag: String): Map<String, String> = attributes.findAll(tag).take(50).associate { match ->
        match.groupValues[1].lowercase() to android.text.Html.fromHtml(
            match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty(),
            android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
