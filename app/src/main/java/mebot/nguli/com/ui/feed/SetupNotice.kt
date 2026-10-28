package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Host-only actions are supplied at the app shell, so feed/detail callers do not own global routes. */
internal data class Setup099TipHostActions(
    val eligibleUpdateAvailable: Boolean = false,
    val onBackgroundSettings: () -> Unit = {},
    val onSoftwareUpdates: () -> Unit = {},
    /** Set only from actual voice/transcription capability state; never from a cached preference. */
    val voiceSetupNeeded: Boolean = false,
    val onVoiceSetup: () -> Unit = {},
)

internal val LocalSetup099TipHostActions = staticCompositionLocalOf { Setup099TipHostActions() }

@Composable
internal fun SetupNotice(state: FeedState, connected: Boolean, page: String,
    onConnect: () -> Unit, onContext: () -> Unit, onDismiss: (String) -> Unit) {
    val host = LocalSetup099TipHostActions.current
    val tip = Setup099Logic.nextTip(
        connected = connected,
        contextEntries = state.context,
        backgroundEnabled = state.backgroundEnabled,
        eligibleUpdateAvailable = host.eligibleUpdateAvailable,
        dismissedNotices = state.dismissedNotices,
        onboardingComplete = state.onboardingComplete,
        page = page,
        nowMillis = LocalFeedClock.current,
        voiceSetupNeeded = host.voiceSetupNeeded,
    ) ?: return
    Setup099TipCard(tip, onAction = { action ->
        when (action) {
            Setup099TipAction.CONNECT_CHATGPT -> onConnect()
            Setup099TipAction.ADD_CONTEXT -> onContext()
            Setup099TipAction.BACKGROUND_SETTINGS -> host.onBackgroundSettings()
            Setup099TipAction.SOFTWARE_UPDATES -> host.onSoftwareUpdates()
            Setup099TipAction.CONFIGURE_VOICE -> host.onVoiceSetup()
        }
    }, onLater = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickGuideSheet(onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("quick-guide"),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.quick_guide_title), style = MaterialTheme.typography.headlineSmall)
            listOf(
                Triple(Icons.Outlined.GridView, R.string.quick_guide_topics, R.string.quick_guide_topics_body),
                Triple(Icons.Outlined.ChatBubbleOutline, R.string.quick_guide_answers, R.string.quick_guide_answers_body),
                Triple(Icons.Outlined.StarOutline, R.string.quick_guide_feedback, R.string.quick_guide_feedback_body),
                Triple(Icons.Outlined.Fullscreen, R.string.guide_media_084, R.string.guide_media_body_084),
                Triple(Icons.Outlined.SystemUpdate, R.string.software_updates, R.string.quick_guide_updates_body)
            ).forEach { (icon, title, body) ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, null, Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(stringResource(R.string.quick_guide_replay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("quick-guide-done")) { Text(stringResource(R.string.quick_guide_done)) }
        }
    }
}
