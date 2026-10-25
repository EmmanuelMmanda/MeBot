package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class RichContent083Test {
    @Test fun extractsMarkdownBareUrlsAndDeduplicatesWithReadableTitle() {
        val links = RichContent.links("Watch [the lesson](https://youtu.be/dQw4w9WgXcQ). Also https://youtu.be/dQw4w9WgXcQ and https://example.com/file.pdf.")
        assertEquals(2, links.size)
        assertEquals("the lesson", links.first().title)
        assertEquals("https://example.com/file.pdf", links.last().url)
        assertEquals("youtube", RichContent.type(links.first().url))
        assertTrue(RichContent.isPdf(links.last().url))
    }
    @Test fun ignoresCodeAndRejectsPrivateOrCredentialLinks() {
        val text = "```\n[example](https://example.com/code.pdf)\n```\nhttps://127.0.0.1/a.pdf https://user:pass@example.com/a.pdf javascript:alert(1)"
        assertTrue(RichContent.links(text).isEmpty())
    }
    @Test fun limitsResourceCardsAndDoesNotInferVideosFromHeadlines() {
        assertEquals(12, RichContent.links((1..30).joinToString(" ") { "https://example.com/$it" }).size)
        assertEquals("link", RichContent.type("https://example.com/watch-a-video"))
        assertFalse(RichContent.isPdf("https://example.com/about-pdf"))
    }
    @Test fun preservesBalancedParenthesesInResourceLinks() {
        val links = RichContent.links("[Reference](https://example.com/guide_(edition).pdf)")
        assertEquals(1, links.size)
        assertEquals("https://example.com/guide_(edition).pdf", links.single().url)
    }
    @Test fun keepsPdfQueryAndDecodesHtmlAmpersands() {
        val url = RichContent.links("[PDF](https://example.com/guide.pdf?download=1&amp;lang=sw)").single().url
        assertEquals("https://example.com/guide.pdf?download=1&lang=sw", url)
        assertTrue(RichContent.isPdf(url))
    }
}
