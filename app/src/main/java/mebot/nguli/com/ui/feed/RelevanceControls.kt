package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*

data class RelevanceUiContext(
    val state: FeedState? = null,
    val onVote: ((String, Boolean?) -> Unit)? = null,
    val onStar: ((String) -> Unit)? = null,
    val onConfigureStar: ((String, Boolean, Int) -> Unit)? = null,
    val onCollection: ((String, String) -> Unit)? = null,
)
val LocalRelevanceContext = staticCompositionLocalOf { RelevanceUiContext() }

/** Following is explicit and independent from a bookmark or negative relevance feedback. */
@Composable
internal fun RelevanceControls(item: FeedItem, modifier: Modifier = Modifier) {
    val ctx = LocalRelevanceContext.current
    val state = ctx.state ?: return
    val onStar = ctx.onStar ?: return
    val starred = state.starredStories.any { it.itemId == item.id }
    IconButton(onClick = { onStar(item.id) }, modifier = modifier.size(48.dp).testTag("star-${item.id}")
        .semantics { selected = starred }) {
        Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            stringResource(if (starred) R.string.star_remove_label else R.string.star_add_label), Modifier.size(22.dp),
            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StarManagement(item: FeedItem, showGuidance: Boolean = true) {
    val ctx = LocalRelevanceContext.current
    val star = ctx.state?.starredStories?.find { it.itemId == item.id } ?: return
    val configure = ctx.onConfigureStar ?: return
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("star-settings-${item.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (star.paused) R.string.star_paused else R.string.star_check_refresh), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            Switch(checked = !star.paused, onCheckedChange = { configure(item.id, !it, star.checkEveryHours) },
                modifier = Modifier.testTag("star-active-${item.id}"))
        }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("star-frequency-${item.id}")) { Text(stringResource(R.string.star_every_hours, star.checkEveryHours)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
                StarredStories.cadences.forEach { hours -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.star_every_hours, hours)) }, modifier = Modifier.testTag("star-frequency-${item.id}-$hours"),
                    onClick = { menu = false; configure(item.id, star.paused, hours) }) }
            }
        }
        if (showGuidance) Text(stringResource(R.string.star_budget_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (star.lastCheckedAtMillis > 0) Text(stringResource(R.string.star_last_checked, relativeTime(star.lastCheckedAtMillis)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
