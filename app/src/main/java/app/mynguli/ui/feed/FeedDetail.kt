package app.mynguli.ui.feed

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.feed.AiActivity
import app.mynguli.feed.ResourceChecks
import kotlinx.coroutines.launch
import app.mynguli.ui.*
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The detail view expands verified metadata and useful actions, never invents article text. */
@Composable
fun FeedDetail(
    ui: FeedUiState,
    id: String?,
    onSave: (String) -> Unit,
    onAsk: () -> Unit,
    onFeed: () -> Unit,
    onPrompt: ((String) -> Unit)? = null,
    onItem: ((String) -> Unit)? = null,
    onModule: ((String) -> Unit)? = null,
    onHide: ((String) -> Unit)? = null,
    onLoadBrief: () -> Unit = {},
    onCancelBrief: () -> Unit = {},
    onRetryBrief: () -> Unit = {},
    onFollowUp: ((String) -> Unit)? = null,
    onConnect: (() -> Unit)? = null,
) {
    val state = ui.state
    val item = state?.items?.find { it.id == id }
    val context = LocalContext.current
    val now = LocalFeedClock.current
    var section by rememberSaveable(id) { mutableStateOf("overview") }
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    var storyNotesOpen by rememberSaveable(id) { mutableStateOf(!ui.connected) }
    val previewKey = "detail-$id"
    val activePreview by MediaPreviewSession.state.collectAsState()
    val showPlayer = activePreview == previewKey
    DisposableEffect(previewKey) { onDispose { MediaPreviewSession.clear(previewKey) } }
    fun openResource(url: String) { if(RichContent.type(url) in setOf("youtube", "audio", "video")) MediaPreviewSession.select(previewKey) else openSource(context,url) }
    val loadBrief by rememberUpdatedState(onLoadBrief)
    LaunchedEffect(item?.id, ui.connected) { if (item != null) loadBrief() }
    ScreenContainer(Modifier.testTag("detail-screen"), scrollKey = id.orEmpty()) {
        if (item == null || state == null) {
            AppCard {
                Text(stringResource(R.string.feed_unavailable_title), style = MaterialTheme.typography.headlineSmall)
                SupportingText(stringResource(R.string.feed_unavailable_body))
                FilledTonalButton(onClick = onFeed) { Text(stringResource(R.string.feed_back_feed)) }
            }
        } else {
            val module = state.modules.find { it.id == item.moduleId }
            val sources = remember(item) {
                (listOfNotNull(item.sourceUrl.takeIf { FeedLogic.safeUrl(it) }?.let { FeedCitation(item.sourceName, it) }) + item.citations)
                    .filter { FeedLogic.safeUrl(it.url) }.distinctBy { FeedLogic.canonicalUrl(it.url) }
            }
            val originalAvailable = FeedLogic.safeUrl(item.sourceUrl)
            val related = remember(state.items, state.hiddenIds, state.modules, state.timezone, item.id, now / 60_000) {
                FeedLogic.visible(state, now).filter { it.id != item.id && it.moduleId == item.moduleId }
                    .sortedByDescending { it.publishedAtMillis.takeIf { date -> date > 0 } ?: it.fetchedAtMillis }.take(6)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.sourceName.ifBlank { stringResource(moduleNameResource(item.kind)) },
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (onHide != null) Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("detail-menu")) {
                            Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.detail_more_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.detail_hide)) },
                                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                                onClick = { menu = false; onHide(item.id); onFeed() }, modifier = Modifier.testTag("detail-hide"))
                        }
                    }
                }
                Text(item.title, style = MaterialTheme.typography.headlineMedium)
                Text(if (item.kind == "horoscope") stringResource(R.string.feed_entertainment) else itemTimestamp(item),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!showPlayer) item.media?.let { media ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(24.dp))) {
                        PublisherImage(item, Modifier.fillMaxSize(), large = true)
                        if (item.contentType == "video" && originalAvailable) FilledIconButton(
                            onClick = { openResource(item.sourceUrl) }, modifier = Modifier.align(Alignment.Center).size(56.dp).testTag("detail-play"),
                            shape = CircleShape,
                        ) { Icon(Icons.Outlined.PlayArrow, stringResource(R.string.edition_watch), Modifier.size(30.dp)) }
                    }
                    TextButton(onClick = { openSource(context, media.sourceUrl) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.edition_image_credit, media.credit.ifBlank { item.sourceName }), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(5.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(13.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (originalAvailable) Button(onClick = { openResource(item.sourceUrl) },
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("detail-source"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(resourceAction(item.contentType)), modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                } else Spacer(Modifier.weight(1f))
                val saved = item.id in state.savedIds
                FilledTonalIconButton(onClick = { onSave(item.id) }, modifier = Modifier.size(48.dp).testTag("detail-save")) {
                    Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        stringResource(if (saved) R.string.feed_unsave else R.string.feed_save))
                }
                if (originalAvailable) IconButton(onClick = { shareResource(context, item.title, item.sourceUrl) }, modifier = Modifier.size(48.dp).testTag("detail-share")) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.detail_share))
                }
            }
            if (DownloadResource.from(item.sourceUrl) != null && RichContent.type(item.sourceUrl) == "document")
                ResourceDownloadButton(item.sourceUrl)
            if(showPlayer && originalAvailable) {
                OfficialMediaPlayer(item.sourceUrl)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { MediaPreviewSession.clear(previewKey) }) { Text(stringResource(R.string.detail_close_player)) }
                    TextButton(onClick = { openSource(context, item.sourceUrl) }) { Text(stringResource(R.string.resource_open)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = section == "overview", onClick = { section = "overview" },
                    label = { Text(stringResource(R.string.detail_overview)) }, modifier = Modifier.testTag("detail-tab-overview"))
                FilterChip(selected = section == "sources", onClick = { section = "sources" },
                    label = { Text(stringResource(R.string.detail_sources_count, sources.size)) }, modifier = Modifier.testTag("detail-tab-sources"))
            }
            if (section == "overview") {
                DetailBrief(ui, item, onRetryBrief, onCancelBrief, onFollowUp, onConnect ?: onAsk)
                item.weather?.let { weather ->
                    WeatherStrip(item) { openSource(context, item.sourceUrl) }
                    Text(stringResource(R.string.edition_feels_like, weather.feelsLikeC.toInt()), style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Useful cached text is readable immediately, independently of the answer stream.
                    val hasAnswer = ui.detailItemId == item.id && (ui.detailText.isNotBlank() || ui.detailTurns.any { it.role == "assistant" })
                    if (!hasAnswer && item.summary.isNotBlank() && !storyNotesOpen) {
                        RichResponse(item.summary)
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { storyNotesOpen = !storyNotesOpen }, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("detail-story-notes"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            Text(stringResource(if (storyNotesOpen) R.string.detail_collapse_notes else R.string.detail_expand_notes))
                            Spacer(Modifier.width(6.dp))
                            Icon(if (storyNotesOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        }
                        if (storyNotesOpen) {
                if (item.summary.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (item.aiGenerated) R.string.detail_ai_summary else R.string.detail_source_summary),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(item.summary, style = MaterialTheme.typography.bodyLarge) }
                }
                if (item.keyPoints.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.detail_key_points))
                    item.keyPoints.forEach { point -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Check, null, Modifier.padding(top = 3.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(point, style = MaterialTheme.typography.bodyMedium) }
                    } }
                }
                if (item.nextStep.isNotBlank()) AppCard(color = MeBotPalette.current.mintSurface) {
                    Text(stringResource(R.string.detail_next_step), style = MaterialTheme.typography.titleMedium, color = MeBotPalette.current.onMint)
                    Text(item.nextStep, style = MaterialTheme.typography.bodyMedium, color = MeBotPalette.current.onMint)
                }
                if (item.caveat.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.detail_caveat), style = MaterialTheme.typography.titleSmall)
                    Text(item.caveat, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.why.isNotBlank()) AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(stringResource(R.string.feed_why), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(item.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (item.deadline.isNotBlank()) Surface(color = MeBotPalette.current.mintSurface, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.EventAvailable, null, tint = MeBotPalette.current.onMint)
                        Text(stringResource(R.string.feed_deadline, item.deadline), style = MaterialTheme.typography.titleSmall, color = MeBotPalette.current.onMint)
                    }
                }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAsk, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("detail-ask"), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text(stringResource(R.string.detail_discuss))
                    }
                    if (onPrompt != null) HorizontalRail(detailQuestions(item.kind).withIndex().toList(),
                        itemKey = { it.index.toString() }, modifier = Modifier.testTag("detail-question-rail")) { (index, pair) ->
                        val prompt = stringResource(pair.second)
                        AssistChip(onClick = { if (ui.connected) onPrompt(prompt) else onAsk() },
                            label = { Text(stringResource(pair.first)) }, enabled = !ui.asking,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("detail-question-$index"))
                    }
                }
                if (module != null && onModule != null) Surface(onClick = { onModule(module.id) },
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.testTag("detail-module")) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(moduleIcon(module.kind), null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(moduleName(module), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.detail_adjust_module), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp))
                    }
                }
                if (related.isNotEmpty() && onItem != null) Column(Modifier.testTag("detail-related-section"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.detail_related))
                    HorizontalRail(related, itemKey = { it.id }, spacing = 12.dp, modifier = Modifier.testTag("detail-related-rail")) { other ->
                            Surface(onClick = { onItem(other.id) }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.width(246.dp).testTag("detail-related-${other.id}")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (other.media != null) Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))) {
                                        PublisherImage(other, Modifier.fillMaxSize())
                                    }
                                    Text(other.sourceName.ifBlank { stringResource(moduleNameResource(other.kind)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(other.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(itemTimestamp(other), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                    }
                }
            } else {
                AppCard {
                    Text(stringResource(R.string.detail_about_update), style = MaterialTheme.typography.titleMedium)
                    DetailMetadata(R.string.detail_published, detailDate(item.publishedAtMillis, state.timezone))
                    DetailMetadata(R.string.detail_retrieved, detailDate(item.fetchedAtMillis, state.timezone))
                    DetailMetadata(R.string.detail_summary_type, stringResource(if (item.aiGenerated) R.string.detail_ai_summary else R.string.detail_source_summary))
                    if (item.kind == "horoscope") SupportingText(stringResource(R.string.feed_entertainment))
                }
                if (sources.isEmpty()) SupportingText(stringResource(R.string.detail_no_sources))
                else ConversationSources(sources, "detail-citation")
            }
        }
    }
}

@Composable
private fun DetailMetadata(label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailBrief(ui: FeedUiState, item: FeedItem, onRetry: () -> Unit, onCancel: () -> Unit,
    onFollowUp: ((String) -> Unit)?, onConnect: () -> Unit) {
    val active = ui.detailItemId == item.id
    val turns = if (active) ui.detailTurns else emptyList()
    val draft = if (active) ui.detailText else ""
    val loading = active && ui.detailLoading
    val error = ui.detailError.takeIf { active }
    var visibleTurnCount by rememberSaveable(item.id) { mutableIntStateOf(8) }
    val visibleTurns = remember(turns, visibleTurnCount) { turns.takeLast(visibleTurnCount) }
    val responseTime = turns.lastOrNull { it.role == "assistant" }?.createdAtMillis ?: 0L
    var question by rememberSaveable(item.id) { mutableStateOf("") }
    var submitted by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(turns.lastOrNull()?.id, loading) {
        if (!loading && turns.lastOrNull()?.role == "assistant" && submitted != null &&
            turns.dropLast(1).lastOrNull { it.role == "user" }?.text == submitted) {
            if (question.trim() == submitted) question = ""
            submitted = null
        }
    }
    Column(Modifier.fillMaxWidth().testTag("detail-brief"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (turns.isNotEmpty() || draft.isNotBlank() || error != null || !ui.connected) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (turns.isNotEmpty() || draft.isNotBlank() || error != null || loading || !ui.connected) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val activity = if (loading) ui.activities.lastOrNull { it.scope == item.id }
                ?: AiActivity("detail-preparing", item.id, item.kind, "answer", stage = "preparing") else null
            MeBotActivityBadge(activity, Modifier.size(30.dp))
            Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (loading) TextButton(onClick = onCancel, modifier = Modifier.testTag("detail-brief-stop")) { Text(stringResource(R.string.workspace_stop)) }
        }
        if (responseTime > 0) Text(stringResource(R.string.detail_last_response, detailDate(responseTime, ui.state?.timezone.orEmpty())),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("detail-brief-time"))
        if (!ui.connected && turns.isEmpty() && draft.isBlank()) {
            Text(stringResource(R.string.detail_connect_explanation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onConnect, modifier = Modifier.testTag("detail-brief-connect")) { Text(stringResource(R.string.feed_connect)) }
        }
        if (turns.size > visibleTurnCount) TextButton(onClick = { visibleTurnCount = (visibleTurnCount + 8).coerceAtMost(turns.size) },
            contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("detail-earlier-messages")) {
            Text(stringResource(R.string.detail_earlier_messages))
        }
        visibleTurns.forEach { turn -> key(turn.id) {
            val user = turn.role == "user"
            Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (user) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RichResponse(turn.text, Modifier.testTag(if (user) "detail-brief-question-${turn.id}" else "detail-brief-answer"), if(user) emptyList() else turn.citations)
                    if (!user) ConversationSources(turn.citations, "detail-turn-source-${turn.id}")
                }
            }
        } }
        if (draft.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!loading) Text(stringResource(R.string.workspace_partial),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RichResponse(draft, Modifier.testTag("detail-brief-draft"), ui.detailCitations)
        }
        if (error != null) FeedStatus(error, onRetry = onRetry, onConnect = onConnect)
        else if (ui.connected && active && !loading && turns.isEmpty() && draft.isBlank()) TextButton(onClick = onRetry, modifier = Modifier.testTag("detail-brief-retry")) {
            Text(stringResource(R.string.detail_load_explanation))
        }
        if (onFollowUp != null && ui.connected && turns.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = question, onValueChange = { if (it.length <= 2000) question = it },
                modifier = Modifier.fillMaxWidth().testTag("detail-followup"), enabled = !loading, minLines = 1, maxLines = 4,
                placeholder = { Text(stringResource(R.string.detail_followup)) }, shape = RoundedCornerShape(16.dp))
            FilledTonalButton(onClick = { submitted = question.trim(); onFollowUp(question.trim()) },
                enabled = question.isNotBlank() && !loading, modifier = Modifier.align(Alignment.End).testTag("detail-followup-send")) {
                Text(stringResource(R.string.feed_ask_send))
            }
        }
    }
}

@Composable
private fun detailDate(millis: Long, timezone: String): String {
    if (millis <= 0) return stringResource(R.string.feed_time_unavailable)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) + " · " + zone.id
}

@Composable
internal fun DetailSource(source: FeedCitation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(onClick = { openSource(context, source.url) }, modifier = modifier, color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.title.ifBlank { runCatching { URI(source.url).host }.getOrNull().orEmpty() }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(runCatching { URI(source.url).host.removePrefix("www.") }.getOrDefault(source.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.feed_open_source), Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ConversationSources(sources: List<FeedCitation>, tagPrefix: String) {
    val valid = remember(sources) {
        sources.filter { FeedLogic.safeUrl(it.url) }.distinctBy { FeedLogic.canonicalUrl(it.url) }
    }
    if (valid.isEmpty()) return
    val media = remember(valid) { valid.filter { RichContent.type(it.url) in setOf("image", "youtube", "audio", "video") } }
    val context = LocalContext.current
    val fileChecks by ResourceChecks.state.collectAsState()
    val fileLinks = remember(valid) { valid.filter { RichContent.type(it.url) == "document" } }
    LaunchedEffect(fileLinks.map { it.url }) {
        fileLinks.take(8).forEach { source -> launch { ResourceChecks.check(context, source.url) } }
    }
    val documents = remember(fileLinks, fileChecks) { fileLinks.filter { fileChecks[it.url]?.available == true } }
    var selected by rememberSaveable(tagPrefix) { mutableStateOf("all") }
    var expanded by rememberSaveable(tagPrefix, selected) { mutableStateOf(false) }
    val shown = when (selected) { "media" -> media; "files" -> documents; else -> valid }
    Column(Modifier.fillMaxWidth().testTag("$tagPrefix-resources"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.resource_heading, valid.size), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (media.isNotEmpty() || documents.isNotEmpty()) HorizontalRail(
            listOf("all" to (stringResource(R.string.resource_sources) + " ${valid.size}")) +
                (if (media.isNotEmpty()) listOf("media" to (stringResource(R.string.resource_media) + " ${media.size}")) else emptyList()) +
                (if (documents.isNotEmpty()) listOf("files" to (stringResource(R.string.resource_documents) + " ${documents.size}")) else emptyList()),
            itemKey = { it.first }) { (id, label) ->
            FilterChip(selected == id, onClick = { selected = id }, label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-filter-$id"))
        }
        (if (expanded) shown else shown.take(2)).forEach { source -> key(source.url) {
            val index = valid.indexOf(source)
            if (RichContent.type(source.url) != "link") RichSourcePreview(source, Modifier.testTag("$tagPrefix-$index"))
            else DetailSource(source, Modifier.testTag("$tagPrefix-$index"))
        } }
        if (shown.size > 2) TextButton(onClick = { expanded = !expanded },
            contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("$tagPrefix-toggle")) {
            Text(if (expanded) stringResource(R.string.resource_less) else stringResource(R.string.resource_all, shown.size))
        }
    }
}

internal fun shareResource(context: Context, title: String, url: String) {
    if (!FeedLogic.safeUrl(url)) return
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.detail_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, R.string.feed_link_error, Toast.LENGTH_SHORT).show() }
}

private fun detailQuestions(kind: String): List<Pair<Int, Int>> = when (kind) {
    "opportunities", "scholarships" -> listOf(R.string.detail_q_eligibility to R.string.detail_prompt_eligibility, R.string.detail_q_prepare to R.string.detail_prompt_prepare)
    "security" -> listOf(R.string.detail_q_affected to R.string.detail_prompt_affected, R.string.detail_q_fix to R.string.detail_prompt_fix)
    "learning", "github" -> listOf(R.string.detail_q_start to R.string.detail_prompt_start, R.string.detail_q_compare to R.string.detail_prompt_compare)
    "entertainment", "music", "media" -> listOf(R.string.detail_q_match to R.string.detail_prompt_match, R.string.detail_q_more to R.string.detail_prompt_more)
    "events", "travel" -> listOf(R.string.detail_q_plan to R.string.detail_prompt_plan, R.string.detail_q_verify to R.string.detail_prompt_verify)
    "horoscope" -> listOf(R.string.detail_q_reflect to R.string.detail_prompt_reflect)
    "weather" -> listOf(R.string.detail_q_plan_day to R.string.detail_prompt_plan_day)
    else -> listOf(R.string.detail_q_explain to R.string.detail_prompt_explain, R.string.detail_q_implications to R.string.detail_prompt_implications)
}
