package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R

internal fun preferencePromptTitle083(category: String): Int = when(category) {
    "work" -> R.string.preferences_work_083
    "goal" -> R.string.preferences_goal_083
    "preference" -> R.string.preferences_style_083
    else -> R.string.preferences_interest_083
}
internal fun preferencePromptHint083(category: String): Int = when(category) {
    "work" -> R.string.preferences_work_hint_083
    "goal" -> R.string.preferences_goal_hint_083
    "preference" -> R.string.preferences_style_hint_083
    "interest" -> R.string.preferences_interest_hint_083
    else -> R.string.preferences_details_hint_083
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreferencePrompts083(enabled: Boolean, onAdd: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.preferences_details_083), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.preferences_help_083), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("interest", "work", "goal", "preference").forEach { category ->
                AssistChip(onClick = { onAdd(category) }, enabled = enabled, label = { Text(stringResource(preferencePromptTitle083(category))) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("context-prompt-$category"))
            }
        }
    }
}
