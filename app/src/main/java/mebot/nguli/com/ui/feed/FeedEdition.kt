package mebot.nguli.com.ui.feed

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.SuggestionGenerationHandle097
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.feed.SuggestionTargets097
import mebot.nguli.com.ui.*
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The first edition stays finite; each card has a source action and a useful next step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedHome(ui: FeedUiState, onItem: (String) -> Unit, onModules: () -> Unit, onYou: () -> Unit,
    onConnect: () -> Unit, onAsk: () -> Unit, onRefresh: () -> Unit, onSave: (String) -> Unit,
    onHide: (String) -> Unit, onDismissError: () -> Unit, nextAlarm: AlarmPlan?, onAlarms: () -> Unit,
    onPrompt: (String?, String) -> Unit = { _, _ -> onAsk() }, onSearch: (() -> Unit)? = null,
    returnToTopRequest: Int = 0, onExploreModules: () -> Unit = onModules,
    onRefreshModule: ((String?) -> Unit)? = null,
    onContext: (() -> Unit)? = null, onDismissNotice: ((String) -> Unit)? = null,
    onLoadMore: ((String?) -> Unit)? = null,
    onUseSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onEditSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onSuggestionSheet: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097 = { target, _ ->
        SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.NEEDS_GENERATION, emptyList())
    },
    onGenerateSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onCancelSuggestionGeneration: (String) -> Unit = {},
    onEnsureDailySelection: () -> Unit = {},
) {
    val state = ui.state ?: return
    var filter by rememberSaveable { mutableStateOf("all") }
    val enabled = remember(state.modules) { state.modules.filter { it.enabled } }
    LaunchedEffect(enabled.map { it.id }) { if (filter != "all" && filter != "today" && enabled.none { it.id == filter }) filter = "all" }
    val refreshSelected: () -> Unit = { if (onRefreshModule == null) onRefresh() else onRefreshModule(filter.takeUnless { it == "all" || it == "today" }) }
    val now = LocalFeedClock.current
    val edition = remember(filter == "today", state.items, state.modules, state.hiddenIds, state.savedIds, state.context, state.region, state.timezone, state.viewedAtMillis, state.readingHistoryEnabled, state.relevanceSignals, state.starredStories, state.editionSequence, now / 60_000) {
        if (filter == "today") emptyList() else FeedLogic.edition(state, now)
    }
    val today = remember(state, now, filter == "today") { if (filter == "today") TodayFeed.edition(state, now) else emptyList() }
    val todayWindow = remember(state.timezone, now / 60_000) { TodayFeed.dayWindow(now, state.timezone) }
    val ensureDailySelection by rememberUpdatedState(onEnsureDailySelection)
    LaunchedEffect(
        todayWindow.date,
        state.timezone,
        state.items,
        state.modules,
        state.hiddenIds,
        state.savedIds,
        state.context,
        state.region,
        state.connection,
        state.relevanceSignals,
        state.starredStories,
        state.viewedAtMillis,
        state.readingHistoryEnabled,
        state.editionSequence,
    ) {
        // The ViewModel callback only updates the local projection; this effect never requests AI.
        ensureDailySelection()
    }
    val personalTodaySelection = remember(state, now / 60_000, filter == "today") {
        if (filter == "today") PersonalDailySelectionLogic098.resolve(state, now) else emptyList()
    }
    val personalTodayIds = remember(personalTodaySelection) { personalTodaySelection.mapTo(HashSet()) { it.id } }
    val personalTodaySourceKeys = remember(personalTodaySelection) { personalTodaySelection.mapNotNullTo(HashSet(), ::todaySourceKey098) }
    val verifiedTodayEntries = remember(today, personalTodayIds, personalTodaySourceKeys) {
        today.filterNot { entry -> entry.item.id in personalTodayIds || todaySourceKey098(entry.item)?.let { it in personalTodaySourceKeys } == true }
    }
    val verifiedTodayById = remember(today) { today.associateBy { it.item.id } }
    val verifiedTodayBySource = remember(today) {
        today.mapNotNull { entry -> todaySourceKey098(entry.item)?.let { key -> key to entry } }.toMap()
    }
    val items = remember(edition, today, filter) {
        if (filter == "today") today.map { it.item } else edition.filter { filter == "all" || it.moduleId == filter }
    }
    val weather = items.firstOrNull { it.weather != null }
    val stories = remember(items) { items.filter { it.weather == null } }
    val youLabel = stringResource(R.string.feed_you_title)
    val layout = LocalConfiguration.current
    val appLanguage = if (ui.appLanguage == "sw") "sw" else "en"
    val compact = layout.screenWidthDp < 360 || layout.fontScale > 1.15f
    // Each filter keeps its own page and position through navigation and rotation.
    // Rendering stays bounded to the viewport even after several pages are opened.
    val pages = rememberSaveableStateHolder()
    // A Feed-tab tap belongs to the current filter, not to every saved filter page.
    var consumedTopRequest by rememberSaveable { mutableIntStateOf(returnToTopRequest) }
    pages.SaveableStateProvider(filter) {
        var visibleCount by rememberSaveable { mutableIntStateOf(5) }
        val listState = rememberLazyListState()
        val scope=rememberCoroutineScope()
        var acceptedIds by rememberSaveable { mutableStateOf(stories.map { it.id }) }
        val hasScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        val newIds=remember(stories,acceptedIds){stories.map{it.id}.filterNot{it in acceptedIds}}
        LaunchedEffect(stories.map{it.id}) { if (acceptedIds.isEmpty()) acceptedIds=stories.map{it.id} }
        // While reading, retain current card order. New results are appended until the reader opts in.
        val presented=remember(stories,acceptedIds){FeedPaging.ordered(stories, acceptedIds)}
        val visible=remember(presented,visibleCount){presented.take(visibleCount)}
        val suggestionItem = visible.firstOrNull() ?: weather
        val filteredModule = state.modules.find { it.id == filter }
        val suggestionTarget = remember(state, suggestionItem?.id, filter, appLanguage) {
            suggestionItem?.let { SuggestionTargets097.item(state, it.id, appLanguage) }
                // A weather/horoscope filter is an explicitly unsupported AI scope. Do not
                // turn its empty state into a module or general suggestion destination.
                ?: filteredModule?.takeIf { it.kind !in setOf("weather", "horoscope") }
                    ?.let { SuggestionTargets097.module(state, it.id, appLanguage) }
                ?: if (filter == "all") SuggestionTargets097.general(state, appLanguage) else null
        }
        val suggestionContext = suggestionItem?.title ?: filteredModule?.let { moduleName(it) }
            ?: stringResource(R.string.workspace_general)
        var autoDiscoveryUsed by rememberSaveable { mutableStateOf(false) }
        val nearEnd by remember { derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        } }
        LaunchedEffect(nearEnd, hasScrolled, visibleCount, stories.size, ui.refreshing, state.readingPreferences.autoLoadMore) {
            // Only advance after the reader has scrolled. Keep the same viewport while adding rows.
            if (filter != "today" && state.readingPreferences.autoLoadMore && nearEnd && hasScrolled && !ui.refreshing) {
                if (visibleCount < stories.size) {
                    listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                    visibleCount = (visibleCount + 5).coerceAtMost(stories.size)
                } else if (!autoDiscoveryUsed && stories.isNotEmpty() && onLoadMore != null) {
                    // One automatic network batch per filter visit prevents a zero-result request loop.
                    autoDiscoveryUsed = true
                    onLoadMore(filter.takeUnless { it == "all" || it == "today" })
                }
            }
        }
        LaunchedEffect(returnToTopRequest) {
            if (returnToTopRequest != consumedTopRequest) {
                consumedTopRequest = returnToTopRequest
                visibleCount = 5
                listState.scrollToItem(0)
                if (filter != "today") refreshSelected()
            }
        }
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = refreshSelected,
            modifier = Modifier.fillMaxSize().testTag("feed-pull-refresh"), indicator = {}) {
            LazyColumn(Modifier.testTag("feed-screen").widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().imePadding(),
                state = listState, contentPadding = PaddingValues(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = "header", contentType = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MeBotActivityBadge(if (ui.refreshing) ui.activities.lastOrNull { it.operation == "discovery" } ?: mebot.nguli.com.feed.AiActivity("feed-preparing", filter, "", "refresh", "preparing") else null, Modifier.size(34.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = refreshSelected, enabled = !ui.refreshing, modifier = Modifier.testTag("feed-refresh")) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.feed_refresh), Modifier.size(23.dp))
                            }
                            Surface(onClick = onYou, color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = youLabel }) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (state.name.isNotBlank()) Text(state.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    else Icon(Icons.Outlined.PersonOutline, null, Modifier.size(23.dp))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(feedDate(state.timezone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (state.name.isBlank()) stringResource(R.string.edition_heading) else stringResource(R.string.feed_greeting, state.name),
                                    style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (onSearch != null) Surface(onClick = onSearch, shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().testTag("feed-search")) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.search_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.polish_feed_filters), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = onModules, modifier = Modifier.heightIn(min = 48.dp).testTag("feed-all-modules")) {
                                Icon(Icons.Outlined.GridView, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.polish_all_modules))
                            }
                        }
                        val choices = listOf("all" to stringResource(R.string.edition_for_you), "today" to stringResource(R.string.today_090_title)) + enabled.map { it.id to moduleName(it) }
                        HorizontalRail(choices, itemKey = { it.first }, selectedKey = filter, modifier = Modifier.testTag("feed-filter-rail")) { (id, label) ->
                            FilterChip(filter == id, { filter = id }, label = { Text(label) }, shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("filter-$id"))
                        }
                    }
                }
                if (newIds.isNotEmpty()) item(key="new-ready",contentType="notice") {
                    FilledTonalButton(onClick={acceptedIds=FeedPaging.acceptNew(stories,acceptedIds);visibleCount=maxOf(5,newIds.size.coerceAtMost(10));scope.launch{listState.scrollToItem(1)}},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("feed-new-results")) {
                        Text(stringResource(R.string.feed_new_items,newIds.size))
                    }
                }
                if (ui.refreshProgress.startedAt>0 && (filter=="all" || filter=="today" || ui.refreshProgress.moduleId==null || ui.refreshProgress.moduleId==filter)) item(key="refresh-outcome",contentType="notice") {
                    val progress=ui.refreshProgress
                    val message=when{
                        progress.running && progress.newItems>0 -> stringResource(R.string.feed_progress_ready,progress.newItems)
                        progress.running -> ""
                        progress.failed.isNotEmpty() -> stringResource(R.string.feed_progress_partial,progress.newItems)
                        progress.newItems>0 -> stringResource(R.string.feed_progress_complete,progress.newItems,progress.completed.size)
                        else -> stringResource(R.string.feed_progress_none)
                    }
                    if(message.isNotBlank())Row(Modifier.fillMaxWidth().testTag("feed-refresh-outcome"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
                        Icon(if(progress.failed.isEmpty())Icons.Outlined.CheckCircleOutline else Icons.Outlined.Info,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.items.isNotEmpty() && onContext != null && onDismissNotice != null && SetupGuidance.next(state, ui.connected, "feed", now) != null)
                    item(key = "setup-notice", contentType = "notice") { SetupNotice(state, ui.connected, "feed", onConnect, onContext, onDismissNotice) }
                if (ui.error != null) item(key = "status", contentType = "status") {
                    FeedStatus(ui.error, if (!ui.refreshing) refreshSelected else null, onDismissError, onConnect)
                }

                when {
                    filter == "today" && items.isEmpty() && personalTodaySelection.isEmpty() -> item(key = "today-empty", contentType = "empty") {
                        EditionEmpty(R.string.today_090_empty, R.string.today_090_empty_body,
                            R.string.today_090_regular_feed, { filter = "all" })
                    }
                    ui.refreshing && items.isEmpty() && personalTodaySelection.isEmpty() -> Unit // Activity is rendered once, above the page.
                    enabled.isEmpty() -> item(key = "empty", contentType = "empty") {
                        EditionEmpty(R.string.feed_no_modules, R.string.feed_no_modules_body, R.string.feed_manage_modules, onModules)
                    }
                    items.isEmpty() && filter != "today" -> {
                        item(key = "empty", contentType = "empty") {
                            EditionEmpty(R.string.edition_empty_title, if (ui.connected) R.string.edition_empty_connected else R.string.edition_empty_body,
                                if (ui.connected) R.string.feed_load else R.string.feed_connect, if (ui.connected) refreshSelected else onConnect)
                        }
                        if (!ui.connected) item(key = "public-updates", contentType = "action") {
                            TextButton(onClick = refreshSelected, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edition_public_updates)) }
                        }
                    }
                    filter == "today" -> {
                        if (personalTodaySelection.isNotEmpty()) {
                            item(key = "today-personal-heading", contentType = "section-heading") {
                                Column(Modifier.semantics(mergeDescendants = true) {}.testTag("today-personal-selection-heading")) {
                                    SectionHeader(stringResource(R.string.daily_selection_098_title))
                                }
                            }
                            items(personalTodaySelection.take(visibleCount), key = { "today-personal-" + it.id }, contentType = { "story" }) { selectedItem ->
                                val selectedTarget = remember(state, selectedItem.id, appLanguage) {
                                    SuggestionTargets097.item(state, selectedItem.id, appLanguage)
                                }
                                val todaySourceEntry = if (FeedLogic.isValidOriginalReadShape(selectedItem)) null
                                    else verifiedTodayById[selectedItem.id] ?: todaySourceKey098(selectedItem)?.let(verifiedTodayBySource::get)
                                Column(Modifier.fillMaxWidth().testTag("today-personal-story-${selectedItem.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StoryRow(
                                        selectedItem,
                                        selectedItem.id in state.savedIds,
                                        onItem,
                                        onSave,
                                        onHide,
                                        todayMetadata = todaySourceEntry,
                                        timestampOverride = if (todaySourceEntry != null) "" else null,
                                        timezone = state.timezone,
                                        suggestionTarget = selectedTarget,
                                        suggestionLanguage = appLanguage,
                                        suggestionsConnected = ui.connected,
                                        suggestionsBusy = ui.asking || ui.importWorking || ui.suggestionWorking,
                                        onConnect = onConnect,
                                        onUseSuggestion = onUseSuggestion,
                                        onEditSuggestion = onEditSuggestion,
                                        onSuggestionSheet = onSuggestionSheet,
                                        onGenerateSuggestion = onGenerateSuggestion,
                                        onCancelSuggestionGeneration = onCancelSuggestionGeneration,
                                    )
                                }
                            }
                            if (visibleCount < personalTodaySelection.size) item(key = "today-personal-more", contentType = "action") {
                                TextButton(onClick = { visibleCount += 5 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.feed_show_more))
                                }
                            }
                        }
                        item(key = "today-verified-heading", contentType = "section-heading") {
                            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("today-verified-section-heading"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionHeader(stringResource(R.string.today_verified_section_098_title))
                                Text(stringResource(R.string.today_090_scope), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (verifiedTodayEntries.isEmpty()) item(key = "today-verified-empty", contentType = "empty") {
                            Box(Modifier.semantics(mergeDescendants = true) {}.testTag("today-verified-section-empty")) {
                                SupportingText(stringResource(
                                    if (today.isNotEmpty()) R.string.today_verified_section_098_covered else R.string.today_verified_section_098_empty,
                                ))
                            }
                        }
                        items(verifiedTodayEntries.take(visibleCount), key = { "today-" + it.item.id }, contentType = { "story" }) { entry ->
                            val todayTarget = remember(state, entry.item.id, appLanguage) {
                                SuggestionTargets097.item(state, entry.item.id, appLanguage)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TodayReasonLabel090(entry, state.timezone)
                                if (entry.item.weather != null) WeatherStrip(entry.item, state.timezone, showTimestamp = false) { onItem(entry.item.id) }
                                else StoryRow(entry.item, entry.item.id in state.savedIds, onItem, onSave, onHide, timestampOverride = "", timezone = state.timezone,
                                    suggestionTarget = todayTarget,
                                    suggestionLanguage = appLanguage,
                                    suggestionsConnected = ui.connected,
                                    suggestionsBusy = ui.asking || ui.importWorking || ui.suggestionWorking,
                                    onConnect = onConnect,
                                    onUseSuggestion = onUseSuggestion,
                                    onEditSuggestion = onEditSuggestion,
                                    onSuggestionSheet = onSuggestionSheet,
                                    onGenerateSuggestion = onGenerateSuggestion,
                                    onCancelSuggestionGeneration = onCancelSuggestionGeneration)
                                if (entry.item.weather != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    SuggestionAction097(
                                        target = todayTarget,
                                        contextLabel = entry.item.title,
                                        language = appLanguage,
                                        connected = ui.connected,
                                        busy = ui.asking || ui.importWorking || ui.suggestionWorking,
                                        onReadSnapshot = onSuggestionSheet,
                                        onGenerate = onGenerateSuggestion,
                                        onCancelGeneration = onCancelSuggestionGeneration,
                                        onConnect = onConnect,
                                        onUse = onUseSuggestion,
                                        onEdit = onEditSuggestion,
                                        testTag = "today-suggestions-${entry.item.id}",
                                    )
                                }
                            }
                        }
                        if (visibleCount < verifiedTodayEntries.size) item(key = "today-more", contentType = "action") {
                            TextButton(onClick = { visibleCount += 5 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.feed_show_more))
                            }
                        }
                    }
                    else -> {
                        if (visible.isNotEmpty()) item(key = "story-" + visible.first().id, contentType = "lead") {
                            LeadStory(visible.first(), visible.first().id in state.savedIds, onItem, onSave, onHide, state.timezone,
                                suggestionsTarget = suggestionTarget,
                                suggestionLanguage = appLanguage,
                                suggestionsConnected = ui.connected,
                                suggestionsBusy = ui.asking || ui.importWorking || ui.suggestionWorking,
                                onConnect = onConnect,
                                onUseSuggestion = onUseSuggestion,
                                onEditSuggestion = onEditSuggestion,
                                onSuggestionSheet = onSuggestionSheet,
                                onGenerateSuggestion = onGenerateSuggestion,
                                onCancelSuggestionGeneration = onCancelSuggestionGeneration,
                            )
                        }
                        if (weather != null) item(key = "weather-" + weather.id, contentType = "weather") {
                            WeatherStrip(weather, state.timezone) { onItem(weather.id) }
                        }
                        if (visible.size > 1) item(key = "closer-look", contentType = "closer-look") {
                            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                SectionHeader(stringResource(R.string.edition_across_modules))
                                if (visible.size >= 3 && compact) {
                                    visible.subList(1, 3).forEach { item -> key(item.id) { StoryRow(item, item.id in state.savedIds, onItem, onSave, onHide, timezone = state.timezone) } }
                                } else if (visible.size >= 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    visible.subList(1, 3).forEach { item ->
                                        key(item.id) { CompactStory(item, Modifier.weight(1f), item.id in state.savedIds, onSave, onItem, state.timezone) }
                                    }
                                } else key(visible[1].id) { StoryRow(visible[1], visible[1].id in state.savedIds, onItem, onSave, onHide, timezone = state.timezone) }
                            }
                        }
                        items(visible.drop(if (visible.size >= 3) 3 else 2), key = { "story-" + it.id }, contentType = { "story" }) { item ->
                            StoryRow(item, item.id in state.savedIds, onItem, onSave, onHide, timezone = state.timezone)
                        }
                        item(key = "page-end", contentType = "page-end") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(if (stories.isEmpty()) stringResource(R.string.edition_caught_up)
                                        else stringResource(R.string.edition_page_count, visible.size, stories.size),
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("feed-page-progress"))
                                    if (state.lastRefreshMillis > 0) Text(stringResource(R.string.feed_updated, relativeTime(state.lastRefreshMillis)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (visibleCount < stories.size) TextButton(onClick = {
                                    // Retain the viewport, not the footer's key, when rows are inserted
                                    // before this footer. Otherwise a page can be skipped on short screens.
                                    listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                    visibleCount = (visibleCount + 5).coerceAtMost(stories.size)
                                }, modifier = Modifier.testTag("feed-show-more")) {
                                    Text(stringResource(R.string.feed_show_more)); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                                } else TextButton(onClick = { autoDiscoveryUsed = true; refreshSelected() }, enabled = !ui.refreshing,
                                    modifier = Modifier.testTag("feed-find-more")) {
                                    Text(stringResource(if (ui.refreshing) R.string.feed_loading_more_083 else R.string.feed_find_more_083))
                                }
                            }
                        }
                    }
                }
                if (filter != "today" && items.isEmpty()) item(key = "question", contentType = "question") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { EditionQuestion(ui.connected, onAsk, onConnect) }
                        SuggestionAction097(
                            target = suggestionTarget,
                            contextLabel = suggestionContext,
                            language = appLanguage,
                            connected = ui.connected,
                            busy = ui.asking || ui.importWorking || ui.suggestionWorking,
                            onReadSnapshot = onSuggestionSheet,
                            onGenerate = onGenerateSuggestion,
                            onCancelGeneration = onCancelSuggestionGeneration,
                            onConnect = onConnect,
                            onUse = onUseSuggestion,
                            onEdit = onEditSuggestion,
                            testTag = "feed-suggestions-empty",
                        )
                    }
                }
                item(key = "modules", contentType = "modules") { ModuleDiscoveryTile(onExploreModules) }
                if (nextAlarm != null) item(key = "alarm", contentType = "alarm") {
                    Surface(onClick = onAlarms, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Alarm, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.feed_next_alarm), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(LocalTime.of(nextAlarm.hour, nextAlarm.minute).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditionQuestion(connected: Boolean, onAsk: () -> Unit, onConnect: () -> Unit) {
    Surface(onClick = if (connected) onAsk else onConnect,
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("edition-ask")) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(if (connected) R.string.edition_ask_today else R.string.edition_connect_personal), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (connected) R.string.edition_ask_today_body else R.string.edition_connect_body),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun editionAuthorLabel(item: FeedItem): String =
    if (FeedLogic.isValidOriginalReadShape(item)) stringResource(R.string.original_read_authorship_098)
    else publisherLabel(item).ifBlank { stringResource(moduleNameResource(item.kind)) }

@Composable
private fun editionTimestampLabel(item: FeedItem, timezone: String): String =
    if (FeedLogic.isValidOriginalReadShape(item))
        stringResource(R.string.original_read_created_098, detailDate(item.fetchedAtMillis, timezone))
    else itemTimestamp(item)

@Composable
private fun LeadStory(item: FeedItem, saved: Boolean, onItem: (String) -> Unit, onSave: (String) -> Unit,
    onHide: (String) -> Unit, timezone: String,
    suggestionsTarget: SuggestionTargetSnapshot097?,
    suggestionLanguage: String,
    suggestionsConnected: Boolean,
    suggestionsBusy: Boolean,
    onConnect: () -> Unit,
    onUseSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit,
    onEditSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit,
    onSuggestionSheet: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097,
    onGenerateSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097?,
    onCancelSuggestionGeneration: (String) -> Unit,
) {
    val hasMedia = item.media != null
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 360 || configuration.fontScale > 1.15f
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(180))) {
        Column {
            Surface(onClick = { onItem(item.id) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("item-" + item.id)) {
                Column {
                    Box(Modifier.fillMaxWidth().height(if (hasMedia && !compact) 200.dp else 156.dp)) {
                        PublisherImage(item, Modifier.fillMaxSize(), large = true)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .02f), Color.Transparent, Color.Black.copy(alpha = .68f)))))
                        Row(Modifier.align(Alignment.TopStart).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Surface(color = Color(0xD923242C), shape = CircleShape, modifier = Modifier.testTag("category-${item.id}")) {
                                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(moduleIcon(item.kind), null, Modifier.size(14.dp), tint = Color.White)
                                    Text(stringResource(storyCategoryResource(item)), style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                            if (item.contentType == "video" || item.contentType == "podcast") Surface(color = Color.White, shape = CircleShape) {
                                Icon(if (item.contentType == "video") Icons.Outlined.PlayArrow else Icons.Outlined.Headphones, null, Modifier.padding(6.dp).size(18.dp), tint = Color(0xFF171922))
                            }
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(editionAuthorLabel(item) + " · " + editionTimestampLabel(item, timezone),
                                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .88f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 17.dp),
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.summary.isNotBlank()) Text(item.summary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.why.isNotBlank()) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.width(2.dp).height(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Text(item.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The story itself is the primary tap target; source access is in detail and overflow.
                Spacer(Modifier.weight(1f))
                SuggestionAction097(
                    target = suggestionsTarget,
                    contextLabel = item.title,
                    language = suggestionLanguage,
                    connected = suggestionsConnected,
                    busy = suggestionsBusy,
                    onReadSnapshot = onSuggestionSheet,
                    onGenerate = onGenerateSuggestion,
                    onCancelGeneration = onCancelSuggestionGeneration,
                    onConnect = onConnect,
                    onUse = onUseSuggestion,
                    onEdit = onEditSuggestion,
                    testTag = "feed-suggestions-${item.id}",
                )
                StoryActions(item, saved, onSave, onHide)
            }
            }
        }
    }
}

@Composable
private fun CompactStory(item: FeedItem, modifier: Modifier, saved: Boolean, onSave: (String) -> Unit, onItem: (String) -> Unit, timezone: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column {
            Surface(onClick = { onItem(item.id) }, color = Color.Transparent, modifier = Modifier.testTag("item-" + item.id)) {
                Column {
                    Box(Modifier.fillMaxWidth().height(114.dp)) {
                        PublisherImage(item, Modifier.fillMaxSize())
                        if (item.contentType == "video") Surface(color = Color.Black.copy(alpha = .65f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.padding(9.dp).size(23.dp), tint = Color.White)
                        }
                    }
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StoryCategoryTag(item, Modifier.testTag("category-${item.id}"))
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(editionAuthorLabel(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (FeedLogic.isValidOriginalReadShape(item)) Text(editionTimestampLabel(item, timezone),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StoryActions(item, saved, onSave, null) }
        }
    }
}

@Composable
internal fun StoryRow(item: FeedItem, saved: Boolean, onItem: (String) -> Unit, onSave: (String) -> Unit, onHide: ((String) -> Unit)?, timestampOverride: String? = null,
    suggestionTarget: SuggestionTargetSnapshot097? = null,
    suggestionLanguage: String = "en",
    suggestionsConnected: Boolean = true,
    suggestionsBusy: Boolean = false,
    onConnect: () -> Unit = {},
    onUseSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onEditSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onSuggestionSheet: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097 = { target, _ ->
        SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.NEEDS_GENERATION, emptyList())
    },
    onGenerateSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onCancelSuggestionGeneration: (String) -> Unit = {},
    timezone: String = "",
    todayMetadata: TodayEntry? = null,
) {
    val originalReadTimezone = timezone.ifBlank { LocalRelevanceContext.current.state?.timezone.orEmpty() }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("story-card-${item.id}")) {
        Column {
            todayMetadata?.let { TodayReasonLabel090(it, originalReadTimezone) }
            Surface(onClick = { onItem(item.id) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("item-" + item.id)) {
                Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(editionAuthorLabel(item), Modifier.testTag("story-author-${item.id}"), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (timestampOverride != null && timestampOverride.isNotBlank()) Text(timestampOverride, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (timestampOverride == null && item.deadline.isNotBlank()) Text(stringResource(R.string.feed_deadline, item.deadline), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        else if (timestampOverride == null) Text(editionTimestampLabel(item, originalReadTimezone), Modifier.testTag("story-created-${item.id}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(82.dp).clip(RoundedCornerShape(16.dp))) { PublisherImage(item, Modifier.fillMaxSize()) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StoryCategoryTag(item, Modifier.testTag("category-${item.id}"))
                Spacer(Modifier.weight(1f))
                if (suggestionTarget != null) SuggestionAction097(
                    target = suggestionTarget,
                    contextLabel = item.title,
                    language = suggestionLanguage,
                    connected = suggestionsConnected,
                    busy = suggestionsBusy,
                    onReadSnapshot = onSuggestionSheet,
                    onGenerate = onGenerateSuggestion,
                    onCancelGeneration = onCancelSuggestionGeneration,
                    onConnect = onConnect,
                    onUse = onUseSuggestion,
                    onEdit = onEditSuggestion,
                    testTag = "today-suggestions-${item.id}",
                )
                StoryActions(item, saved, onSave, onHide)
            }
        }
    }
}

@Composable
private fun StoryActions(item: FeedItem, saved: Boolean, onSave: (String) -> Unit, onHide: ((String) -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalRelevanceContext.current
    val context = LocalContext.current
    val star = ctx.state?.starredStories?.any { it.itemId == item.id } == true
    val vote = ctx.state?.let { RelevanceFeedback.current(it, item) }
    RelevanceControls(item)
    IconButton(onClick = { onSave(item.id) }, modifier = Modifier.size(48.dp).testTag("save-" + item.id)) {
        Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.feed_unsave else R.string.feed_save),
            Modifier.size(21.dp), tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("story-menu-${item.id}")) {
            Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.feed_more), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
            if (FeedLogic.safeUrl(item.sourceUrl)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.detail_share)) }, onClick = { menu = false; shareResource(context, item.title, item.sourceUrl) },
                    leadingIcon = { Icon(Icons.Outlined.Share, null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.feed_open_source)) }, onClick = { menu = false; openSource(context, item.sourceUrl) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, modifier = Modifier.testTag("source-${item.id}"))
            }
            ctx.onStar?.let { toggle -> DropdownMenuItem(text = { Text(stringResource(if (star) R.string.star_following else R.string.star_follow)) },
                onClick = { menu = false; toggle(item.id) }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) }) }
            ctx.onVote?.let { rate ->
                DropdownMenuItem(text = { Text(stringResource(if (vote == false) R.string.feedback_undo_083 else R.string.feedback_less_083)) },
                    onClick = { menu = false; rate(item.id, if (vote == false) null else false) }, leadingIcon = { Icon(Icons.Outlined.NotInterested, null) })
            }
            if (onHide != null) DropdownMenuItem(text = { Text(stringResource(R.string.feed_hide)) }, onClick = { menu = false; onHide(item.id) },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
        }
    }
}

@Composable
internal fun WeatherStrip(item: FeedItem, timezone: String = "", showTimestamp: Boolean = true, onOpen: () -> Unit) {
    val weather = item.weather ?: return
    val palette = MeBotPalette.current
    Surface(onClick = onOpen, color = palette.blueSurface, shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("weather-" + item.id)) {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(weatherIcon(weather.weatherCode), null, Modifier.size(42.dp), tint = palette.onBlue)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(weather.location, style = MaterialTheme.typography.titleSmall, color = palette.onBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(weatherCondition(weather.weatherCode)), style = MaterialTheme.typography.bodySmall, color = palette.onBlue)
                Text(stringResource(R.string.edition_rain_chance, weather.precipitationChance), style = MaterialTheme.typography.labelSmall, color = palette.onBlue)
            }
            Text(stringResource(R.string.edition_temperature, weather.temperatureC.toInt()), style = MaterialTheme.typography.displaySmall, color = palette.onBlue)
        }
        if (showTimestamp) Text(weatherTiming091(item, timezone), style = MaterialTheme.typography.labelSmall, color = palette.onBlue)
        }
    }
}

@Composable
private fun ModuleDiscoveryTile(onModules: () -> Unit) {
    val colors = MeBotPalette.current
    Surface(onClick = onModules, color = colors.violetSurface, shape = RoundedCornerShape(24.dp), modifier = Modifier.testTag("edition-modules")) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp)) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp), modifier = Modifier.align(Alignment.TopStart).size(43.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.School, null, Modifier.size(23.dp), tint = colors.onViolet) }
                }
                Surface(color = colors.onViolet, shape = RoundedCornerShape(13.dp), modifier = Modifier.align(Alignment.BottomEnd).size(35.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, Modifier.size(22.dp), tint = colors.violetSurface) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.edition_modules_title), style = MaterialTheme.typography.titleMedium, color = colors.onViolet)
                Text(stringResource(R.string.edition_modules_body), style = MaterialTheme.typography.bodySmall, color = colors.onViolet)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = colors.onViolet)
        }
    }
}

private fun todaySourceKey098(item: FeedItem): String? =
    item.sourceUrl.takeIf { FeedLogic.safeUrl(it) }?.let(FeedLogic::canonicalUrl)

@Composable
private fun EditionEmpty(title: Int, body: Int, action: Int, onAction: () -> Unit) {
    AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
        FeatureIcon(Icons.Outlined.ViewDay, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface, size = 52.dp)
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Button(onClick = onAction, modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(action)) }
    }
}


internal fun resourceAction(type: String): Int = when (type) {
    "video" -> R.string.edition_watch
    "podcast" -> R.string.edition_listen
    "repository" -> R.string.edition_repository
    "course" -> R.string.edition_course
    "opportunity" -> R.string.edition_opportunity
    "event" -> R.string.edition_event
    "weather" -> R.string.edition_forecast
    else -> R.string.edition_read
}

internal fun weatherIcon(code: Int) = when (code) {
    0, 1 -> Icons.Outlined.WbSunny
    2, 3, 45, 48 -> Icons.Outlined.Cloud
    71, 73, 75, 77, 85, 86 -> Icons.Outlined.AcUnit
    95, 96, 99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.WaterDrop
}
internal fun weatherCondition(code: Int): Int = when (code) {
    0, 1 -> R.string.edition_weather_clear
    2, 3 -> R.string.edition_weather_cloudy
    45, 48 -> R.string.edition_weather_fog
    71, 73, 75, 77, 85, 86 -> R.string.edition_weather_snow
    95, 96, 99 -> R.string.edition_weather_storm
    else -> R.string.edition_weather_rain
}
