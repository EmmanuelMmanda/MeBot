package mebot.nguli.com.ui.feed

/** Small, deliberately bounded Markdown reader for assistant text. It does not load remote HTML. */
internal sealed interface RichMarkdownBlock {
    data class Heading(val level: Int, val text: String) : RichMarkdownBlock
    data class Paragraph(val text: String) : RichMarkdownBlock
    data class ListItem(val indent: Int, val marker: String, val text: String, val ordered: Boolean) : RichMarkdownBlock
    data class Code(val language: String, val text: String) : RichMarkdownBlock
    data class Table(val rows: List<List<String>>) : RichMarkdownBlock
}

internal object RichMarkdown {
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val list = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.+)$")
    private val tableRuleCell = Regex("^:?-{3,}:?$")

    fun parse(source: String): List<RichMarkdownBlock> {
        val lines = source.lineSequence().toList()
        val result = mutableListOf<RichMarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.matches(Regex("(?:---+|___+|\\*\\*\\*+)"))) {
                index++
                continue
            }

            if (trimmed.startsWith("```")) {
                val language = trimmed.removePrefix("```").trim().take(48)
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code += lines[index]
                    index++
                }
                if (index < lines.size) index++
                result += RichMarkdownBlock.Code(language, code.joinToString("\n"))
                continue
            }

            val next = lines.getOrNull(index + 1)
            if (raw.contains('|') && next != null && isTableRule(next)) {
                val rows = mutableListOf<List<String>>()
                rows += splitTableRow(raw)
                index += 2
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                    rows += splitTableRow(lines[index])
                    index++
                }
                val width = rows.maxOfOrNull { it.size } ?: 0
                result += RichMarkdownBlock.Table(rows.map { row -> row + List((width - row.size).coerceAtLeast(0)) { "" } })
                continue
            }

            val headingMatch = heading.matchEntire(raw)
            if (headingMatch != null) {
                result += RichMarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2])
                index++
                continue
            }

            val listMatch = list.matchEntire(raw)
            if (listMatch != null) {
                val indentWidth = listMatch.groupValues[1].replace("\t", "    ").length
                result += RichMarkdownBlock.ListItem(indentWidth / 2, listMatch.groupValues[2], listMatch.groupValues[3],
                    listMatch.groupValues[2].firstOrNull()?.isDigit() == true)
                index++
                continue
            }

            // Adjacent prose lines are one paragraph. Stop before any recognized block boundary.
            val paragraph = mutableListOf(raw.trim())
            index++
            while (index < lines.size) {
                val line = lines[index]
                val lineTrimmed = line.trim()
                if (lineTrimmed.isBlank() || lineTrimmed.startsWith("```") || heading.matches(line) ||
                    list.matches(line) || lineTrimmed.matches(Regex("(?:---+|___+|\\*\\*\\*+)")) ||
                    (line.contains('|') && lines.getOrNull(index + 1)?.let(::isTableRule) == true)) break
                paragraph += line.trim()
                index++
            }
            result += RichMarkdownBlock.Paragraph(paragraph.joinToString(" "))
        }
        return result
    }

    /** A read-aloud rendition: structural punctuation is spoken naturally, Markdown syntax is not. */
    fun plainText(source: String): String = parse(source).joinToString("\n\n") { block ->
        when (block) {
            is RichMarkdownBlock.Heading -> inlinePlainText(block.text)
            is RichMarkdownBlock.Paragraph -> inlinePlainText(block.text)
            is RichMarkdownBlock.ListItem -> "${block.marker.takeIf { block.ordered }?.let { "$it " }.orEmpty()}${inlinePlainText(block.text)}"
            is RichMarkdownBlock.Code -> block.text
            is RichMarkdownBlock.Table -> block.rows.joinToString("\n") { row -> row.joinToString(". ") { inlinePlainText(it) }.trim() }
        }
    }.trim()

    private fun inlinePlainText(text: String): String {
        val images = Regex("!\\[([^]\\n]*)]\\(https?://[^\\s)]+\\)")
        val links = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)")
        return images.replace(text) { it.groupValues[1].ifBlank { "Image" } }
            .let { links.replace(it) { match -> match.groupValues[1] } }
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__"), "$1$2")
            .replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)"), "$1$2")
            .replace(Regex("~~(.+?)~~"), "$1")
            .replace(Regex("!\\[([^]]*)]\\((https?://[^\\s)]+)\\)"), "$1")
            .replace(Regex("https?://[^\\s<>]+"), "")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun isTableRule(raw: String): Boolean {
        val cells = splitTableRow(raw)
        return cells.size >= 2 && cells.all { tableRuleCell.matches(it.trim()) }
    }

    private fun splitTableRow(raw: String): List<String> {
        val protected = raw.trim().removePrefix("|").removeSuffix("|").replace("\\|", "\u0000")
        return protected.split('|').map { it.trim().replace("\u0000", "|") }
    }
}
