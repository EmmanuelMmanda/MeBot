package mebot.nguli.com.core

/** Editable regional discovery defaults, not a claim about nationality or precise location. */
object RegionalDefaults {
    fun isTanzania(state: FeedState): Boolean {
        val region = state.region.trim().lowercase()
        return region == "tz" || region.contains("tanzania") ||
            region in setOf("dar es salaam", "dodoma", "arusha", "mwanza", "mbeya", "tanga", "morogoro", "zanzibar") ||
            (region.isBlank() && state.timezone == "Africa/Dar_es_Salaam")
    }
    fun region(state: FeedState): String = state.region.ifBlank { if (isTanzania(state)) "Tanzania" else "" }
    fun guidance(state: FeedState, module: FeedModule): String = if (!isTanzania(state)) "" else
        "Regional discovery focus: Tanzania. This is an editable default, not proof of nationality, eligibility or exact location. " +
        "Prefer useful Tanzanian and East African developments within this topic; include global material when it has practical local relevance. " +
        "Respect explicit preferences. Verify work and study eligibility and dates at the employer, university or programme source. Never invent listings or assume qualification. " +
        "Keep a variety of relevant sources and options; do not replace personal relevance with popularity. Topic: " + module.kind

    private val publisherDefaults = setOf("https://openai.com/news/rss.xml", "https://github.blog/feed/", "https://blog.google/technology/ai/rss/")
    private val localNews = listOf(FeedSource("Daily News Tanzania", "https://dailynews.co.tz/feed/"))
    private val swahiliNews = FeedSource("BBC News Swahili", "https://feeds.bbci.co.uk/swahili/rss.xml")
    private val localBusiness = FeedSource("Daily News Tanzania", "https://dailynews.co.tz/category/business/feed/")
    fun sources(state: FeedState, module: FeedModule, language: String): List<FeedSource> {
        if (!isTanzania(state) || module.sources.any { it.url !in publisherDefaults }) return module.sources
        return when (module.kind) {
            "news" -> if (language.startsWith("sw")) listOf(swahiliNews) + localNews else localNews + swahiliNews
            "business" -> listOf(localBusiness)
            "technology", "github" -> ModuleCatalog.defaults.first { it.kind == "news" }.sources
            else -> module.sources
        }
    }
    fun useTechnicalNewsAdapter(state: FeedState, module: FeedModule): Boolean =
        !isTanzania(state) || module.query.isNotBlank() &&
            RecommendationPolicy.terms(module.query).any { it in setOf("technology", "software", "programming", "ai", "teknolojia", "programu") }
}
