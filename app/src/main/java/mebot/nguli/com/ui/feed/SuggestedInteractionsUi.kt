package mebot.nguli.com.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.AiSuggestedInteractionIntent
import mebot.nguli.com.core.AiSuggestedInteractions095
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.LocalSuggestionOption095
import mebot.nguli.com.feed.SuggestionOptionSnapshot097
import mebot.nguli.com.feed.SuggestionOptionSource097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.feed.localSuggestionOptions095
import mebot.nguli.com.security.ProtectedWindowRegistration
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

/** Compact entry point shared by the feed, story details and the conversation composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestionAction097(
    target: SuggestionTargetSnapshot097?,
    options: List<SuggestionOptionSnapshot097>,
    contextLabel: String,
    language: String,
    connected: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onUse: (SuggestionTargetSnapshot097, String, String) -> Unit,
    onEdit: (SuggestionTargetSnapshot097, String, String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "suggestions",
) {
    var opened by remember { mutableStateOf<SuggestionSheetSnapshot097?>(null) }
    var actionHandled by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val openLabel = stringResource(R.string.suggestions_open)
    if (target != null && options.isNotEmpty()) {
        IconButton(
            onClick = { if (!busy) {
                keyboard?.hide()
                actionHandled = false
                opened = SuggestionSheetSnapshot097(target, options.toList(), contextLabel, language)
            } },
            enabled = !busy,
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("$testTag-open"),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = openLabel, modifier = Modifier.size(21.dp))
        }
    }
    val sheetSnapshot = opened
    if (sheetSnapshot != null) {
        val sheetIsStale = target != sheetSnapshot.target || options != sheetSnapshot.options
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { opened = null },
            sheetState = sheetState,
            modifier = Modifier.testTag("$testTag-sheet"),
        ) {
            ProtectedWindowRegistration()
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                    .testTag("$testTag-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.titleLarge)
                        Text(sheetSnapshot.contextLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$testTag-context"))
                    }
                    TextButton(onClick = { opened = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-close")) {
                        Text(stringResource(R.string.suggestions_close))
                    }
                }
                if (!connected) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().testTag("$testTag-offline-note"),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(stringResource(R.string.suggestions_offline_send), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                            TextButton(onClick = { opened = null; onConnect() }, modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-connect")) {
                                Text(stringResource(R.string.suggestions_connect))
                            }
                        }
                    }
                } else if (busy) {
                    Text(stringResource(R.string.suggestions_busy), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$testTag-busy"))
                }
                if (sheetIsStale) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().testTag("$testTag-stale-note")) {
                        Text(stringResource(R.string.suggestions_stale),
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                sheetSnapshot.options.forEachIndexed { index, option ->
                    val optionStillCurrent = !sheetIsStale && options.getOrNull(index) == option
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("$testTag-option-$index"),
                    ) {
                        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(option.label, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(option.displayText, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$testTag-question-$index"))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(if (option.source == SuggestionOptionSource097.LOCAL)
                                        R.string.suggestions_source_local else R.string.suggestions_source_cached),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).testTag("$testTag-source-$index"),
                                )
                                TextButton(
                                    onClick = {
                                        if (!actionHandled && !busy) {
                                            actionHandled = true
                                            opened = null
                                            onEdit(sheetSnapshot.target, option.id, sheetSnapshot.language)
                                        }
                                    },
                                    enabled = optionStillCurrent && !busy && !actionHandled,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-edit-$index"),
                                ) {
                                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.suggestions_edit))
                                }
                                Button(
                                    onClick = {
                                        if (!actionHandled && !busy && connected) {
                                            actionHandled = true
                                            opened = null
                                            onUse(sheetSnapshot.target, option.id, sheetSnapshot.language)
                                        }
                                    },
                                    enabled = optionStillCurrent && connected && !busy && !actionHandled,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-send-$index"),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.suggestions_send))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SuggestionSheetSnapshot097(
    val target: SuggestionTargetSnapshot097,
    val options: List<SuggestionOptionSnapshot097>,
    val contextLabel: String,
    val language: String,
)
