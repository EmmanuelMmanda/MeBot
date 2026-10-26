package mebot.nguli.com.ui.feed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.BuildConfig
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.FeedJson
import mebot.nguli.com.ui.*
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun YouScreen(ui: FeedUiState, onContext: () -> Unit, onConnect: () -> Unit, onDelivery: () -> Unit, onSettings: () -> Unit, onData: () -> Unit, onSoftwareUpdates: (() -> Unit)? = null, onAppLock: () -> Unit = {}, onStarred: () -> Unit = {}) {
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
        FeedSettingsRow(Icons.Outlined.PersonOutline, stringResource(R.string.preferences_title_083), stringResource(R.string.preferences_body_083), onContext)
        FeedSettingsRow(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.feed_connection),
            if (ui.connected && state.connection.mode == "chatgpt") stringResource(R.string.chatgpt_using_plan)
            else if (ui.connected) "${stringResource(R.string.feed_connected)} · ${state.connection.model}" else stringResource(R.string.feed_not_connected), onConnect)
        FeedSettingsRow(Icons.Outlined.NotificationsNone, stringResource(R.string.feed_delivery),
            if (state.backgroundEnabled || state.notifications) stringResource(R.string.feed_delivery_on, state.refreshHours) else stringResource(R.string.feed_delivery_off), onDelivery)
        onSoftwareUpdates?.let { FeedSettingsRow(Icons.Outlined.SystemUpdate, stringResource(R.string.software_updates), stringResource(R.string.update_card_body), it) }
        FeedSettingsRow(Icons.Outlined.StarOutline, stringResource(R.string.starred_settings_092), stringResource(R.string.starred_summary_092), onClick = onStarred)
        FeedSettingsRow(Icons.Outlined.Palette, stringResource(R.string.personalization_092), stringResource(R.string.personalization_summary_092), onClick = onSettings)
        FeedSettingsRow(Icons.Outlined.FolderOpen, stringResource(R.string.feed_data), stringResource(R.string.settings_data_summary_091), onClick = onData)
        Text(stringResource(R.string.feed_about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun ContextScreen(ui: FeedUiState, vm: FeedViewModel, onImportContext: () -> Unit = {}, onDone: () -> Unit) {
    val state = ui.state ?: return
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var region by rememberSaveable(state.region) { mutableStateOf(state.region) }
    var timezone by rememberSaveable(state.timezone) { mutableStateOf(state.timezone) }
    var entriesJson by rememberSaveable(state.context) {
        mutableStateOf(FeedJson.encodeToString(PersonalProfile085.draft(state.context, state.modules.filter { it.enabled && it.kind != "weather" }.map { it.kind }.toSet())))
    }
    val entries = remember(entriesJson) { FeedJson.decodeFromString<List<ContextEntry>>(entriesJson) }
    val primaryId = PersonalProfile085.primary(entries)?.id ?: PersonalProfile085.PROFILE_ID
    var localError by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    var desiredContextJson by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val timezoneInvalid = timezone.isNotBlank() && runCatching { ZoneId.of(timezone.trim()) }.isFailure
    val canEdit = !ui.contextSaving && !ui.importWorking
    val documents = mebot.nguli.com.documents.LocalDocumentActions.current
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
    ScreenContainer {
        ScreenTitle(stringResource(R.string.preferences_title_083), stringResource(R.string.preferences_body_083))
        ImportFeedback(ui)
        FeedSettingsRow(Icons.Outlined.FileUpload, stringResource(R.string.context_import_title_092),
            stringResource(R.string.context_import_entry_092), onImportContext)
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.LocationOn, null)
                Text(stringResource(R.string.context_location_092), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(region, { region = it.take(100) }, label = { Text(stringResource(R.string.feed_region)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("context-region"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
            OutlinedTextField(timezone, { timezone = it.take(80); localError = false }, label = { Text(stringResource(R.string.feed_timezone)) },
                isError = timezoneInvalid, supportingText = { if (timezoneInvalid) Text(stringResource(R.string.profile_timezone_invalid_085)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("context-timezone"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
        }
        OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.feed_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("context-name"), shape = RoundedCornerShape(16.dp), enabled = canEdit)
        val primary = entries.find { it.id == primaryId }
        val legacy = entries.filterNot { it.id == primaryId }
        if (primary != null) {
            PersonalProfileEditor085(primary.text, canEdit, { text ->
                entriesJson = FeedJson.encodeToString(entries.map { if (it.id == primaryId) it.copy(text = text) else it })
                localError = false
            })
            Text(stringResource(R.string.profile_global_use_085), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("context-global-use"))
        }
        val saveDetails: @Composable (String) -> Unit = { tag ->
        Button(onClick = {
            val now = System.currentTimeMillis()
            val updated = entries.filter { it.text.isNotBlank() }.map { entry ->
                val text = entry.text.trim()
                if (entry.id == primaryId) entry.copy(text = text, global = true, category = "profile", scopes = emptySet(), confirmed = true, updatedAtMillis = now)
                else if (state.context.find { it.id == entry.id } == entry) entry else entry.copy(text = text, confirmed = true, updatedAtMillis = now)
            }
            localError = entries.find { it.id == primaryId }?.let { !PersonalProfile085.valid(it.text) } == true || updated.size > 80 || updated.any { it.text.length > 1000 || '\u0000' in it.text } || timezoneInvalid
            if (!localError) {
                desiredContextJson = FeedJson.encodeToString(updated); pending = true
                vm.clearMessage()
                vm.saveContext(name.trim(), region.trim(), timezone.trim(), updated)
            }
        }, enabled = canEdit && entries.none { it.text.length > 1000 || '\u0000' in it.text } && entries.find { it.id == primaryId }?.let { PersonalProfile085.valid(it.text) } != false, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.profile_save_085)) }
        }
        saveDetails("context-save")
        if (localError) Text(stringResource(R.string.feed_context_invalid), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (ui.importPreview == null) ui.error?.let { FeedStatus(it) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_notes_title_085), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                entriesJson = FeedJson.encodeToString(entries + ContextEntry(text = "", scopes = emptySet(), confirmed = false))
            }, enabled = canEdit && entries.size < 80, modifier = Modifier.testTag("context-add")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.profile_note_add_085))
            }
        }
        Text(stringResource(R.string.profile_notes_body_085), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (legacy.isEmpty()) Text(stringResource(R.string.profile_notes_empty_085), style = MaterialTheme.typography.bodySmall)
        legacy.forEach { entry ->
            key(entry.id) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(entry.text, { text ->
                        entriesJson = FeedJson.encodeToString(entries.map { if (it.id == entry.id) it.copy(text = text) else it })
                        localError = false
                    }, isError = entry.text.length > 1000 || '\u0000' in entry.text,
                        supportingText = { if (entry.text.length > 1000 || '\u0000' in entry.text) Text(stringResource(R.string.profile_note_limit_085)) }, label = { Text(stringResource(preferencePromptTitle083(entry.category))) },
                        placeholder = { Text(stringResource(preferencePromptHint083(entry.category))) }, minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("context-edit-${entry.id}"),
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
        saveDetails("context-save-bottom")
        OutlinedButton(onClick = { documents.exportContext() }, enabled = canEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-export")) { Text(stringResource(R.string.feed_context_export)) }

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
                        }, modifier = Modifier.testTag("context-scope-${scopeEntry.id}-$kind"), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                Text(stringResource(moduleNameResource(kind)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { scopeEntryId = null }) { Text(stringResource(R.string.done)) } })
    FeedImportReview(ui, vm::confirmImport, vm::cancelImport)
}

@Composable
fun DeliveryScreen(ui: FeedUiState, vm: FeedViewModel, onDone: () -> Unit, onConnection: (() -> Unit)? = null) =
    DeliveryPreferencesScreen(ui, vm, onDone, onConnection)

@Composable
fun FeedDataScreen(ui: FeedUiState, vm: FeedViewModel, onLocalData: () -> Unit) {
    val documents = mebot.nguli.com.documents.LocalDocumentActions.current
    FeedDataContent(ui,
        onExport = { documents.exportFeedBackup() },
        onImport = { documents.importFeedBackup() },
        onRestoreHidden = vm::restoreHidden, onLocalData = onLocalData,
        onHistoryChanged = vm::setReadingHistory, onClearHistory = vm::clearReadingHistory,
        onDismissError = vm::clearMessage, onConfirmImport = { documents.invalidatePending(); vm.confirmImport() }, onCancelImport = vm::cancelImport, onResetFeedback=vm::resetRelevanceFeedback)
}
