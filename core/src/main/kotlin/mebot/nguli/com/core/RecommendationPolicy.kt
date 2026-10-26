package mebot.nguli.com.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln

/** Local, explainable reranking of source-backed AI candidates; never an engagement score. */
object RecommendationPolicy {
    data class Score(val semantic: Int, val interest: Int, val feedback: Int, val freshness: Int,
        val novelty: Int, val deadline: Int, val exploration: Int) {
        val total: Int get() = semantic + interest + feedback + freshness + novelty + deadline + exploration
    }
    private val words = Regex("[\\p{L}\\p{N}]{3,}")
    private val stop = setOf("the", "and", "for", "with", "that", "this", "from", "about", "have", "want", "news", "like", "kwa", "hii", "hizi", "yangu", "habari", "katika", "na", "are", "can", "your")
    fun terms(text: String): Set<String> = words.findAll(text.lowercase()).map { it.value }.filter { it !in stop }.take(120).toSet()
    fun score(state: FeedState, item: FeedItem, now: Long): Score {
        val module = state.modules.firstOrNull { it.id == item.moduleId }
        val body = terms(item.title + " " + item.summary)
        val explicit = module?.let { terms(FeedLogic.scopedContext(state, it).joinToString(" ") { note -> note.text } + " " + it.query) }.orEmpty()
        val interest = (body.intersect(explicit).size * 3).coerceAtMost(15)
        fun affinity(ids: Set<String>): Int = state.items.asSequence().filter { it.id in ids && it.moduleId == item.moduleId && it.id != item.id }
            .take(40).map { saved -> body.intersect(terms(saved.title)).size.coerceAtMost(3) }.sum().coerceAtMost(5)
        // A deliberate save or hide outweighs a passive open. Reading history can be disabled.
        val feedback = (affinity(state.savedIds) * 2 - affinity(state.hiddenIds) * 3 + RelevanceFeedback.adjustment(state, item, now)).coerceIn(-50, 40)
        val ageHours = (now - (item.publishedAtMillis.takeIf { it > 0 } ?: item.fetchedAtMillis)).coerceAtLeast(0) / 3_600_000.0
        val halfLife = if (item.kind in setOf("news", "weather", "security", "business")) 24.0 else 168.0
        val freshness = (-ln(1 + ageHours / halfLife) * if(halfLife == 24.0) 12 else 6).toInt().coerceAtLeast(-60)
        val viewed = if (state.readingHistoryEnabled) state.viewedAtMillis[item.id] else null
        val novelty = if (viewed == null) 14 else when ((now-viewed).coerceAtLeast(0)) {
            in 0 until 21_600_000L -> -18
            in 0 until 86_400_000L -> -10
            else -> -3
        }
        val days = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(FeedLogic.dateAt(now, state.timezone)), LocalDate.parse(item.deadline)) }.getOrNull()
        val deadline = if (days != null && days in 0..3) 24 else 0
        // Only a small, deterministic exploration allowance; never replace strong relevance with random noise.
        val mixed = ((item.id.hashCode().toLong() * 0x9E3779B9L) xor (state.editionSequence * 0x85EBCA6BL)).ushr(8)
        val exploration = if (state.editionSequence > 0 && item.relevance >= 40 && viewed == null) (mixed % 7).toInt() else 0
        return Score(item.relevance.coerceIn(0, 100), interest, feedback, freshness, novelty, deadline, exploration)
    }
    /** Global About-you is explicitly shared with every question; other notes still require topic routing. */
    fun contextForQuestion(state: FeedState, question: String): List<ContextEntry> =
        FeedLogic.contextForKinds(state, questionKinds(state, question), 24)

    /** Shared native/service routing metadata; this is not permission to expand any note's scopes. */
    fun questionKinds(state: FeedState, question: String): Set<String> {
        if (question.lowercase().contains("what matters for me") || question.lowercase().contains("leo kuna nini kinachonihusu")) return emptySet()
        val q = terms(question)
        val aliases = mapOf(
            "music" to setOf("music", "song", "songs", "piano", "muziki", "nyimbo", "wimbo"),
            "entertainment" to setOf("movie", "movies", "film", "films", "series", "watch", "filamu", "nitazame", "burudani"),
            "weather" to setOf("weather", "rain", "forecast", "hali", "hewa", "mvua"),
            "technology" to setOf("technology", "software", "coding", "programming", "teknolojia", "programu"),
            "opportunities" to setOf("jobs", "job", "opportunities", "contracts", "kazi", "fursa"),
            "learning" to setOf("learn", "lesson", "course", "learning", "jifunze", "somo", "kujifunza"),
            "security" to setOf("security", "vulnerability", "vulnerabilities", "usalama"),
            "scholarships" to setOf("scholarship", "scholarships", "ufadhili"))
        return state.modules.filter { it.enabled && (aliases[it.kind].orEmpty().any(q::contains) || it.kind in q) }.map { it.kind }.toSet()
    }

    fun generalContext(state: FeedState): List<ContextEntry> = FeedLogic.contextForKinds(state, emptySet(), 24)

}

object LanguagePolicy {
    fun instruction(language: String): String = if (language.startsWith("sw"))
        "Jibu kwa Kiswahili cha kawaida kinachoeleweka Tanzania. Tumia sentensi fupi na maneno yanayotumika kila siku. Epuka tafsiri ya neno kwa neno, lugha ya kiofisi, misemo ya kujisifu na salamu ndefu. Eleza istilahi ngumu kwa kifupi. Hifadhi majina, namba, tarehe na maana ya chanzo. Usibuni ukweli."
    else "Use everyday English, short sentences and specific examples. Explain unfamiliar terms briefly. Avoid slogans, inflated claims and unnecessary introductions. Preserve source names, dates, numbers and uncertainty."
}
