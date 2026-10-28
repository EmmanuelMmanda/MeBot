package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class DetailKeyPointTest {
    @Test fun markdownKeepsMeaningAndMovesSafeReferenceToSecondaryLink() {
        val point = DetailKeyPoint.parse("Use **the toolkit** from [the guide](https://example.org/a_(guide)?x=1&amp;y=2).")
        assertEquals("Use the toolkit from the guide.", point.text)
        assertEquals("https://example.org/a_(guide)?x=1&y=2", point.references.single().url)
        assertEquals("the guide", point.references.single().title)
    }
    @Test fun malformedMissingClosePreservesFollowingWords() {
        val point = DetailKeyPoint.parse("Read [eligibility](https://example.org/rules before applying tomorrow.")
        assertEquals("Read eligibility before applying tomorrow.", point.text)
        assertEquals("https://example.org/rules", point.references.single().url)
    }
    @Test fun unsafeDestinationsNeverBecomeClickableButLabelsSurvive() {
        val point = DetailKeyPoint.parse("[Apply](javascript:alert(1)) or [file](file:///private/a) or [local](https://127.0.0.1/x).")
        assertEquals("Apply or file or local.", point.text)
        assertTrue(point.references.isEmpty())
    }
    @Test fun citationsAndRawAddressesDoNotLeakMarkupOrEatFacts() {
        val point = DetailKeyPoint.parse("Costs fell 12% [2025]. \uE200cite\uE202turn0search0\uE201 See https://example.org/report.")
        assertEquals("Costs fell 12% [2025]. See example.org.", point.text)
        assertEquals("https://example.org/report", point.references.single().url)
    }
    @Test fun plainContentAndNumbersRemainUnchanged() {
        val text = "Bei ni TSh 25,000; tumia [A] kwa hatua 2 (si 3)."
        assertEquals(text, DetailKeyPoint.parse(text).text)
    }
    @Test fun rawOnlyLinkRemainsAvailableAndRepeatedReferencesDeduplicate() {
        val point = DetailKeyPoint.parse("<https://example.org/file.pdf> [PDF](https://example.org/file.pdf)")
        assertEquals("example.org PDF", point.text)
        assertEquals(1, point.references.size)
    }
    @Test fun rawEntityQueryKeepsOnlyReadableHostAndPunctuation() {
        val point = DetailKeyPoint.parse("See <https://example.org/report?a=1&amp;b=2>.")
        assertEquals("See example.org.", point.text)
        assertEquals("https://example.org/report?a=1&b=2", point.references.single().url)
    }
    @Test fun missingCitationTerminatorDoesNotSwallowFollowingProse() {
        val point = DetailKeyPoint.parse("Evidence \uE200cite\uE202turn0search0 still needs checking.")
        assertEquals("Evidence still needs checking.", point.text)
    }

}
