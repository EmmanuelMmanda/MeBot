package mebot.nguli.com.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

/** Explicit usefulness feedback, scoped to one module, independent from saves and reading history. */
@Serializable
data class RelevanceSignal(
    val itemId: String,
    val moduleId: String,
    val relevant: Boolean,
    val title: String,
    val terms: Set<String>,
    val updatedAtMillis: Long,
)

object RelevanceFeedback {
    const val MAX_SIGNALS = 500
    fun current(state: FeedState, item: FeedItem): Boolean? =
        state.relevanceSignals.lastOrNull { it.itemId == item.id && it.moduleId == item.moduleId }?.relevant

    /** One vote per item/module. A repeated vote is idempotent; undo removes it. */
    fun record(state: FeedState, itemId: String, relevant: Boolean?, now: Long): FeedState {
        require(now in 0..32_503_680_000_000L)
        val item = state.items.firstOrNull { it.id == itemId } ?: return state
        val old = state.relevanceSignals.lastOrNull { it.itemId == itemId && it.moduleId == item.moduleId }
        if (old?.relevant == relevant) return state
        val kept = state.relevanceSignals.filterNot { it.itemId == itemId && it.moduleId == item.moduleId }
        val signals = if (relevant == null) kept else kept + RelevanceSignal(item.id, item.moduleId, relevant,
            item.title.take(300), RecommendationPolicy.terms(item.title + " " + item.summary).filter { it.length in 1..120 }.take(32).toSet(), now)
        return state.copy(relevanceSignals = signals.sortedBy { it.updatedAtMillis }.takeLast(MAX_SIGNALS))
    }

    /** Bounded influence; evidence quality, timeliness and explicit preferences still matter. */
    fun adjustment(state: FeedState, item: FeedItem, now: Long): Int {
        val terms = RecommendationPolicy.terms(item.title + " " + item.summary)
        if (terms.isEmpty()) return 0
        val value = state.relevanceSignals.asSequence().filter { it.moduleId == item.moduleId }.sumOf { signal ->
            val ageDays = (now - signal.updatedAtMillis).coerceAtLeast(0) / 86_400_000.0
            val decay = exp(-ageDays / 60.0)
            val same = signal.itemId == item.id
            val overlap = terms.intersect(signal.terms).size
            val similarity = if (same) 1.0 else if (overlap < 2) 0.0 else
                (overlap / sqrt(terms.size.toDouble() * signal.terms.size.coerceAtLeast(1))).coerceIn(0.0, 1.0)
            (if (signal.relevant) 24.0 else -30.0) * similarity * decay
        }
        return value.toInt().coerceIn(-45, 35)
    }

    /** Only the selected module is sent to its provider, never another module's feedback. */
    fun forModule(state: FeedState, module: FeedModule): List<RelevanceSignal> =
        state.relevanceSignals.filter { it.moduleId == module.id }.sortedByDescending { it.updatedAtMillis }.take(12)

    fun validate(signals: List<RelevanceSignal>) {
        require(signals.size <= MAX_SIGNALS)
        require(signals.map { it.moduleId to it.itemId }.distinct().size == signals.size)
        signals.forEach {
            require(it.itemId.length in 1..100 && it.moduleId.length in 1..100 && it.title.length <= 300)
            require(it.terms.size <= 32 && it.terms.all { word -> word.length in 1..120 })
            require(it.updatedAtMillis in 0..32_503_680_000_000L)
        }
    }
}
