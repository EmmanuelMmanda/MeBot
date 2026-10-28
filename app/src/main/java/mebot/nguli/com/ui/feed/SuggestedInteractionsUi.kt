package mebot.nguli.com.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.feed.SuggestionGenerationHandle097
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.security.ProtectedWindowRegistration
import kotlinx.coroutines.delay
import java.util.Locale

/** Compact Suggestions entry point shared by feed, detail, and conversation composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestionAction097(
    target: SuggestionTargetSnapshot097?,
    contextLabel: String,
    language: String,
    connected: Boolean,
    busy: Boolean,
    onReadSnapshot: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097,
    onGenerate: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097?,
    onLoadMore: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onCancelGeneration: (String) -> Unit,
    onConnect: () -> Unit,
    onUse: (SuggestionTargetSnapshot097, String, String) -> Unit,
    onEdit: (SuggestionTargetSnapshot097, String, String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "suggestions",
) {
    var opened by remember { mutableStateOf<OpenedSuggestion097?>(null) }
    var actionHandled by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val openLabel = stringResource(R.string.suggestions_open)

    // Keep the entry point available for a valid destination even when there are no AI
    // suggestions yet. Only an explicit tap on this icon may start the one-shot request;
    // composition, scrolling and snapshot reads stay side-effect free.
    fun requestGeneration(current: OpenedSuggestion097): OpenedSuggestion097 {
        if (current.generationRequestId != null || busy) return current
        val handle = onGenerate(current.snapshot.target, current.language)
        return if (handle == null) {
            current.copy(snapshot = onReadSnapshot(current.snapshot.target, current.language))
        } else {
            current.copy(
                snapshot = current.snapshot.copy(
                    status = SuggestionSheetStatus097.GENERATING,
                    options = emptyList(),
                    requestId = handle.requestId,
                ),
                generationRequestId = handle.requestId,
                generationJob = handle.job,
                cancelGenerationOnDismiss = handle.cancelOnDismiss,
            )
        }
    }

    fun attachExistingGeneration(current: OpenedSuggestion097): OpenedSuggestion097 {
        val handle = onGenerate(current.snapshot.target, current.language)
            ?: return current.copy(snapshot = onReadSnapshot(current.snapshot.target, current.language))
        return current.copy(
            snapshot = current.snapshot.copy(requestId = handle.requestId),
            generationRequestId = handle.requestId,
            generationJob = handle.job,
            cancelGenerationOnDismiss = handle.cancelOnDismiss,
        )
    }

    fun requestMore(current: OpenedSuggestion097): OpenedSuggestion097 {
        if (current.generationRequestId != null || busy || !connected || !current.snapshot.canLoadMore) return current
        val handle = onLoadMore(current.snapshot.target, current.language)
        return if (handle == null) current.copy(snapshot = onReadSnapshot(current.snapshot.target, current.language))
        else current.copy(
            snapshot = current.snapshot.copy(loadingMore = true, loadMoreFailureCode = null, requestId = handle.requestId),
            generationRequestId = handle.requestId,
            generationJob = handle.job,
            cancelGenerationOnDismiss = handle.cancelOnDismiss,
        )
    }

    if (target != null) {
        IconButton(
            onClick = {
                keyboard?.hide()
                actionHandled = false
                val snapshot = onReadSnapshot(target, language)
                val next = OpenedSuggestion097(snapshot, contextLabel, language)
                // A cache miss is handled at the moment the person asks for suggestions,
                // not while rendering a card or inferring intent from a scroll. The VM
                // still owns target validation, request budgets, deduplication and cancel.
                opened = when (snapshot.status) {
                    SuggestionSheetStatus097.NEEDS_GENERATION -> requestGeneration(next)
                    SuggestionSheetStatus097.GENERATING -> attachExistingGeneration(next)
                    else -> next
                }
            },
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("$testTag-open"),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = openLabel, modifier = Modifier.size(21.dp))
        }
    }

    val session = opened
    if (session != null) {
        val latestGeneration = rememberUpdatedState(session.generationRequestId to session.cancelGenerationOnDismiss)
        // A lock curtain, navigation, or parent teardown can dispose this sheet without an
        // onDismissRequest. Fence that in-flight request as well as explicit dismissals.
        DisposableEffect(session.snapshot.target) {
            onDispose {
                latestGeneration.value.let { (requestId, shouldCancel) -> if (shouldCancel) requestId?.let(onCancelGeneration) }
            }
        }
        val renderedTargetChanged = target != session.snapshot.target
        val status = if (renderedTargetChanged) SuggestionSheetStatus097.STALE_TARGET else session.snapshot.status

        // The list stays frozen while the user reads. It changes only after they explicitly
        // request generation, and only from the same target/revision. A moved target is never
        // silently rebound to a different story, module, or conversation.
        LaunchedEffect(target, session.snapshot.target, session.generationRequestId) {
            val current = opened ?: return@LaunchedEffect
            if (target != current.snapshot.target) {
                if (current.cancelGenerationOnDismiss) current.generationRequestId?.let(onCancelGeneration)
                if (current.snapshot.status != SuggestionSheetStatus097.STALE_TARGET || current.generationRequestId != null) {
                    opened = current.copy(
                        snapshot = current.snapshot.copy(
                            status = SuggestionSheetStatus097.STALE_TARGET,
                            options = emptyList(),
                            requestId = null,
                        ),
                        generationRequestId = null,
                        generationJob = null,
                        cancelGenerationOnDismiss = false,
                    )
                }
            } else if (current.generationRequestId != null && current.generationJob != null) {
                val requestId = current.generationRequestId
                current.generationJob.join()
                val stillOpen = opened
                if (stillOpen?.generationRequestId == requestId) {
                    val updated = onReadSnapshot(stillOpen.snapshot.target, stillOpen.language)
                    opened = stillOpen.copy(snapshot = updated, generationRequestId = null, generationJob = null)
                }
            }
        }

        // Cache validity is bounded. If the saved AI set expires while the sheet remains open,
        // remove those expired questions and actions at the deadline; never silently swap in a
        // different set. The refreshed read is local-only and does not contact the provider.
        LaunchedEffect(session.snapshot.target, session.snapshot.validUntilMillis, session.snapshot.status) {
            val deadline = session.snapshot.validUntilMillis ?: return@LaunchedEffect
            if (session.snapshot.status != SuggestionSheetStatus097.READY) return@LaunchedEffect
            val waitMillis = (deadline - System.currentTimeMillis() + 1L).coerceAtLeast(1L)
            delay(waitMillis)
            val current = opened ?: return@LaunchedEffect
            if (current.snapshot.target == session.snapshot.target &&
                current.snapshot.validUntilMillis == deadline &&
                current.snapshot.status == SuggestionSheetStatus097.READY) {
                val refreshed = onReadSnapshot(current.snapshot.target, current.language)
                if (refreshed.status != SuggestionSheetStatus097.READY) {
                    opened = current.copy(snapshot = refreshed)
                }
            }
        }

        fun startGeneration() {
            val current = opened ?: return
            opened = requestGeneration(current)
        }
        fun startMore() {
            val current = opened ?: return
            opened = requestMore(current)
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun dismiss() {
            // Fence a pending one-shot before dismissing state; disposal remains a
            // fallback for lock/navigation teardown, not the only cancellation edge.
            opened?.let { if (it.cancelGenerationOnDismiss) it.generationRequestId?.let(onCancelGeneration) }
            opened = null
        }

        ModalBottomSheet(
            onDismissRequest = { dismiss() },
            sheetState = sheetState,
            modifier = Modifier.testTag("$testTag-sheet"),
        ) {
            ProtectedWindowRegistration()
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                    .testTag("$testTag-content"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            session.contextLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("$testTag-context"),
                        )
                    }
                    TextButton(
                        onClick = { dismiss() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-close"),
                    ) { Text(stringResource(R.string.suggestions_close)) }
                }

                when {
                    renderedTargetChanged || status == SuggestionSheetStatus097.STALE_TARGET -> {
                        SuggestionStatusMessage097(
                            tag = "$testTag-stale-note",
                            text = stringResource(R.string.suggestions_stale_target),
                            error = true,
                        )
                    }
                    status == SuggestionSheetStatus097.GENERATING -> {
                        SuggestionGenerationProgress097("$testTag-generating")
                    }
                    status == SuggestionSheetStatus097.UNAVAILABLE -> when {
                        // A disconnected profile should always get the actionable connect
                        // explanation, even if the provider snapshot reports `offline` as
                        // the reason. FeedStatus's generic offline row has no sheet-scoped
                        // semantics tag and can suggest a network retry instead of setup.
                        !connected -> SuggestionConnectNotice097(
                            tag = "$testTag-unavailable-note",
                            text = stringResource(R.string.suggestions_connect_to_prepare),
                            connectLabel = stringResource(R.string.suggestions_connect),
                            onConnect = { dismiss(); onConnect() },
                            connectTag = "$testTag-connect",
                        )
                        session.snapshot.failureCode == "unauthorized" -> SuggestionConnectNotice097(
                            tag = "$testTag-unavailable-note",
                            text = stringResource(R.string.feed_error_unauthorized),
                            connectLabel = stringResource(R.string.suggestions_connect),
                            onConnect = { dismiss(); onConnect() },
                            connectTag = "$testTag-connect",
                        )
                        session.snapshot.failureCode in setOf("chatgpt_reauth", "chatgpt_permission", "chatgpt_capability", "chatgpt_unsupported", "chatgpt_models", "chatgpt_no_models") ->
                            FeedStatus(session.snapshot.failureCode.orEmpty(), onConnect = { dismiss(); onConnect() })
                        session.snapshot.failureCode in setOf("offline", "rate_limit", "chatgpt_rate_limit") ->
                            FeedStatus(session.snapshot.failureCode.orEmpty(), onRetry = { startGeneration() })
                        else -> SuggestionStatusMessage097(
                            tag = "$testTag-unavailable-note",
                            text = stringResource(R.string.suggestions_unavailable),
                        )
                    }
                    status == SuggestionSheetStatus097.NEEDS_GENERATION -> {
                        SuggestionGenerationPrompt097(
                            testTag = testTag,
                            title = stringResource(R.string.suggestions_none_ready),
                            body = stringResource(R.string.suggestions_generate_disclosure),
                            action = stringResource(R.string.suggestions_generate),
                            connected = connected,
                            busy = busy || actionHandled,
                            onConnect = { dismiss(); onConnect() },
                            onGenerate = { startGeneration() },
                        )
                    }
                    status == SuggestionSheetStatus097.INCOMPLETE -> {
                        SuggestionGenerationPrompt097(
                            testTag = testTag,
                            title = stringResource(R.string.suggestions_incomplete),
                            body = stringResource(R.string.suggestions_generate_disclosure),
                            action = stringResource(R.string.suggestions_retry),
                            connected = connected,
                            busy = busy || actionHandled,
                            onConnect = { dismiss(); onConnect() },
                            onGenerate = { startGeneration() },
                        )
                    }
                    status == SuggestionSheetStatus097.ERROR -> {
                        val code = session.snapshot.failureCode.orEmpty()
                        when {
                            code == "suggestion_changed" -> SuggestionStatusMessage097(
                                tag = "$testTag-stale-note",
                                text = stringResource(R.string.suggestions_stale_target),
                                error = true,
                            )
                            code == "suggestion_unavailable" -> SuggestionStatusMessage097(
                                tag = "$testTag-unavailable-note",
                                text = stringResource(R.string.suggestions_unavailable),
                            )
                            code in setOf("chatgpt_reauth", "chatgpt_permission", "chatgpt_capability", "chatgpt_unsupported", "chatgpt_models", "chatgpt_no_models") ->
                                FeedStatus(code, onConnect = { dismiss(); onConnect() })
                            code == "unauthorized" -> SuggestionConnectNotice097(
                                tag = "$testTag-unavailable-note",
                                text = stringResource(R.string.feed_error_unauthorized),
                                connectLabel = stringResource(R.string.suggestions_connect),
                                onConnect = { dismiss(); onConnect() },
                                connectTag = "$testTag-connect",
                            )
                            code in setOf("limit_reached", "quota") -> FeedStatus(code)
                            code in setOf("offline", "rate_limit", "chatgpt_rate_limit") -> FeedStatus(
                                code,
                                onRetry = { startGeneration() },
                            )
                            else -> SuggestionGenerationPrompt097(
                                testTag = testTag,
                                title = stringResource(if (code == "suggestion_incomplete") R.string.suggestions_incomplete else R.string.suggestions_error),
                                body = stringResource(R.string.suggestions_retry_disclosure),
                                action = stringResource(R.string.suggestions_retry),
                                connected = connected,
                                busy = busy || actionHandled,
                                onConnect = { dismiss(); onConnect() },
                                onGenerate = { startGeneration() },
                            )
                        }
                    }
                    status == SuggestionSheetStatus097.LIMIT_REACHED -> SuggestionStatusMessage097(
                        tag = "$testTag-limit-note", text = stringResource(R.string.suggestions_limit_099), error = true)
                    status == SuggestionSheetStatus097.READY && !hasFiveDistinctQuestions(session.snapshot) -> {
                        SuggestionGenerationPrompt097(
                            testTag = testTag,
                            title = stringResource(R.string.suggestions_incomplete),
                            body = stringResource(R.string.suggestions_generate_disclosure),
                            action = stringResource(R.string.suggestions_retry),
                            connected = connected,
                            busy = busy || actionHandled,
                            onConnect = { dismiss(); onConnect() },
                            onGenerate = { startGeneration() },
                        )
                    }
                    status == SuggestionSheetStatus097.READY -> {
                        Text(
                            stringResource(R.string.suggestions_ai_generated),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("$testTag-provenance"),
                        )
                        if (!connected) {
                            SuggestionConnectNotice097(
                                tag = "$testTag-offline-note",
                                text = stringResource(R.string.suggestions_saved_offline),
                                connectLabel = stringResource(R.string.suggestions_connect),
                                onConnect = { dismiss(); onConnect() },
                                connectTag = "$testTag-connect",
                            )
                        } else if (busy) {
                            SuggestionStatusMessage097(
                                tag = "$testTag-busy",
                                text = stringResource(R.string.suggestions_busy),
                            )
                        }
                        session.snapshot.options.forEachIndexed { index, option ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth().testTag("$testTag-option-$index"),
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Text(
                                        option.questionText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("$testTag-question-$index"),
                                    )
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                                        TextButton(
                                            onClick = {
                                                if (!actionHandled && !busy) {
                                                    actionHandled = true
                                                    dismiss()
                                                    onEdit(session.snapshot.target, option.id, session.language)
                                                }
                                            },
                                            enabled = !busy && !actionHandled,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-edit-$index"),
                                        ) {
                                            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(stringResource(R.string.suggestions_edit))
                                        }
                                        Button(
                                            onClick = {
                                                if (!actionHandled && !busy && connected) {
                                                    actionHandled = true
                                                    dismiss()
                                                    onUse(session.snapshot.target, option.id, session.language)
                                                }
                                            },
                                            enabled = connected && !busy && !actionHandled,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("$testTag-send-$index"),
                                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(17.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(stringResource(R.string.suggestions_send))
                                        }
                                    }
                                }
                            }
                        }
                        if (session.snapshot.loadingMore) SuggestionGenerationProgress097("$testTag-loading-more") {
                            Text(stringResource(R.string.suggestions_loading_more_099),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        session.snapshot.loadMoreFailureCode?.let { code ->
                            if (code == "suggestion_limit" || !session.snapshot.canLoadMore) {
                                SuggestionStatusMessage097("$testTag-limit-note", stringResource(R.string.suggestions_limit_099))
                            } else {
                                SuggestionStatusMessage097("$testTag-more-error", stringResource(R.string.suggestions_more_error_099), error = true)
                            }
                        }
                        if (session.snapshot.canLoadMore) {
                            TextButton(
                                onClick = { startMore() },
                                enabled = connected && !busy && !actionHandled && !session.snapshot.loadingMore,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$testTag-load-more"),
                            ) {
                                Text(stringResource(if (session.snapshot.loadMoreFailureCode == null)
                                    R.string.suggestions_load_more_099 else R.string.suggestions_more_retry_099))
                            }
                        } else SuggestionStatusMessage097("$testTag-limit-note", stringResource(R.string.suggestions_limit_099))
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionGenerationProgress097(testTag: String, content: @Composable () -> Unit = {
    Text(stringResource(R.string.suggestions_generating),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer)
}) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            content()
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SuggestionStatusMessage097(tag: String, text: String, error: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuggestionConnectNotice097(
    tag: String,
    text: String,
    connectLabel: String,
    onConnect: () -> Unit,
    connectTag: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp).testTag(connectTag)) {
                Text(connectLabel)
            }
        }
    }
}

@Composable
private fun SuggestionGenerationPrompt097(
    testTag: String,
    title: String,
    body: String,
    action: String,
    connected: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onGenerate: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("$testTag-prompt"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SuggestionStatusMessage097(tag = "$testTag-status", text = title)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (connected) {
            Button(
                onClick = onGenerate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$testTag-generate"),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(action)
            }
        } else {
            TextButton(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$testTag-connect"),
            ) {
                Text(stringResource(R.string.suggestions_connect))
            }
        }
    }
}

private fun hasFiveDistinctQuestions(snapshot: SuggestionSheetSnapshot097): Boolean {
    if (snapshot.status != SuggestionSheetStatus097.READY || snapshot.options.size < 5) return false
    val unique = snapshot.options.map { normalizeQuestion097(it.questionText) }.toSet()
    return unique.size >= 5 && unique.none(String::isBlank)
}

private fun normalizeQuestion097(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private data class OpenedSuggestion097(
    val snapshot: SuggestionSheetSnapshot097,
    val contextLabel: String,
    val language: String,
    val generationRequestId: String? = null,
    val generationJob: kotlinx.coroutines.Job? = null,
    val cancelGenerationOnDismiss: Boolean = false,
)
