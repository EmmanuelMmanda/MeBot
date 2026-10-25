package mebot.nguli.com.ui.feed

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun FeedOnboarding(state: FeedState, language: String, onLanguage: (String) -> Unit, onStart: () -> Unit, onConnect: () -> Unit,
    onPreferences: (Set<String>, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(true) }) =
    MeBotOnboarding(state, language, onLanguage, onStart, onConnect, onPreferences)

@Composable
fun SavedFeed(state: FeedState, onItem: (String) -> Unit, onSave: (String) -> Unit, onFeed: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("saved") }
    var module by rememberSaveable { mutableStateOf("all") }
    var collection by rememberSaveable { mutableStateOf("all") }
    var oldest by rememberSaveable { mutableStateOf(false) }
    var editCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionName by rememberSaveable { mutableStateOf("") }
    val callbacks = LocalRelevanceContext.current
    val focusManager = LocalFocusManager.current
    val starred = state.starredStories.map { it.itemId }.toSet()
    val available = state.items.filter { if (tab == "starred") it.id in starred else it.id in state.savedIds }
    val availableModules = state.modules.filter { m -> available.any { it.moduleId == m.id } }
    LaunchedEffect(availableModules.map { it.id }) { if (module != "all" && availableModules.none { it.id == module }) module = "all" }
    val collections = state.savedCollections.values.distinct().sorted()
    LaunchedEffect(collections) { if (collection !in listOf("all", "unfiled") && collection !in collections.map { "name:$it" }) collection = "all" }
    val filtered = remember(available, tab, query, module, collection, oldest, state.savedCollections, state.starredStories) {
        available.filter { item ->
            (module == "all" || item.moduleId == module) &&
                (tab != "saved" || collection == "all" || (collection == "unfiled" && item.id !in state.savedCollections) || state.savedCollections[item.id]?.let { "name:$it" } == collection) &&
                (query.isBlank() || listOf(item.title, item.summary, item.sourceName, state.savedCollections[item.id].orEmpty()).any { it.contains(query.trim(), ignoreCase = true) })
        }.sortedBy { item -> state.starredStories.find { it.itemId == item.id }?.starredAtMillis
            ?: item.publishedAtMillis.takeIf { it > 0 } ?: item.fetchedAtMillis }.let { if (oldest) it else it.reversed() }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query, tab, module, collection, oldest) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("saved-library"),
        contentPadding = PaddingValues(horizontal = MeBotTokens.ScreenPadding, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "library-header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle(stringResource(R.string.library_title), stringResource(R.string.library_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == "saved", { editCollectionId = null; tab = "saved" }, label = { Text(stringResource(R.string.library_all)) },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("library-saved"))
                    FilterChip(tab == "starred", { editCollectionId = null; tab = "starred" }, label = { Text(stringResource(R.string.library_starred)) },
                        leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("library-starred"))
                }
                OutlinedTextField(query, { editCollectionId = null; query = it.take(200) }, singleLine = true,
                    label = { Text(stringResource(R.string.library_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().testTag("library-search"), shape = RoundedCornerShape(18.dp))
                HorizontalRail(listOf("all" to stringResource(R.string.library_all_types)) + availableModules.map { it.id to moduleName(it) },
                    itemKey = { it.first }, selectedKey = module) { (id, label) ->
                    FilterChip(module == id, { editCollectionId = null; module = id }, label = { Text(label) })
                }
                if (tab == "saved" && collections.isNotEmpty()) HorizontalRail(
                    listOf("all" to stringResource(R.string.library_all_collections), "unfiled" to stringResource(R.string.library_unfiled)) + collections.map { "name:$it" to it },
                    itemKey = { it.first }, selectedKey = collection) { (id, label) ->
                    FilterChip(collection == id, { editCollectionId = null; collection = id }, label = { Text(label) }, modifier = Modifier.testTag("library-filter-collection-$id"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.library_count, filtered.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { editCollectionId = null; oldest = !oldest }, modifier = Modifier.testTag("library-sort")) {
                        Icon(Icons.Outlined.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (oldest) R.string.library_oldest else R.string.library_recent))
                    }
                }
            }
        }
        if (available.isEmpty()) item(key = "empty") {
            FeedEmpty(if (tab == "starred") R.string.library_following_empty else R.string.feed_saved_empty,
                if (tab == "starred") R.string.library_following_empty_body else R.string.feed_saved_empty_body,
                R.string.feed_back_feed, if (tab == "starred") Icons.Outlined.StarOutline else Icons.Outlined.BookmarkBorder, onFeed)
        } else if (filtered.isEmpty()) item(key = "no-match") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_empty_filter))
                TextButton(onClick = { editCollectionId = null; query = ""; module = "all"; collection = "all" }) { Text(stringResource(R.string.library_reset)) }
            }
        }
        items(filtered, key = { it.id }) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StoryRow(item, item.id in state.savedIds, onItem, onSave, null)
                if (tab == "starred") StarManagement(item)
                if (item.id in state.savedIds && callbacks.onCollection != null) TextButton(onClick = {
                    focusManager.clearFocus()
                    editCollectionId = item.id; collectionName = state.savedCollections[item.id].orEmpty()
                }, modifier = Modifier.testTag("library-collection-${item.id}")) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(state.savedCollections[item.id] ?: stringResource(R.string.library_collection))
                }
                if (editCollectionId == item.id) {
                    val clean = collectionName.trim()
                    val valid = clean.length <= 40 && clean.none(Char::isISOControl) &&
                        (clean.isBlank() || clean in collections || collections.size < 30)
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.library_collection), style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(collectionName, { collectionName = it.take(41) }, singleLine = true,
                                label = { Text(stringResource(R.string.library_collection_hint)) }, isError = !valid,
                                modifier = Modifier.fillMaxWidth().testTag("collection-name"))
                            if (!valid) Text(stringResource(R.string.library_collection_error), color = MaterialTheme.colorScheme.error)
                            if (collections.isNotEmpty()) HorizontalRail(collections, itemKey = { it }) { name ->
                                SuggestionChip(onClick = { collectionName = name }, label = { Text(name) })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { focusManager.clearFocus(); editCollectionId = null }) { Text(stringResource(R.string.cancel)) }
                                TextButton(enabled = valid, onClick = {
                                    callbacks.onCollection?.invoke(item.id, clean); focusManager.clearFocus(); editCollectionId = null
                                }) { Text(stringResource(R.string.library_collection_apply)) }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun AskScreen(ui: FeedUiState, itemId: String?, onAsk: (String) -> Unit, onConnect: () -> Unit) {
    var question by rememberSaveable(itemId) { mutableStateOf(ui.question.orEmpty()) }
    LaunchedEffect(ui.question) { ui.question?.let { question = it } }
    val item = ui.state?.items?.find { it.id == itemId }
    val context = LocalContext.current
    val why = stringResource(R.string.feed_ask_why)
    val explain = stringResource(R.string.feed_ask_explain)
    val next = stringResource(R.string.feed_ask_next)
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_ask), item?.title ?: stringResource(R.string.feed_ask_context))
        if (!ui.connected) {
            FeedEmpty(R.string.feed_connect, R.string.feed_ask_connect, R.string.feed_connect, Icons.Outlined.ChatBubbleOutline, onConnect)
        } else {
            if (ui.state?.connection?.mode == "chatgpt") ChatGptPlanBadge()
            if (item != null) HorizontalRail(listOf(explain, why, next), itemKey = { it }) { prompt ->
                AssistChip(onClick = { onAsk(prompt) }, enabled = !ui.asking,
                    label = { Text(prompt) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            OutlinedTextField(value = question, onValueChange = { if (it.length <= 2000) question = it }, modifier = Modifier.fillMaxWidth().testTag("ask-question"),
                label = { Text(stringResource(R.string.feed_ask_question)) }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(18.dp), enabled = !ui.asking)
            Button(onClick = { onAsk(question.trim()) }, enabled = question.isNotBlank() && !ui.asking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("ask-submit"), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(if (ui.asking) R.string.feed_ask_thinking else R.string.feed_ask_send))
            }
            ui.error?.let { FeedStatus(it, onConnect = onConnect) }
            if (ui.answer != null) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MeBotMark(Modifier.size(25.dp)); Text(stringResource(R.string.feed_answer), style = MaterialTheme.typography.titleMedium)
                }
                Text(ui.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ask-answer"))
                ui.answerCitations.distinctBy { it.url }.forEach { source -> SourceLink(source) { openSource(context, source.url) } }
            } else if (!ui.asking && ui.error == null) {
                Text(stringResource(R.string.feed_ask_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceLink(source: FeedCitation, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.title.ifBlank { source.url }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (source.title.isNotBlank()) Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.feed_open_source), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FeedEmpty(title: Int, body: Int, action: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onAction: () -> Unit) {
    AppCard {
        FeatureIcon(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 50.dp)
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(action)) }
    }
}

