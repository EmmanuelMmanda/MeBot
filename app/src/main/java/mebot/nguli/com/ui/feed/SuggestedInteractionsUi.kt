package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.core.AiSuggestedInteractionIntent
import mebot.nguli.com.core.AiSuggestedInteractions095
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.LocalSuggestionOption095
import mebot.nguli.com.feed.localSuggestionOptions095
import java.util.Locale

internal data class SuggestionChip095(val stableId: String, val label: String)
internal data class StorySuggestionGroup095(val item: FeedItem, val options: List<SuggestionChip095>)

internal enum class LocalSuggestionFallback095 { CONTENT, DETAIL, CONTENT_AND_DETAIL }

internal fun currentStoryCandidates095(state: FeedState, now: Long): List<FeedItem> =
    FeedLogic.edition(state, now).filter { it.weather == null && it.kind != "weather" && it.kind != "horoscope" }.take(2)

/** Read only eligible cached provider suggestions; local trusted fallbacks are always available. */
@Composable
internal fun suggestionChips095(
    ui: FeedUiState,
    state: FeedState,
    item: FeedItem,
    now: Long,
    appLanguage: String,
    fallback: LocalSuggestionFallback095,
    priorUserQuestions: List<String> = emptyList(),
): List<SuggestionChip095> {
    val used = remember(priorUserQuestions) { priorUserQuestions.map(::normalizeQuestion095).toSet() }
    // LocalFeedClock intentionally ticks only once a minute for feed ranking. A
    // discovery response can land immediately after that tick, so its freshly
    // stamped cache must be validated against wall time at this recomputation,
    // not a clock value that predates the cache by a few seconds.
    val cacheCheckNow = System.currentTimeMillis()
    val cached = remember(ui.connected, ui.asking, ui.importWorking, state, item, now / 60_000, appLanguage, used) {
        if (!ui.asking && !ui.importWorking && ui.connected && AiSuggestedInteractions095.isApplicable(state, item, cacheCheckNow, appLanguage)) {
            item.suggestedInteractions
                .filter { it.intent == AiSuggestedInteractionIntent.ASK_ABOUT_ITEM }
                .filterNot { normalizeQuestion095(it.prompt) in used }
                .take(AiSuggestedInteractions095.MAX_INTERACTIONS)
                .map { SuggestionChip095(AiSuggestedInteractions095.suggestionId(item, it), it.label) }
        } else emptyList()
    }
    if (cached.isNotEmpty()) return cached
    if (ui.asking || ui.importWorking) return emptyList()

    val local = localSuggestionOptions095(item.kind)
    val detail = local.filterNot { it.stableId == "local:explain" }
    val selected: List<LocalSuggestionOption095> = when (fallback) {
        LocalSuggestionFallback095.CONTENT -> {
            if (item.kind in setOf("weather", "horoscope") || item.weather != null) detail.take(1)
            else local.filter { it.stableId == "local:explain" }.take(1)
        }
        LocalSuggestionFallback095.DETAIL -> detail.take(2).ifEmpty { local.take(1) }
        LocalSuggestionFallback095.CONTENT_AND_DETAIL -> buildList {
            if (item.kind in setOf("weather", "horoscope") || item.weather != null) addAll(detail.take(2))
            else {
                addAll(local.filter { it.stableId == "local:explain" }.take(1))
                addAll(detail.take(1))
            }
        }
    }
    return selected.filterNot { normalizeQuestion095(stringResource(it.promptRes, item.title)) in used }
        .map { SuggestionChip095(it.stableId, stringResource(it.labelRes)) }
}

private fun normalizeQuestion095(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

@Composable
internal fun SuggestionRail095(
    options: List<SuggestionChip095>,
    tag: String,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) return
    HorizontalRail(options.withIndex().toList(), itemKey = { it.index.toString() }, modifier = Modifier.testTag(tag)) { (index, option) ->
        AssistChip(
            onClick = { onSelect(option.stableId) },
            label = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-$index"),
        )
    }
}
