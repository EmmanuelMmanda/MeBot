package app.mynguli.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.feed.AiActivity
import app.mynguli.ui.MeBotMark
import kotlinx.coroutines.delay

@Composable
internal fun AiActivityPanel(activity: AiActivity?, modifier: Modifier = Modifier, extraCount: Int = 0) {
    if (activity == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var seconds by remember(activity.id) { mutableLongStateOf(0) }
    LaunchedEffect(activity.id) {
        while (true) {
            seconds = ((System.currentTimeMillis() - activity.startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val label = stringResource(when (activity.stage) {
        "queued" -> R.string.activity_queued
        "connecting" -> R.string.activity_connecting
        "authorizing" -> R.string.activity_authorizing
        "searching" -> if (activity.source == "youtube") R.string.activity_youtube else R.string.activity_searching
        "reading" -> R.string.activity_reading
        "thinking" -> R.string.activity_thinking
        "writing" -> R.string.activity_writing
        "refreshing" -> R.string.activity_refreshing
        "importing" -> R.string.activity_importing
        "downloading" -> R.string.activity_downloading
        "saving" -> R.string.activity_saving
        else -> R.string.activity_preparing
    })
    Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag("ai-activity-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.fillMaxSize().clearAndSetSemantics {}, strokeWidth = 2.dp)
                    if (activity.stage in setOf("queued", "connecting", "working")) MeBotMark(Modifier.size(22.dp))
                    else Icon(when {
                        activity.stage in setOf("downloading", "saving", "importing") -> Icons.Outlined.Description
                        activity.source == "youtube" -> Icons.Outlined.SmartDisplay
                        activity.kind == "horoscope" -> Icons.Outlined.AutoAwesome
                        activity.kind in setOf("music", "media", "entertainment") -> Icons.Outlined.PlayCircleOutline
                        activity.kind == "weather" -> Icons.Outlined.CloudQueue
                        else -> Icons.Outlined.MenuBook
                    }, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(if (extraCount > 0) stringResource(R.string.activity_elapsed_more, seconds, extraCount)
                        else stringResource(R.string.activity_elapsed, seconds),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(R.string.activity_details), Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Text(activity.summary.take(400).ifBlank { stringResource(R.string.activity_details_body) },
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
