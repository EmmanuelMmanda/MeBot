package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.NotInterested
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*

data class RelevanceUiContext(val state: FeedState? = null, val onVote: ((String, Boolean?) -> Unit)? = null)
val LocalRelevanceContext = staticCompositionLocalOf { RelevanceUiContext() }

/** Usefulness, not popularity: no vote counts and no social ranking. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelevanceControls(item: FeedItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalRelevanceContext.current
    val state = ctx.state ?: return
    val onVote = ctx.onVote ?: return
    val selected = RelevanceFeedback.current(state, item)
    FlowRow(modifier.fillMaxWidth().testTag("relevance-${item.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        listOf(true to R.string.feedback_relevant, false to R.string.feedback_not_relevant).forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onVote(item.id, if (selected == value) null else value) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(if (value) Icons.Outlined.CheckCircleOutline else Icons.Outlined.NotInterested,
                    null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 44.dp).testTag("relevance-${item.id}-$value"))
        }
        if (selected != null) TextButton(onClick = { onVote(item.id, null) }, contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.heightIn(min = 44.dp).testTag("relevance-undo-${item.id}")) { Text(stringResource(R.string.feedback_undo)) }
    }
}
