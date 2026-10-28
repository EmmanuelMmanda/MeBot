package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.Setup099Stage
import mebot.nguli.com.core.Setup099Tip
import mebot.nguli.com.core.Setup099TipAction
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ScreenContainer

/** One compact explanation followed by the three useful actions; navigation/persistence stay with the host. */
@Composable
internal fun Setup099Onboarding(
    setup: mebot.nguli.com.core.Setup099State,
    chatGptConnected: Boolean,
    hasConfirmedContext: Boolean,
    language: String,
    onLanguage: (String) -> Unit,
    onConnectChatGpt: () -> Unit,
    onImportContext: () -> Unit,
    onSkipContext: () -> Unit,
    onContinuePublic: () -> Unit,
    onStartFeed: () -> Unit,
    errorCode: String? = null,
    onDismissError: () -> Unit = {},
) {
    // Ephemeral guard only: a process recreation must permit retry if the durable write did not land.
    var actionPending by remember(setup.stage) { mutableStateOf(false) }
    LaunchedEffect(setup.stage, errorCode) { if (errorCode != null) actionPending = false }
    val usesSwahili = language == "sw" ||
        (language == "system" && LocalConfiguration.current.locales[0].language == "sw")
    ScreenContainer(Modifier.testTag("setup-099-onboarding")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.setup099_intro_title), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.setup099_intro_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onLanguage(if (usesSwahili) "en" else "sw") },
                modifier = Modifier.testTag("setup099-language")) {
                Icon(Icons.Outlined.Language, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (usesSwahili) R.string.english else R.string.swahili))
            }
        }

        Setup099Progress(setup.stage, chatGptConnected, hasConfirmedContext)

        errorCode?.let { FeedStatus(it, onDismiss = onDismissError) }

        when (setup.stage) {
            Setup099Stage.INTRO -> AppCard(modifier = Modifier.testTag("setup099-step-intro")) {
                Text(stringResource(R.string.setup099_step_connect), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup099_step_connect_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    onDismissError()
                    onConnectChatGpt()
                }, enabled = !actionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("setup099-connect"),
                    shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.setup099_connect_action), modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
                TextButton(onClick = {
                    if (!actionPending) {
                        onDismissError()
                        actionPending = true
                        onContinuePublic()
                    }
                }, enabled = !actionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("setup099-continue-public")) {
                    if (actionPending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.setup099_continue_public))
                }
            }
            Setup099Stage.CONNECT_CHATGPT -> AppCard(modifier = Modifier.testTag("setup099-step-connect")) {
                Text(stringResource(R.string.setup099_step_connect), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup099_step_connect_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chatGptConnected) Text(stringResource(R.string.setup099_status_done), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Button(onClick = { onDismissError(); onConnectChatGpt() }, enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .testTag("setup099-connect"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.setup099_connect_action))
                }
                TextButton(onClick = {
                    if (!actionPending) {
                        onDismissError()
                        actionPending = true
                        onContinuePublic()
                    }
                }, enabled = !actionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("setup099-continue-public")) {
                    if (actionPending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.setup099_continue_public))
                }
            }
            Setup099Stage.IMPORT_CONTEXT -> AppCard(modifier = Modifier.testTag("setup099-step-context")) {
                Text(stringResource(R.string.setup099_step_context), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup099_step_context_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.setup099_explainer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onDismissError(); onImportContext() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .testTag("setup099-import-context"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.setup099_import_action))
                }
                TextButton(onClick = {
                    if (!actionPending) {
                        onDismissError()
                        actionPending = true
                        onSkipContext()
                    }
                }, enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup099-skip-context")) {
                    Text(stringResource(R.string.setup099_skip_context))
                }
            }
            Setup099Stage.START_FEED -> AppCard(modifier = Modifier.testTag("setup099-step-feed")) {
                Text(stringResource(R.string.setup099_step_feed), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup099_step_feed_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasConfirmedContext) Text(stringResource(R.string.setup099_context_saved_title),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                else Text(stringResource(R.string.setup099_context_skipped_body),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    if (!actionPending) {
                        onDismissError()
                        actionPending = true
                        onStartFeed()
                    }
                }, enabled = !actionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .testTag("setup099-start-feed"), shape = RoundedCornerShape(16.dp)) {
                    if (actionPending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.setup099_start_action))
                }
                if (!hasConfirmedContext) TextButton(onClick = { onDismissError(); onImportContext() }, enabled = !actionPending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup099-add-context-later")) {
                    Text(stringResource(R.string.setup099_import_action))
                }
            }
            else -> Unit
        }
    }
}

/** Compact, reusable three-step marker for the setup screen and connect/import routes. */
@Composable
internal fun Setup099Progress(stage: String, chatGptConnected: Boolean, hasConfirmedContext: Boolean,
    modifier: Modifier = Modifier) {
    val complete = stage == Setup099Stage.COMPLETE
    val active = when (stage) {
        Setup099Stage.INTRO, Setup099Stage.CONNECT_CHATGPT -> 0
        Setup099Stage.IMPORT_CONTEXT -> 1
        else -> 2
    }
    val labels = listOf(R.string.setup099_step_connect, R.string.setup099_step_context, R.string.setup099_step_feed)
    val done = listOf(chatGptConnected, hasConfirmedContext, complete)
    Column(modifier.fillMaxWidth().testTag("setup099-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup099_progress, active + 1), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == active && !complete
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinearProgressIndicator(
                        progress = { if (done[index]) 1f else if (selected) .35f else 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                        color = if (selected || done[index]) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun Setup099NextFeedAction(
    hasConfirmedContext: Boolean,
    onStartFeed: () -> Unit,
    errorCode: String? = null,
    onDismissError: () -> Unit = {},
) {
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(errorCode) { if (errorCode != null) pending = false }
    AppCard(modifier = Modifier.testTag("setup099-next-feed")) {
        Text(stringResource(if (hasConfirmedContext) R.string.setup099_context_saved_title else R.string.setup099_context_skipped_title),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (hasConfirmedContext) R.string.setup099_context_saved_body else R.string.setup099_context_skipped_body),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            if (!pending) {
                onDismissError()
                pending = true
                onStartFeed()
            }
        }, enabled = !pending, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup099-next-feed-button"),
            shape = RoundedCornerShape(16.dp)) {
            if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.setup099_next_feed))
        }
    }
}

@Composable
internal fun Setup099TipCard(tip: Setup099Tip, onAction: (Setup099TipAction) -> Unit, onLater: (String) -> Unit) {
    val title = when (tip.key) {
        "missing_ai" -> R.string.setup099_tip_missing_ai_title
        "missing_context" -> R.string.setup099_tip_missing_context_title
        "background_disabled" -> R.string.setup099_tip_background_title
        "voice_setup" -> R.string.setup099_tip_voice_title
        else -> R.string.setup099_tip_update_title
    }
    val body = when (tip.key) {
        "missing_ai" -> R.string.setup099_tip_missing_ai_body
        "missing_context" -> R.string.setup099_tip_missing_context_body
        "background_disabled" -> R.string.setup099_tip_background_body
        "voice_setup" -> R.string.setup099_tip_voice_body
        else -> R.string.setup099_tip_update_body
    }
    val action = when (tip.action) {
        Setup099TipAction.CONNECT_CHATGPT -> R.string.setup099_tip_connect_action
        Setup099TipAction.ADD_CONTEXT -> R.string.setup099_tip_context_action
        Setup099TipAction.BACKGROUND_SETTINGS -> R.string.setup099_tip_background_action
        Setup099TipAction.SOFTWARE_UPDATES -> R.string.setup099_tip_update_action
        Setup099TipAction.CONFIGURE_VOICE -> R.string.setup099_tip_voice_action
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("setup-notice-${tip.key}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAction(tip.action) }, contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("setup-tip-action-${tip.key}")) {
                    Text(stringResource(action))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onLater(tip.key) }, contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("setup-tip-later-${tip.key}")) {
                    Text(stringResource(R.string.setup099_tip_later))
                }
            }
        }
    }
}
