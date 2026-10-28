package mebot.nguli.com.core

/** Display-only formatting. Never changes stored evidence or interprets markup as executable HTML. */
data class DetailKeyPoint(val text: String, val references: List<FeedCitation>) {
    companion object {
        fun parse(value: String): DetailKeyPoint {
            val input = value.take(4000)
            val references = linkedMapOf<String, FeedCitation>()
            fun reference(label: String, destination: String) {
                val url = destination.trim().removeSurrounding("<", ">").replace("&amp;", "&")
                if (FeedLogic.safeUrl(url, true)) references.putIfAbsent(FeedLogic.canonicalUrl(url), FeedCitation(label, url))
            }
            val rendered = StringBuilder()
            var index = 0
            while (index < input.length) {
                if (input[index] == '[') {
                    val close = input.indexOf(']', index + 1)
                    if (close > index && close + 1 < input.length && input[close + 1] == '(') {
                        val label = input.substring(index + 1, close)
                        var end = close + 2
                        var depth = 1
                        while (end < input.length && depth > 0 && !input[end].isWhitespace()) {
                            if (input[end] == '(') depth++
                            if (input[end] == ')') depth--
                            if (depth > 0) end++
                        }
                        val destination = input.substring(close + 2, end)
                        reference(label, destination)
                        rendered.append(label)
                        index = if (depth == 0) end + 1 else end
                        continue
                    }
                }
                rendered.append(input[index++])
            }
            var text = rendered.toString()
            // An autolink/raw address remains available below, without a long tracking URL in prose.
            val urls = Regex("""<?https?://[^\s<>"\]]+>?""")
            text = urls.replace(text) { match ->
                val raw = match.value.removePrefix("<").removeSuffix(">").replace("&amp;", "&")
                val url = RichContent.links(raw).firstOrNull()?.url
                if (url == null) match.value else {
                    reference("", url)
                    val suffix = raw.removePrefix(url)
                    java.net.URI(url).host.removePrefix("www.") + suffix
                }
            }
            text = text.replace(Regex("\\uE200[^\\uE201]*\\uE201"), "")
                .replace(Regex("【[^】]*(?:†|https?://)[^】]*】"), "")
                .replace(Regex("\\uE200cite(?:\\uE202[a-zA-Z0-9_-]+)*"), "")
                .replace(Regex("【[0-9]+†[^\\s】]+$"), "")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
                .replace(Regex("\\s+"), " ").trim()
                .replace(Regex("\\s+([,.;:!?])"), "$1")
                .replace(Regex("\\(\\s*\\)"), "").trim()
            return DetailKeyPoint(text, references.values.take(6))
        }
    }
}
