package app.mynguli.core
import org.junit.Assert.*
import org.junit.Test
class RegionalDefaultsTest {
    private val tz = FeedState(timezone = "Africa/Dar_es_Salaam")
    private val news = ModuleCatalog.defaults.first { it.kind == "news" }
    @Test fun countryFocusDoesNotInventPreciseCity() {
        assertEquals("Tanzania", RegionalDefaults.region(tz)); assertEquals("", tz.region)
    }
    @Test fun explicitOtherRegionWinsOverDeviceTimezone() {
        val s = tz.copy(region = "Nairobi, Kenya")
        assertFalse(RegionalDefaults.isTanzania(s)); assertEquals("Nairobi, Kenya", RegionalDefaults.region(s))
    }
    @Test fun publicSourcesAreLocalWithoutSendingContext() {
        assertTrue(RegionalDefaults.sources(tz, news, "en").any { it.url == "https://dailynews.co.tz/feed/" })
        assertEquals("BBC News Swahili", RegionalDefaults.sources(tz, news, "sw").first().name)
        assertFalse(RegionalDefaults.useTechnicalNewsAdapter(tz, news))
    }
    @Test fun customSourcesRemainUnchanged() {
        val edited = news.copy(sources = listOf(FeedSource("Mine", "https://example.com/rss")))
        assertEquals(edited.sources, RegionalDefaults.sources(tz, edited, "sw"))
    }
    @Test fun emptyRegionElsewhereHasNoTanzaniaClaim() {
        assertEquals("", RegionalDefaults.region(FeedState(timezone = "Europe/London")))
    }
    @Test fun explicitTechnicalInterestsRemainAvailable() {
        assertTrue(RegionalDefaults.useTechnicalNewsAdapter(tz, news.copy(query = "software releases")))
    }
}
