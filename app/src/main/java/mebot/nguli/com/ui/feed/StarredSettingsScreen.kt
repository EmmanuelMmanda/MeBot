package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.ui.*

/** The existing persisted per-story schedule, never a second worker or an extra AI allowance. */
@Composable
fun StarredSettingsScreen(ui: FeedUiState, vm: FeedViewModel, onBackground: () -> Unit, onConnection: () -> Unit) {
    val state = ui.state ?: return
    ScreenContainer {
        ScreenTitle(stringResource(R.string.starred_settings_092), stringResource(R.string.starred_intro_092))
        FeedSettingsRow(Icons.Outlined.Sync, stringResource(R.string.starred_background_092),
            if (state.backgroundEnabled || state.notifications) stringResource(R.string.starred_background_on_092, state.refreshHours)
            else stringResource(R.string.starred_background_off_092), onBackground)
        Text(stringResource(R.string.starred_timing_092), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ui.connected) OutlinedButton(onClick = onConnection, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.starred_ai_needed_092))
        }
        if (state.starredStories.isEmpty()) Text(stringResource(R.string.starred_empty_092), modifier = Modifier.testTag("starred-settings-empty"))
        state.starredStories.forEach { star ->
            val item = state.items.find { it.id == star.itemId } ?: return@forEach
            AppCard {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                if (state.modules.none { it.id == item.moduleId && it.enabled }) Text(stringResource(R.string.starred_module_off_092), style = MaterialTheme.typography.bodySmall)
                StarManagement(item, showGuidance = false)
                TextButton(onClick = { vm.toggleStar(item.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("starred-remove-${item.id}")) {
                    Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.starred_remove_092))
                }
            }
        }
        ui.error?.let { FeedStatus(it) }
    }
}
