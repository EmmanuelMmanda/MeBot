package app.mynguli.ui.feed

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
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.ui.*
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
    onRefreshModule: ((String?) -> Unit)? = null) {
    val state = ui.state ?: return
    var filter by rememberSaveable { mutableStateOf("all") }
    val enabled = remember(state.modules) { state.modules.filter { it.enabled } }
    LaunchedEffect(enabled.map { it.id }) { if (filter != "all" && enabled.none { it.id == filter }) filter = "all" }
    val refreshSelected: () -> Unit = { if (onRefreshModule == null) onRefresh() else onRefreshModule(filter.takeUnless { it == "all" }) }
    val now = LocalFeedClock.current
    val edition = remember(state.items, state.modules, state.hiddenIds, state.timezone, state.viewedAtMillis, state.readingHistoryEnabled, state.editionSequence, now / 60_000) {
        FeedLogic.edition(state, now)
    }
    val items = remember(edition, filter) { edition.filter { filter == "all" || it.moduleId == filter } }
    val weather = items.firstOrNull { it.weather != null }
    val stories = remember(items) { items.filter { it.weather == null } }
    val context = LocalContext.current
    val askPrompt = stringResource(R.string.edition_ask_today_prompt)
    val youLabel = stringResource(R.string.feed_you_title)
    val layout = LocalConfiguration.current
    val compact = layout.screenWidthDp < 360 || layout.fontScale > 1.15f
    // Each filter keeps its own page and position through navigation and rotation.
    // Rendering stays bounded to the viewport even after several pages are opened.
    val pages = rememberSaveableStateHolder()
    // A Feed-tab tap belongs to the current filter, not to every saved filter page.
    var consumedTopRequest by rememberSaveable { mutableIntStateOf(returnToTopRequest) }
    pages.SaveableStateProvider(filter) {
        var visibleCount by rememberSaveable { mutableIntStateOf(5) }
        val visible = remember(stories, visibleCount) { stories.take(visibleCount) }
        val listState = rememberLazyListState()
        LaunchedEffect(returnToTopRequest) {
            if (returnToTopRequest != consumedTopRequest) {
                consumedTopRequest = returnToTopRequest
                visibleCount = 5
                listState.scrollToItem(0)
                refreshSelected()
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
                            MeBotActivityBadge(if (ui.refreshing) ui.activities.lastOrNull { it.operation == "discovery" } ?: app.mynguli.feed.AiActivity("feed-preparing", filter, "", "refresh", "preparing") else null, Modifier.size(34.dp))
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
                        val choices = listOf("all" to stringResource(R.string.edition_for_you)) + enabled.map { it.id to moduleName(it) }
                        HorizontalRail(choices, itemKey = { it.first }, selectedKey = filter, modifier = Modifier.testTag("feed-filter-rail")) { (id, label) ->
                            FilterChip(filter == id, { filter = id }, label = { Text(label) }, shape = CircleShape,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("filter-$id"))
                        }
                    }
                }
                if (ui.error != null) item(key = "status", contentType = "status") {
                    FeedStatus(ui.error, if (!ui.refreshing) refreshSelected else null, onDismissError, onConnect)
                }

                when {
                    ui.refreshing && items.isEmpty() -> Unit // Activity is rendered once, above the page.
                    enabled.isEmpty() -> item(key = "empty", contentType = "empty") {
                        EditionEmpty(R.string.feed_no_modules, R.string.feed_no_modules_body, R.string.feed_manage_modules, onModules)
                    }
                    items.isEmpty() -> {
                        item(key = "empty", contentType = "empty") {
                            EditionEmpty(R.string.edition_empty_title, if (ui.connected) R.string.edition_empty_connected else R.string.edition_empty_body,
                                if (ui.connected) R.string.feed_load else R.string.feed_connect, if (ui.connected) refreshSelected else onConnect)
                        }
                        if (!ui.connected) item(key = "public-updates", contentType = "action") {
                            TextButton(onClick = refreshSelected, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edition_public_updates)) }
                        }
                    }
                    else -> {
                        if (visible.isNotEmpty()) item(key = "story-" + visible.first().id, contentType = "lead") {
                            LeadStory(visible.first(), visible.first().id in state.savedIds, onItem, onSave, onHide,
                                onExplain = { id, prompt -> if (ui.connected) onPrompt(id, prompt) else onConnect() })
                        }
                        if (weather != null) item(key = "weather-" + weather.id, contentType = "weather") {
                            WeatherStrip(weather) { onItem(weather.id) }
                        }
                        item(key = "question", contentType = "question") { EditionQuestion(ui.connected, { onPrompt(null, askPrompt) }, onConnect) }
                        if (visible.size > 1) item(key = "closer-look", contentType = "closer-look") {
                            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                SectionHeader(stringResource(R.string.edition_across_modules))
                                if (visible.size >= 3 && compact) {
                                    visible.subList(1, 3).forEach { item -> key(item.id) { StoryRow(item, item.id in state.savedIds, onItem, onSave, onHide) } }
                                } else if (visible.size >= 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    visible.subList(1, 3).forEach { item ->
                                        key(item.id) { CompactStory(item, Modifier.weight(1f), onItem) { openSource(context, item.sourceUrl) } }
                                    }
                                } else key(visible[1].id) { StoryRow(visible[1], visible[1].id in state.savedIds, onItem, onSave, onHide) }
                            }
                        }
                        items(visible.drop(if (visible.size >= 3) 3 else 2), key = { "story-" + it.id }, contentType = { "story" }) { item ->
                            StoryRow(item, item.id in state.savedIds, onItem, onSave, onHide)
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
                                } else Icon(Icons.Outlined.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                if (items.isEmpty() && ui.connected) item(key = "question", contentType = "question") { EditionQuestion(true, { onPrompt(null, askPrompt) }, onConnect) }
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
private fun LeadStory(item: FeedItem, saved: Boolean, onItem: (String) -> Unit, onSave: (String) -> Unit,
    onHide: (String) -> Unit, onExplain: (String, String) -> Unit) {
    val context = LocalContext.current
    val explain = stringResource(R.string.feed_ask_explain)
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
                            Surface(color = Color(0xD923242C), shape = CircleShape) {
                                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(moduleIcon(item.kind), null, Modifier.size(14.dp), tint = Color.White)
                                    Text(stringResource(moduleNameResource(item.kind)), style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                            if (item.contentType == "video" || item.contentType == "podcast") Surface(color = Color.White, shape = CircleShape) {
                                Icon(if (item.contentType == "video") Icons.Outlined.PlayArrow else Icons.Outlined.Headphones, null, Modifier.padding(6.dp).size(18.dp), tint = Color(0xFF171922))
                            }
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.sourceName.ifBlank { stringResource(R.string.feed_entertainment) } + " · " + itemTimestamp(item),
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
                if (item.sourceUrl.isNotBlank()) TextButton(onClick = { openSource(context, item.sourceUrl) }, modifier = Modifier.weight(1f).testTag("source-" + item.id)) {
                    Text(stringResource(resourceAction(item.contentType)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.width(5.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(15.dp))
                }
                else Spacer(Modifier.weight(1f))
                if (!compact) {
                IconButton(onClick = { onExplain(item.id, explain) }, modifier = Modifier.testTag("explain-" + item.id)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.edition_explain), Modifier.size(21.dp))
                }
                StoryActions(item, saved, onSave, onHide)
                }
            }
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onExplain(item.id, explain) }, modifier = Modifier.testTag("explain-" + item.id)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.edition_explain), Modifier.size(21.dp))
                }
                StoryActions(item, saved, onSave, onHide)
            }
            }
        }
    }
}

@Composable
private fun CompactStory(item: FeedItem, modifier: Modifier, onItem: (String) -> Unit, onOpen: () -> Unit) {
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
                        Text(stringResource(moduleNameResource(item.kind)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(item.sourceName.ifBlank { itemTimestamp(item) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (item.sourceUrl.isNotBlank()) TextButton(onClick = onOpen, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 5.dp).testTag("source-" + item.id)) {
                Text(stringResource(resourceAction(item.contentType)), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(14.dp))
            }
        }
    }
}

@Composable
internal fun StoryRow(item: FeedItem, saved: Boolean, onItem: (String) -> Unit, onSave: (String) -> Unit, onHide: ((String) -> Unit)?) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Surface(onClick = { onItem(item.id) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("item-" + item.id)) {
                Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(item.sourceName.ifBlank { stringResource(moduleNameResource(item.kind)) }, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (item.deadline.isNotBlank()) Text(stringResource(R.string.feed_deadline, item.deadline), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        else Text(itemTimestamp(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(82.dp).clip(RoundedCornerShape(16.dp))) { PublisherImage(item, Modifier.fillMaxSize()) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.sourceUrl.isNotBlank()) TextButton(onClick = { openSource(context, item.sourceUrl) }, modifier = Modifier.testTag("source-" + item.id)) {
                    Text(stringResource(resourceAction(item.contentType)), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(14.dp))
                }
                else Text(stringResource(R.string.feed_entertainment), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                StoryActions(item, saved, onSave, onHide)
            }
        }
    }
}

@Composable
private fun StoryActions(item: FeedItem, saved: Boolean, onSave: (String) -> Unit, onHide: ((String) -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { onSave(item.id) }, modifier = Modifier.testTag("save-" + item.id)) {
        Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.feed_unsave else R.string.feed_save),
            Modifier.size(21.dp), tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (onHide != null) Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.feed_more), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.feed_hide)) }, onClick = { menu = false; onHide(item.id) },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
        }
    }
}

@Composable
internal fun WeatherStrip(item: FeedItem, onOpen: () -> Unit) {
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
        Text(stringResource(R.string.edition_forecast_time, itemTimestamp(item)), style = MaterialTheme.typography.labelSmall, color = palette.onBlue)
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
