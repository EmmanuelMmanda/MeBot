package mebot.nguli.com.core

/** Independent from transient network/UI state: no empty-first-frame or stream race. */
object DetailPresentation {
    fun introduction(summary: String, maxChars: Int = 420): String {
        require(maxChars >= 40)
        val text = summary.trim()
        if (text.length <= maxChars) return text
        val prefix = text.take(maxChars)
        val sentence = maxOf(prefix.lastIndexOf(". "), prefix.lastIndexOf("! "), prefix.lastIndexOf("? "))
        val end = if (sentence >= maxChars / 2) sentence + 1 else prefix.lastIndexOf(' ').takeIf { it > maxChars / 2 } ?: maxChars
        return prefix.take(end).trimEnd() + "…"
    }
}
