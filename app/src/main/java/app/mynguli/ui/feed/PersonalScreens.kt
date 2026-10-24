package app.mynguli.ui.feed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mynguli.BuildConfig
import app.mynguli.R
import app.mynguli.core.*
import app.mynguli.feed.FeedUiState
import app.mynguli.feed.FeedViewModel
import app.mynguli.feed.FeedJson
import app.mynguli.ui.*
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun YouScreen(ui: FeedUiState, onContext: () -> Unit, onConnect: () -> Unit, onDelivery: () -> Unit, onSettings: () -> Unit, onData: () -> Unit) {
    val state = ui.state ?: return
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_you_title))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(25.dp)) {
            Row(Modifier.fillMaxWidth().padding(23.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = CircleShape, modifier = Modifier.size(60.dp)) { Box(contentAlignment = Alignment.Center) { MeBotMark(Modifier.size(40.dp)) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(state.name.ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.headlineSmall)
                    Text(state.region.ifBlank { stringResource(R.string.feed_context_summary) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        FeedSettingsRow(Icons.Outlined.PersonOutline, stringResource(R.string.feed_context), stringResource(R.string.feed_context_summary), onContext)
        FeedSettingsRow(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.feed_connection),
            if (ui.connected && state.connection.mode == "chatgpt") stringResource(R.string.chatgpt_using_plan)
            else if (ui.connected) "${stringResource(R.string.feed_connected)} · ${state.connection.model}" else stringResource(R.string.feed_not_connected), onConnect)
        FeedSettingsRow(Icons.Outlined.NotificationsNone, stringResource(R.string.feed_delivery),
            if (state.notifications) stringResource(R.string.feed_delivery_on, state.refreshHours) else stringResource(R.string.feed_delivery_off), onDelivery)
        FeedSettingsRow(Icons.Outlined.Palette, stringResource(R.string.mb_preferences), onClick = onSettings)
        FeedSettingsRow(Icons.Outlined.FolderOpen, stringResource(R.string.feed_data), onClick = onData)
        Text(stringResource(R.string.feed_about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun ContextScreen(ui: FeedUiState, vm: FeedViewModel, onDone: () -> Unit) {
    val state = ui.state ?: return
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var region by rememberSaveable(state.region) { mutableStateOf(state.region) }
    var timezone by rememberSaveable(state.timezone) { mutableStateOf(state.timezone) }
    var entriesJson by rememberSaveable(state.context) {
        mutableStateOf(FeedJson.encodeToString(state.context.ifEmpty { listOf(ContextEntry(text = "")) }))
    }
    val entries = remember(entriesJson) { FeedJson.decodeFromString<List<ContextEntry>>(entriesJson) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pastedContext by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    var desiredContextJson by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val canEdit = !ui.contextSaving && !ui.importWorking
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareContextImport(uri) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportContext(uri) }
    LaunchedEffect(state.name, state.region, state.timezone, state.context, pending, ui.contextSaving) {
        val desired = desiredContextJson?.let { FeedJson.decodeFromString<List<ContextEntry>>(it) }
        val saved = state.name == name.trim() && state.region == region.trim() && state.timezone == timezone.trim() && state.context == desired
        if (pending && !ui.contextSaving) {
            pending = false; desiredContextJson = null
            if (saved) onDone()
            // Otherwise the restored draft outlived its saving ViewModel.
            // Keep it editable without claiming an unmatched save succeeded.
        }
    }
    LaunchedEffect(ui.error) { if (ui.error != null) pending = false }
    LaunchedEffect(ui.importPreview) {
        if (showPaste && ui.importPreview != null) { showPaste = false; pastedContext = "" }
    }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_context), stringResource(R.string.feed_context_body))
        ImportFeedback(ui)
        ContextImportActions(enabled = canEdit,
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onPaste = { vm.clearMessage(); showPaste = true })
        ContextPromptActions(enabled = canEdit)
        OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.feed_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("context-name"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
        SectionLabel(stringResource(R.string.feed_context_entries))
        entries.forEachIndexed { index, entry ->
            key(entry.id) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(entry.text, { text ->
                        if (text.length <= 1000) {
                            entriesJson = FeedJson.encodeToString(entries.map { if (it.id == entry.id) it.copy(text = text) else it })
                            localError = false
                        }
                    }, label = { Text(stringResource(R.string.feed_context_entry, index + 1)) }, minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag(if (index == 0) "context-edit" else "context-edit-${entry.id}"),
                        shape = RoundedCornerShape(18.dp), enabled = canEdit)
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val scopeNames = entry.scopes.sorted().map { stringResource(moduleNameResource(it)) }.joinToString(", ")
                        Text(if (scopeNames.isEmpty()) stringResource(R.string.feed_context_unshared) else stringResource(R.string.feed_context_used_by, scopeNames),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { entriesJson = FeedJson.encodeToString(entries.filterNot { it.id == entry.id }); localError = false }, enabled = canEdit,
                            modifier = Modifier.testTag("context-remove-${entry.id}")) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.feed_context_remove), Modifier.size(20.dp))
                        }
                    }
                    TextButton(onClick = { scopeEntryId = entry.id }, enabled = canEdit,
                        modifier = Modifier.testTag("context-scopes-" + entry.id)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.edition_context_choose), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        OutlinedButton(onClick = { entriesJson = FeedJson.encodeToString(entries + ContextEntry(text = "")); localError = false }, enabled = canEdit && entries.size < 80,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-add"), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.feed_context_add))
        }
        Text(stringResource(R.string.feed_context_scopes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(region, { region = it.take(100) }, label = { Text(stringResource(R.string.feed_region)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("context-region"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
        OutlinedTextField(timezone, { timezone = it.take(80); localError = false }, label = { Text(stringResource(R.string.feed_timezone)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("context-timezone"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
        OutlinedButton(onClick = { exportLauncher.launch("mebot-context-${LocalDate.now()}.json") }, enabled = canEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-export")) { Text(stringResource(R.string.feed_context_export)) }
        Text(stringResource(R.string.imports_context_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (localError) Text(stringResource(R.string.feed_context_invalid), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (ui.importPreview == null) ui.error?.let { FeedStatus(it) }
        Button(onClick = {
            val now = System.currentTimeMillis()
            val updated = entries.filter { it.text.isNotBlank() }.map { entry ->
                val text = entry.text.trim()
                entry.copy(text = text, confirmed = true, updatedAtMillis = if (state.context.find { it.id == entry.id }?.text != text) now else entry.updatedAtMillis)
            }
            localError = updated.size > 80 || updated.any { it.text.length > 1000 } || (timezone.isNotBlank() && runCatching { ZoneId.of(timezone.trim()) }.isFailure)
            if (!localError) {
                desiredContextJson = FeedJson.encodeToString(updated); pending = true
                vm.clearMessage()
                vm.saveContext(name.trim(), region.trim(), timezone.trim(), updated)
            }
        }, enabled = canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("context-save"), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.feed_context_save)) }
    }
    val scopeEntry = entries.find { it.id == scopeEntryId }
    if (scopeEntry != null) AlertDialog(onDismissRequest = { scopeEntryId = null },
        title = { Text(stringResource(R.string.edition_context_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edition_context_choose_body), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    ModuleCatalog.kinds.filter { it != "weather" }.forEach { kind ->
                        val selected = kind in scopeEntry.scopes
                        Surface(onClick = {
                            entriesJson = FeedJson.encodeToString(entries.map { entry ->
                                if (entry.id == scopeEntry.id) entry.copy(scopes = if (selected) entry.scopes - kind else entry.scopes + kind) else entry
                            })
                        }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                Text(stringResource(moduleNameResource(kind)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scopeEntryId = null }) { Text(stringResource(R.string.done)) } })
    if (showPaste) ContextPasteDialog(
        text = pastedContext,
        onTextChanged = { pastedContext = it; vm.clearMessage() },
        working = ui.importWorking,
        error = ui.error,
        onDismiss = { showPaste = false },
        onReview = vm::prepareContextText,
    )
    FeedImportReview(ui, vm::confirmImport, vm::cancelImport)
}

@Composable
fun DeliveryScreen(ui: FeedUiState, vm: FeedViewModel, onDone: () -> Unit) =
    DeliveryPreferencesScreen(ui, vm, onDone)

@Composable
fun FeedDataScreen(ui: FeedUiState, vm: FeedViewModel, onLocalData: () -> Unit) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportFeedBackup(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareFeedBackup(uri) }
    FeedDataContent(ui,
        onExport = { export.launch("mebot-feed-${LocalDate.now()}.json") },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        onRestoreHidden = vm::restoreHidden, onLocalData = onLocalData,
        onHistoryChanged = vm::setReadingHistory, onClearHistory = vm::clearReadingHistory,
        onDismissError = vm::clearMessage, onConfirmImport = vm::confirmImport, onCancelImport = vm::cancelImport)
}
