package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.PopupProperties
import mebot.nguli.com.R
import mebot.nguli.com.feed.AiActivity
import mebot.nguli.com.ui.MeBotMark
import kotlinx.coroutines.delay

/** Shared badge: the ring exists exactly while the associated operation is active. */
@Composable
internal fun MeBotActivityBadge(activity: AiActivity?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (activity != null) CircularProgressIndicator(
            Modifier.fillMaxSize().testTag("mebot-active-ring").clearAndSetSemantics {}, strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f))
        val icon = when {
            activity == null || activity.stage in setOf("queued", "connecting", "working", "preparing") -> null
            activity.stage in setOf("downloading", "saving", "importing", "checking_file") -> Icons.Outlined.Description
            activity.source == "youtube" -> Icons.Outlined.SmartDisplay
            activity.kind == "horoscope" -> Icons.Outlined.Science
            activity.kind in setOf("music", "media", "entertainment") -> Icons.Outlined.PlayCircleOutline
            activity.kind == "weather" -> Icons.Outlined.CloudQueue
            activity.kind in setOf("opportunities", "scholarships") -> Icons.Outlined.WorkOutline
            else -> Icons.Outlined.MenuBook
        }
        if (icon == null) MeBotMark(Modifier.fillMaxSize(.62f))
        else Icon(icon, null, Modifier.fillMaxSize(.58f), tint = MaterialTheme.colorScheme.primary)
    }
}

internal fun activityLabelResource(activity: AiActivity): Int = when (activity.stage) {
    "queued" -> R.string.activity_queued
    "connecting" -> R.string.activity_connecting
    "authorizing" -> R.string.activity_authorizing
    "searching" -> if (activity.source == "youtube") R.string.activity_youtube else R.string.activity_searching
    "reading" -> R.string.activity_reading
    "thinking" -> R.string.activity_thinking
    "writing", "summarizing" -> if (activity.operation in setOf("discovery", "refresh", "search")) R.string.activity_summarizing_items else R.string.activity_writing
    "validating" -> R.string.activity_validating
    "ready" -> R.string.activity_results_ready
    "refreshing" -> R.string.activity_refreshing
    "importing" -> R.string.activity_importing
    "checking_file" -> R.string.activity_checking_file
    "downloading" -> R.string.activity_downloading
    "saving" -> R.string.activity_saving
    else -> if (activity.operation in setOf("discovery", "refresh")) R.string.activity_preparing_feed else R.string.activity_preparing
}

/** Single anchored progress surface. Expanded details overlay, never become page content. */
@Composable
internal fun AiActivityPanel(activity: AiActivity?, modifier: Modifier = Modifier, extraCount: Int = 0,
    scopeLabel: String = "", showDetails: Boolean = true) {
    if (activity == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var seconds by remember(activity.id) { mutableLongStateOf(0) }
    LaunchedEffect(activity.id) {
        while (true) {
            seconds = ((System.currentTimeMillis() - activity.startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    LaunchedEffect(showDetails) { if (!showDetails) expanded = false }
    val label = stringResource(activityLabelResource(activity))
    val resultCount = activity.steps.lastOrNull { it.count != null }?.count
    val operation = stringResource(when (activity.operation) {
        "discovery", "refresh" -> R.string.activity_scope_discovery
        "search" -> R.string.activity_scope_search
        else -> R.string.workspace_title
    })
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val anchorWidth = maxWidth
        Surface(onClick = { if (showDetails) expanded = !expanded }, shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("ai-activity-panel")
                .semantics { stateDescription = label }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MeBotActivityBadge(activity, Modifier.size(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    val detail = when {
                        resultCount != null -> stringResource(R.string.activity_verified_count, resultCount)
                        activity.source.isNotBlank() -> activity.source
                        else -> stringResource(R.string.activity_seconds, seconds)
                    }
                    Text(listOf(scopeLabel.ifBlank { operation }, detail).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (extraCount > 0) Text("+$extraCount", style = MaterialTheme.typography.labelSmall)
                if (showDetails) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(R.string.activity_details), Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded && showDetails, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false),
            modifier = Modifier.width(anchorWidth).heightIn(max = 380.dp).testTag("activity-details-popup")) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(scopeLabel.ifBlank { operation }, style = MaterialTheme.typography.titleSmall)
                activity.contextEntries?.let { count ->
                    Text(stringResource(if (count > 0) R.string.activity_preferences_used else R.string.activity_module_defaults,
                        count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val steps = activity.steps.ifEmpty { listOf(mebot.nguli.com.feed.AiActivityStep(activity.stage, activity.source)) }
                steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (index == steps.lastIndex) Icons.Outlined.ArrowForward else Icons.Outlined.Check,
                            null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (step.count != null) stringResource(R.string.activity_verified_count, step.count)
                                else stringResource(activityLabelResource(activity.copy(stage = step.stage, source = step.source))),
                                style = MaterialTheme.typography.bodySmall)
                            if (step.source.isNotBlank()) Text(step.source, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (activity.summary.isNotBlank()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.activity_provider_update), style = MaterialTheme.typography.labelSmall)
                    Text(activity.summary.take(600), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = false }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.activity_less))
                }
            }
        }
    }
}
