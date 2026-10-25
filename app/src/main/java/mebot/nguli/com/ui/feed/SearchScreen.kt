package mebot.nguli.com.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedCitation
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.MeBotTokens
import mebot.nguli.com.ui.ScreenTitle

@Composable
fun SearchScreen(ui: FeedUiState, onSearch: (String, String?) -> Unit, onItem: (String) -> Unit,
    onSave: (String) -> Unit, onConnect: () -> Unit, onCancel: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(ui.searchQuery) }
    var selectedModule by rememberSaveable { mutableStateOf(ui.searchModuleId) }
    var syncedQuery by rememberSaveable { mutableStateOf(ui.searchQuery) }
    var syncedModule by rememberSaveable { mutableStateOf(ui.searchModuleId) }
    val modules = ui.state?.modules.orEmpty().filter { it.enabled && it.kind !in setOf("weather", "horoscope") }
    val moduleId = selectedModule.takeIf { id -> modules.any { it.id == id } }
    val keyboard = LocalSoftwareKeyboardController.current
    var visibleCount by rememberSaveable(ui.searchQuery, ui.searchModuleId) { mutableIntStateOf(10) }
    val results = ui.searchResults.distinctBy { it.id }
    val canSearch = ui.connected && modules.isNotEmpty() && query.isNotBlank() && !ui.searching
    LaunchedEffect(ui.searchQuery, ui.searchModuleId) {
        // A restored unsent draft can differ from the last submitted search.
        // Only a new submission should replace it with the ViewModel's query.
        if (ui.searchQuery != syncedQuery || ui.searchModuleId != syncedModule) {
            if (ui.searchQuery.isNotBlank()) { query = ui.searchQuery; selectedModule = ui.searchModuleId }
            syncedQuery = ui.searchQuery
            syncedModule = ui.searchModuleId
        }
    }
    fun submit() { if (canSearch) { keyboard?.hide(); onSearch(query.trim(), moduleId) } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxSize().imePadding().testTag("search-list"),
            contentPadding = PaddingValues(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "title") { ScreenTitle(stringResource(R.string.search_title), stringResource(R.string.search_description)) }
            item(key = "form") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(query, { if (it.length <= 2000) query = it },
                        modifier = Modifier.fillMaxWidth().testTag("search-query"),
                        label = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(22.dp)) },
                        singleLine = true, enabled = !ui.searching, shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }))
                    val choices = listOf("all" to stringResource(R.string.search_all_modules)) + modules.map { it.id to moduleName(it) }
                    HorizontalRail(choices, itemKey = { it.first }, selectedKey = moduleId ?: "all", modifier = Modifier.testTag("search-filter-rail")) { (id, label) ->
                        FilterChip(selected = (moduleId ?: "all") == id, onClick = { selectedModule = id.takeUnless { it == "all" } }, enabled = !ui.searching,
                            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("search-filter-$id"))
                    }
                    Button(onClick = { submit() }, enabled = canSearch,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("search-submit"), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.search_submit))
                    }
                }
            }
            if (!ui.connected) item(key = "connect") {
                AppCard {
                    Text(stringResource(R.string.search_connect_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.search_connect_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onConnect, modifier = Modifier.testTag("search-connect")) { Text(stringResource(R.string.feed_connect)) }
                }
            } else if (modules.isEmpty()) item(key = "modules-empty") {
                SearchMessage(R.string.search_modules_empty, R.string.search_modules_empty_body, "search-modules-empty")
            }
            if (ui.searching) item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("search-cancel")) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
            ui.searchError?.let { error -> item(key = "error") {
                FeedStatus(error, onRetry = if (canSearch) ({ submit() }) else null,
                    onConnect = onConnect)
            } }
            if (results.isNotEmpty()) {
                item(key = "results-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pluralStringResource(R.plurals.search_result_count, results.size, results.size), style = MaterialTheme.typography.titleMedium)
                        Text(ui.searchQuery, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(results.take(visibleCount), key = { "search-result-${it.id}" }, contentType = { "search-result" }) { item ->
                    SearchResult(item, item.id in ui.state?.savedIds.orEmpty(), onItem, onSave)
                }
                if (visibleCount < results.size) item(key = "more") {
                    OutlinedButton(onClick = { visibleCount += 10 }, modifier = Modifier.fillMaxWidth().testTag("search-more")) {
                        Text(stringResource(R.string.feed_show_more))
                    }
                }
            } else if (ui.searchCompleted && !ui.searching && ui.searchError == null) item(key = "empty") {
                SearchMessage(R.string.search_no_results, R.string.search_no_results_body, "search-no-results")
            } else if (!ui.searching && ui.searchError == null && ui.connected && modules.isNotEmpty()) item(key = "intro") {
                Text(stringResource(R.string.search_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("search-intro"))
            }
        }
    }
}

@Composable
private fun SearchMessage(title: Int, body: Int, tag: String) {
    AppCard(modifier = Modifier.testTag(tag)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchResult(item: FeedItem, saved: Boolean, onItem: (String) -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var showSources by rememberSaveable(item.id) { mutableStateOf(false) }
    val sources = remember(item.sourceUrl, item.sourceName, item.citations) {
        (listOf(FeedCitation(item.sourceName, item.sourceUrl)) + item.citations)
            .filter { FeedLogic.safeUrl(it.url) }.distinctBy { FeedLogic.canonicalUrl(it.url) }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("search-result-${item.id}")) {
        Column {
            Surface(onClick = { onItem(item.id) }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("search-item-${item.id}")) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(moduleNameResource(item.kind)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(item.sourceName, itemTimestamp(item)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.media != null) Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp))) {
                        PublisherImage(item, Modifier.fillMaxSize())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (FeedLogic.safeUrl(item.sourceUrl)) TextButton(onClick = { openSource(context, item.sourceUrl) }, modifier = Modifier.weight(1f).testTag("search-source-${item.id}")) {
                    Text(stringResource(resourceAction(item.contentType)), Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(15.dp))
                } else Spacer(Modifier.weight(1f))
                if (sources.size > 1) IconButton(onClick = { showSources = !showSources }, modifier = Modifier.testTag("search-sources-${item.id}")) {
                    Icon(Icons.Outlined.Link, pluralStringResource(R.plurals.search_source_count, sources.size, sources.size), Modifier.size(21.dp))
                }
                IconButton(onClick = { onSave(item.id) }, modifier = Modifier.testTag("search-save-${item.id}")) {
                    Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        stringResource(if (saved) R.string.feed_unsave else R.string.feed_save), Modifier.size(21.dp))
                }
            }
            if (showSources) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                sources.forEachIndexed { index, source ->
                    TextButton(onClick = { openSource(context, source.url) }, modifier = Modifier.fillMaxWidth().testTag("search-citation-${item.id}-$index")) {
                        Text(source.title.ifBlank { source.url }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}
