package mebot.nguli.com.feed

import mebot.nguli.com.R

/** Trusted, localized prompts that are resolved by ID against the current story. */
internal data class LocalSuggestionOption095(
    val stableId: String,
    val labelRes: Int,
    val promptRes: Int,
)

/** Local fallbacks are bounded to the UI's two-suggestion rail and never trigger inference. */
internal fun localSuggestionOptions095(kind: String): List<LocalSuggestionOption095> = buildList {
    add(LocalSuggestionOption095("local:explain", R.string.feed_ask_explain, R.string.feed_local_prompt_explain))
    detailQuestionResources(kind).forEachIndexed { index, pair ->
        add(LocalSuggestionOption095("local:detail:$index", pair.first, pair.second))
    }
}

/** Resolve a known local ID using the item's current kind; never accepts a raw model prompt. */
internal fun localSuggestionPromptResource095(kind: String, stableId: String): Int? =
    localSuggestionOptions095(kind).firstOrNull { it.stableId == stableId }?.promptRes

private fun detailQuestionResources(kind: String): List<Pair<Int, Int>> = when (kind) {
    "opportunities", "scholarships" -> listOf(
        R.string.detail_q_eligibility to R.string.detail_prompt_eligibility,
        R.string.detail_q_prepare to R.string.detail_prompt_prepare,
    )
    "security" -> listOf(
        R.string.detail_q_affected to R.string.detail_prompt_affected,
        R.string.detail_q_fix to R.string.detail_prompt_fix,
    )
    "learning", "github" -> listOf(
        R.string.detail_q_start to R.string.detail_prompt_start,
        R.string.detail_q_compare to R.string.detail_prompt_compare,
    )
    "entertainment", "music", "media" -> listOf(
        R.string.detail_q_match to R.string.detail_prompt_match,
        R.string.detail_q_more to R.string.detail_prompt_more,
    )
    "events", "travel" -> listOf(
        R.string.detail_q_plan to R.string.detail_prompt_plan,
        R.string.detail_q_verify to R.string.detail_prompt_verify,
    )
    "horoscope" -> listOf(R.string.detail_q_reflect to R.string.detail_prompt_reflect)
    "weather" -> listOf(R.string.detail_q_plan_day to R.string.detail_prompt_plan_day)
    else -> listOf(
        R.string.detail_q_explain to R.string.detail_prompt_explain,
        R.string.detail_q_implications to R.string.detail_prompt_implications,
    )
}
