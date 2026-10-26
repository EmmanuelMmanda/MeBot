package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle

@Composable
internal fun ImportFeedback(ui: FeedUiState) {
    if (ui.importWorking && ui.importPreview == null) {
        Column(Modifier.fillMaxWidth().testTag("import-checking"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.imports_checking), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val summary = when (ui.importSummary) {
        "context" -> R.string.imports_context_done
        "backup" -> R.string.imports_backup_done
        else -> null
    }
    if (summary != null) Row(Modifier.fillMaxWidth().testTag("import-success"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The validated file is reviewed before any replacement is requested. */
@Composable
internal fun FeedImportReview(ui: FeedUiState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val preview = ui.importPreview ?: return
    val contextImport = preview.kind == "context"
    AlertDialog(
        modifier = Modifier.testTag("import-review"),
        onDismissRequest = { if (!ui.importWorking) onCancel() },
        icon = { Icon(Icons.Outlined.FileDownload, null) },
        title = { Text(stringResource(if (contextImport) R.string.imports_context_review else R.string.imports_backup_review)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(preview.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("import-file-name"))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportCount(stringResource(R.string.imports_entries), preview.entries)
                    if (!contextImport || preview.modules > 0) ImportCount(stringResource(R.string.imports_modules), preview.modules)
                    if (!contextImport) {
                        ImportCount(stringResource(R.string.imports_saved), preview.saved)
                        ImportCount(stringResource(R.string.imports_conversations), preview.conversations)
                    }
                }
                HorizontalDivider()
                Text(stringResource(if (contextImport) R.string.imports_context_impact else R.string.imports_backup_impact),
                    style = MaterialTheme.typography.bodyMedium)
                if (contextImport) Text(stringResource(if (preview.modules > 0) R.string.imports_modules_replace else R.string.imports_modules_keep),
                    style = MaterialTheme.typography.bodyMedium)
                if (!contextImport) Text(stringResource(R.string.imports_local_tools_keep), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.error?.let { FeedStatus(it) }
                if (ui.importWorking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("import-applying"))
                    Text(stringResource(R.string.imports_applying), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = onConfirm, enabled = !ui.importWorking,
            modifier = Modifier.testTag("import-confirm")) { Text(stringResource(R.string.imports_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !ui.importWorking,
            modifier = Modifier.testTag("import-cancel")) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ImportCount(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun FeedDataContent(
    ui: FeedUiState,
    onExport: () -> Unit, onImport: () -> Unit, onRestoreHidden: () -> Unit, onLocalData: () -> Unit,
    onHistoryChanged: (Boolean) -> Unit, onClearHistory: () -> Unit,
    onDismissError: () -> Unit, onConfirmImport: () -> Unit, onCancelImport: () -> Unit,
    onResetFeedback: (() -> Unit)? = null,
) {
    var confirmFeedbackReset by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_data), stringResource(R.string.feed_backup_note))
        if (ui.importPreview == null) ui.error?.let { FeedStatus(it, onDismiss = onDismissError) }
        ImportFeedback(ui)
        DataActionRow(Icons.Outlined.FileUpload, stringResource(R.string.feed_export_backup), !ui.importWorking,
            "data-export", onExport)
        DataActionRow(Icons.Outlined.FileDownload, stringResource(R.string.feed_import_backup), !ui.importWorking,
            "data-import", onImport)
        ReadingHistoryCard(ui, onHistoryChanged, onClearHistory, onDismissError)
        if(onResetFeedback!=null) AppCard {
            Text(stringResource(R.string.feedback_reset_description),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={confirmFeedbackReset=true},enabled=ui.state?.relevanceSignals?.isNotEmpty()==true,modifier=Modifier.testTag("feedback-reset")){Text(stringResource(R.string.feedback_reset))}
        }
        if (ui.diagnostics.isNotEmpty()) {
            TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }, modifier = Modifier.testTag("data-diagnostics")) {
                Text(stringResource(R.string.imports_diagnostics))
                Spacer(Modifier.width(8.dp))
                Icon(if (diagnosticsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (diagnosticsExpanded) AppCard(modifier = Modifier.testTag("data-diagnostics-content")) {
                Text(stringResource(R.string.imports_diagnostics_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.diagnostics.takeLast(8).asReversed().forEach { diagnostic ->
                    Text(stringResource(R.string.imports_diagnostic_record, diagnostic.operation, diagnostic.code, diagnostic.correlationId),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(confirmFeedbackReset)AlertDialog(onDismissRequest={confirmFeedbackReset=false},title={Text(stringResource(R.string.feedback_reset_title))},text={Text(stringResource(R.string.feedback_reset_body))},confirmButton={ mebot.nguli.com.security.ProtectedWindowRegistration();TextButton(onClick={confirmFeedbackReset=false;onResetFeedback?.invoke()},modifier=Modifier.testTag("feedback-reset-confirm")){Text(stringResource(R.string.feedback_reset))}},dismissButton={TextButton(onClick={confirmFeedbackReset=false}){Text(stringResource(R.string.cancel))}})
    FeedImportReview(ui, onConfirmImport, onCancelImport)
}

@Composable
private fun DataActionRow(icon: ImageVector, title: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(23.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadingHistoryCard(ui: FeedUiState, onChanged: (Boolean) -> Unit, onClear: () -> Unit, onDismissError: () -> Unit) {
    val state = ui.state ?: return
    var pendingToggle by remember { mutableStateOf<Boolean?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.readingHistoryEnabled, state.viewedAtMillis, ui.error, pendingToggle, pendingClear) {
        if (pendingToggle == state.readingHistoryEnabled || ui.error != null) pendingToggle = null
        if (state.viewedAtMillis.isEmpty() || ui.error != null) pendingClear = false
    }
    val enabled = !ui.importWorking && pendingToggle == null && !pendingClear
    val historyLabel = stringResource(R.string.imports_history_title)
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(historyLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(state.readingHistoryEnabled, onCheckedChange = { selected ->
                onDismissError(); pendingToggle = selected; onChanged(selected)
            }, enabled = enabled, modifier = Modifier.testTag("data-history-switch").semantics { contentDescription = historyLabel })
        }
        Text(stringResource(if (state.readingHistoryEnabled) R.string.imports_history_body else R.string.imports_history_off),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(pluralStringResource(R.plurals.imports_history_count, state.viewedAtMillis.size, state.viewedAtMillis.size),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("data-history-count"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { confirmClear = true },
                enabled = enabled && state.viewedAtMillis.isNotEmpty(), modifier = Modifier.testTag("data-history-clear")) {
                Text(stringResource(R.string.imports_history_clear))
            }
            if (pendingToggle != null || pendingClear) Text(stringResource(R.string.delivery_saving), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.history_clear_title)) },
        text = { Text(stringResource(R.string.history_clear_body)) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { confirmClear = false; onDismissError(); pendingClear = true; onClear() },
            modifier = Modifier.testTag("history-clear-confirm")) { Text(stringResource(R.string.imports_history_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.testTag("history-clear-cancel")) { Text(stringResource(R.string.cancel)) } })
}
