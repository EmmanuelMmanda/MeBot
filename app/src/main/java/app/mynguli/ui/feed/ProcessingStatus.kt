package app.mynguli.ui.feed

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.mynguli.feed.AiActivity
import app.mynguli.feed.FeedUiState
import app.mynguli.feed.ResourceOperations

/** The only app-owned AI progress surface, outside screen scroll containers. */
@Composable
internal fun ProcessingStatus(ui: FeedUiState, page: String, itemId: String?, modifier: Modifier = Modifier) {
    val resources by ResourceOperations.state.collectAsState()
    val conversation = ui.state?.conversations?.find { it.id == ui.activeConversationId }
    val scope = when (page) {
        "item" -> itemId
        "ai" -> conversation?.itemId ?: conversation?.id ?: "general"
        else -> null
    }
    val preferred = ui.activities.lastOrNull { scope != null && it.scope == scope }
        ?: ui.activities.lastOrNull { it.operation != "discovery" }
        ?: ui.activities.lastOrNull()
    val busyStage = when {
        page == "connect" && ui.chatGpt.phase == "waiting" -> "authorizing"
        ui.connecting -> "connecting"
        ui.asking || ui.detailLoading || ui.searching -> "working"
        ui.refreshing -> "refreshing"
        page == "ai" && ui.importWorking -> "importing"
        else -> null
    }
    // A fallback reports the request being prepared, not invented provider activity.
    val fallback = remember(busyStage != null) {
        if (busyStage != null) AiActivity("preparing", "app", "", "local", stage = busyStage) else null
    }
    val current = preferred ?: resources.values.lastOrNull() ?: fallback?.copy(stage = busyStage ?: "working")
    AiActivityPanel(current, modifier, extraCount = (ui.activities.size + resources.size - if (preferred != null || resources.isNotEmpty()) 1 else 0).coerceAtLeast(0))
}
