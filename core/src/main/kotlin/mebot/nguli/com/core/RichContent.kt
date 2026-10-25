package mebot.nguli.com.core

import java.net.URI
import java.net.URLDecoder

/** Classifies an actual source URL. Never infers playable media from an article headline. */
object RichContent {
    /** Bounded links explicitly present in a response. Cards are not claims of source verification. */
    fun links(text: String): List<FeedCitation> {
        val input = text.take(24_000).split("```").filterIndexed { index, _ -> index % 2 == 0 }.joinToString("\n")
        val found = linkedMapOf<String, FeedCitation>()
        val markdown = Regex("""\[([^]\n]{0,300})]\((https?://(?:[^\s()]|\([^\s()]*\))+)\)""")
        markdown.findAll(input).forEach { match ->
            val url = match.groupValues[2].replace("&amp;", "&")
            if (FeedLogic.safeUrl(url, true)) found.putIfAbsent(FeedLogic.canonicalUrl(url), FeedCitation(match.groupValues[1], url))
        }
        // Ignore fenced code: examples are not resource recommendations.
        val prose = input
        Regex("""https?://[^\s<>"\]]+""").findAll(prose).forEach { match ->
            var url = match.value.trimEnd('.', ',', ';', ':', '!', '?').replace("&amp;", "&")
            while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
            if (FeedLogic.safeUrl(url, true)) found.putIfAbsent(FeedLogic.canonicalUrl(url), FeedCitation("", url))
        }
        return found.values.take(12)
    }

    /** HTTPS documents are previewed locally, never uploaded to a third-party document viewer. */
    fun isPdf(url: String): Boolean = DownloadResource.from(url)?.extension == "pdf"

    fun youtubeId(url: String): String? = runCatching {
        if (!FeedLogic.safeUrl(url, true)) return null
        val u = URI(url); val host = u.host.lowercase().removePrefix("www.").removePrefix("m.")
        val id = when (host) {
            "youtu.be" -> u.path.trim('/').substringBefore('/')
            "youtube.com", "youtube-nocookie.com" -> when {
                u.path == "/watch" -> u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
                u.path.startsWith("/shorts/") || u.path.startsWith("/embed/") || u.path.startsWith("/live/") -> u.path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }.getOrNull()
    fun type(url: String): String {
        if (!FeedLogic.safeUrl(url, true)) return "link"
        if (youtubeId(url) != null) return "youtube"
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return when {
            listOf(".pdf", ".docx", ".xlsx", ".pptx", ".txt", ".csv").any(path::endsWith) -> "document"
            listOf(".jpg", ".jpeg", ".png", ".webp").any(path::endsWith) -> "image"
            listOf(".mp3", ".m4a", ".ogg", ".wav").any(path::endsWith) -> "audio"
            listOf(".mp4", ".webm").any(path::endsWith) -> "video"
            else -> "link"
        }
    }
}
