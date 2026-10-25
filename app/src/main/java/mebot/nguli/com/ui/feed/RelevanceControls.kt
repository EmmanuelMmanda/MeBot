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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelevanceControls(item: FeedItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalRelevanceContext.current
    val state = ctx.state ?: return
    val onStar = ctx.onStar ?: return
    val star = state.starredStories.find { it.itemId == item.id }
    FlowRow(modifier.fillMaxWidth().testTag("relevance-${item.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = star != null, onClick = { onStar(item.id) },
            label = { Text(stringResource(if (star == null) R.string.star_follow else R.string.star_following), style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(if (star == null) Icons.Outlined.StarOutline else Icons.Outlined.Star, null, Modifier.size(17.dp)) },
            modifier = Modifier.heightIn(min = 44.dp).testTag("star-${item.id}"))
        if (!compact) Text(stringResource(if (star == null) R.string.star_hint else R.string.star_active_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StarManagement(item: FeedItem) {
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
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.star_every_hours, star.checkEveryHours)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                StarredStories.cadences.forEach { hours -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.star_every_hours, hours)) },
                    onClick = { menu = false; configure(item.id, star.paused, hours) }) }
            }
        }
        Text(stringResource(R.string.star_budget_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (star.lastCheckedAtMillis > 0) Text(stringResource(R.string.star_last_checked, relativeTime(star.lastCheckedAtMillis)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
