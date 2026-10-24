package app.mynguli.ui.feed

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.ui.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun FeedOnboarding(state: FeedState, language: String, onLanguage: (String) -> Unit, onStart: () -> Unit, onConnect: () -> Unit,
    onPreferences: (Set<String>, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(true) }) =
    MeBotOnboarding(state, language, onLanguage, onStart, onConnect, onPreferences)

@Composable
fun SavedFeed(state: FeedState, onItem: (String) -> Unit, onSave: (String) -> Unit, onFeed: () -> Unit) {
    val saved = state.items.filter { it.id in state.savedIds }
    var visibleCount by rememberSaveable { mutableIntStateOf(10) }
    val visible = saved.take(visibleCount)
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_saved_title), stringResource(R.string.feed_saved_body))
        if (saved.isEmpty()) FeedEmpty(R.string.feed_saved_empty, R.string.feed_saved_empty_body, R.string.feed_back_feed, Icons.Outlined.BookmarkBorder, onFeed)
        else Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
            Column { visible.forEachIndexed { index, item ->
                StoryRow(item, true, onItem, onSave, null)
                if (index < visible.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            } }
        }
        if (visibleCount < saved.size) OutlinedButton(onClick = { visibleCount += 10 }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.feed_show_more)) }
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
                if (ui.asking) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
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

@Composable
private fun FeedLoading() {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            repeat(3) { index -> Box(Modifier.fillMaxWidth(if (index == 2) .6f else 1f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) }
            Text(stringResource(R.string.feed_refreshing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
