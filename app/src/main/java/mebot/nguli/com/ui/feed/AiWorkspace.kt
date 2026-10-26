package mebot.nguli.com.ui.feed

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.MeBotMark
import mebot.nguli.com.ui.MeBotTokens
import java.util.Locale

/** A durable conversation workspace. Account setup and model eligibility remain in the existing connection flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWorkspace(
    ui: FeedUiState,
    onNew: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAsk: (String) -> Unit,
    onCancel: () -> Unit,
    onConnect: () -> Unit,
    onContext: () -> Unit,
    onImportText: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSelectModel: (String) -> Unit = {},
    onDismissNotice: ((String) -> Unit)? = null,
    onSpeechPreferences: (Float, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val state = ui.state
    val conversations = state?.conversations.orEmpty().sortedByDescending { it.updatedAtMillis }
    val conversation = conversations.find { it.id == ui.activeConversationId }
    // The selected id is authoritative even while DataStore has not emitted the thread yet.
    val draftKey = ui.activeConversationId ?: "new"
    var drafts by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var previousDraftKey by rememberSaveable { mutableStateOf(draftKey) }
    LaunchedEffect(draftKey) {
        // A newly-created thread may arrive after the user starts typing.
        if (previousDraftKey == "new" && draftKey != "new" && conversation?.turns.isNullOrEmpty() && drafts[draftKey].isNullOrBlank()) {
            drafts["new"]?.takeIf { it.isNotBlank() }?.let { pending ->
                drafts = HashMap(drafts).apply { put(draftKey, pending); remove("new") }
            }
        }
        previousDraftKey = draftKey
    }
    val draft = drafts[draftKey].orEmpty()
    fun setDraft(value: String) { drafts = HashMap(drafts).apply { if (value.isEmpty()) remove(draftKey) else put(draftKey, value.take(2000)) } }
    var submittedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedText by rememberSaveable { mutableStateOf<String?>(null) }
    var previousTurnId by rememberSaveable { mutableStateOf<String?>(null) }
    var sentAttachmentIds by rememberSaveable(draftKey) { mutableStateOf(arrayListOf<String>()) }
    var showConversationFiles by rememberSaveable { mutableStateOf(false) }
    var showVoiceSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showScopes by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var apiModel by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewAttachmentId by rememberSaveable { mutableStateOf<String?>(null) }
    val speech = rememberWorkspaceSpeech(state?.speechRate ?: 1f, state?.speechVoice.orEmpty())
    val voiceAvailable = remember(context) {
        runCatching { context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null }.getOrDefault(false)
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { spoken ->
                setDraft(if (draft.isBlank()) spoken else "$draft\n$spoken")
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImportText(uri) }
    val listState = rememberLazyListState()
    var previousThread by remember { mutableStateOf<String?>(null) }
    val turns = conversation?.turns.orEmpty()
    LaunchedEffect(conversation?.id, turns.size, ui.asking) {
        val changedThread = previousThread != conversation?.id
        val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
            it >= listState.layoutInfo.totalItemsCount - 3
        } ?: true
        if (changedThread || nearBottom) {
            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(lastIndex)
        }
        previousThread = conversation?.id
    }
    LaunchedEffect(turns.lastOrNull()?.id, ui.asking, ui.activeConversationId) {
        val submitted = submittedText
        val accepted = turns.lastOrNull { it.role == "user" }
        if (submitted != null && accepted != null && accepted.id != previousTurnId && accepted.text == submitted &&
            (submittedKey == draftKey || submittedKey == "new")) {
            drafts = HashMap(drafts).apply {
                submittedKey?.let { key -> if (get(key)?.trim() == submitted) remove(key) }
                if (get(draftKey)?.trim() == submitted) remove(draftKey)
            }
            sentAttachmentIds = ArrayList((sentAttachmentIds + conversation?.attachments.orEmpty().map { it.id }).distinct())
            submittedText = null; submittedKey = null
        }
    }
    fun send(value: String) {
        if (!ui.connected || ui.asking || ui.importWorking || value.isBlank()) return
        previousTurnId = turns.lastOrNull { it.role == "user" }?.id
        submittedKey = draftKey; submittedText = value.trim()
        onAsk(value.trim())
        // Retrying an unanswered durable question reuses its turn id. Only clear that exact
        // text, never another unsent draft; new attachments still await their persisted timestamp.
        if (turns.lastOrNull()?.let { it.role == "user" && it.text == value.trim() } == true) {
            drafts = HashMap(drafts).apply { if (get(draftKey)?.trim() == value.trim()) remove(draftKey) }
            submittedText = null; submittedKey = null
        }
    }
    val module = state?.modules?.find { it.id == conversation?.moduleId }
    val story = state?.items?.find { it.id == conversation?.itemId }
    val scopeKind = module?.kind ?: story?.kind
    val noteCount = state?.context.orEmpty().count { it.confirmed && scopeKind != null && scopeKind in it.scopes }
    val promptChoices = remember(scopeKind, state?.modules, locale.language) { PromptSuggestions.forModule(scopeKind, state, locale.language == "sw") }
    val modelName = state?.connection?.let { connection ->
        if (connection.mode == "chatgpt") ui.chatGpt.accounts.find { it.id == connection.accountId }?.models?.find { it.slug == connection.model }?.displayName ?: connection.model
        else connection.model
    }.orEmpty()

    if(showModels) {
        val models = ui.chatGpt.accounts.find { it.id == state?.connection?.accountId }?.models.orEmpty()
        AlertDialog(onDismissRequest={if(!ui.connecting)showModels=false},
            title={Text(if(locale.language=="sw") "Chagua modeli" else "Choose a model")},
            text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(state?.connection?.mode=="chatgpt") models.forEach { model ->
                    TextButton(onClick={showModels=false;onSelectModel(model.slug)},enabled=!ui.asking && !ui.connecting,modifier=Modifier.fillMaxWidth()) {
                        Text(model.displayName,Modifier.weight(1f));if(model.slug==state.connection.model)Icon(Icons.Outlined.Check,null,Modifier.size(18.dp))
                    }
                } else OutlinedTextField(apiModel, {apiModel=it.take(128)},singleLine=true,label={Text(if(locale.language=="sw") "Jina la modeli" else "Model ID")})
            }},
            confirmButton={ mebot.nguli.com.security.ProtectedWindowRegistration();if(state?.connection?.mode!="chatgpt")TextButton(onClick={showModels=false;onSelectModel(apiModel.trim())},enabled=apiModel.isNotBlank()){Text(if(locale.language=="sw") "Tumia" else "Use model")}},
            dismissButton={TextButton(onClick={showModels=false}){Text(if(locale.language=="sw") "Funga" else "Close")}})
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().fillMaxHeight().imePadding()
            .padding(horizontal = MeBotTokens.ScreenPadding).testTag("ai-workspace")) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MeBotActivityBadge(if (ui.asking) ui.activities.lastOrNull { it.operation == "answer" } ?: mebot.nguli.com.feed.AiActivity("workspace-preparing", "workspace", "", "answer", "preparing") else null, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.workspace_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { showHistory = true }, enabled = conversations.isNotEmpty() && !ui.asking && !ui.importWorking,
                    modifier = Modifier.testTag("workspace-history")) { Icon(Icons.Outlined.History, stringResource(R.string.workspace_history)) }
                IconButton(onClick = { speech.stop(); onNew(null) }, enabled = !ui.asking && !ui.importWorking,
                    modifier = Modifier.testTag("workspace-new")) { Icon(Icons.Outlined.Add, stringResource(R.string.workspace_new)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.2f
                val modelLabel = if (ui.connected) modelName else stringResource(R.string.feed_connect)
                val scopeLabel = module?.let { moduleName(it) } ?: stringResource(R.string.workspace_general)
                if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkspaceModelControl(modelLabel, ui.connected, { if(ui.connected) showModels = true else onConnect() }, Modifier.fillMaxWidth(), wrapLabel = true)
                    WorkspaceScopeControl(scopeLabel, !ui.asking && !ui.importWorking, { showScopes = true }, Modifier.fillMaxWidth(), wrapLabel = true)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceModelControl(modelLabel, ui.connected, { if(ui.connected) showModels = true else onConnect() }, Modifier.weight(1f))
                    WorkspaceScopeControl(scopeLabel, !ui.asking && !ui.importWorking, { showScopes = true }, Modifier.widthIn(max = 160.dp))
                }
            }
            if (story != null) Text(story.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("workspace-messages"),
                contentPadding = PaddingValues(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (turns.isEmpty() && !ui.asking && state != null && onDismissNotice != null) item(key = "setup") {
                    SetupNotice(state, ui.connected, "mebot", onConnect, onContext, onDismissNotice)
                }
                if (turns.isEmpty()) item(key = "start") {
                    WorkspaceStart(ui.connected, onConnect, onContext, noteCount, scopeKind != null, promptChoices) { prompt ->
                        if (ui.connected) send(prompt)
                    }
                }
                items(turns, key = { it.id }, contentType = { it.role }) { turn -> WorkspaceTurn(turn, speech) }
                if (ui.asking || (ui.answer?.isNotBlank() == true && turns.lastOrNull()?.text != ui.answer)) item(key = "pending") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("workspace-pending"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!ui.answer.isNullOrBlank()) {
                            if (!ui.asking) Text(stringResource(R.string.workspace_partial),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RichResponse(ui.answer.orEmpty(), Modifier.testTag("workspace-draft"), ui.answerCitations)
                        }

                    }
                }
                if (ui.error != null) item(key = "error") {
                    FeedStatus(ui.error, onConnect = onConnect)
                    turns.lastOrNull()?.takeIf { it.role == "user" }?.let { unanswered ->
                        TextButton(onClick = { send(unanswered.text) }, enabled = ui.connected && !ui.asking && !ui.importWorking,
                            modifier = Modifier.testTag("workspace-retry")) { Text(stringResource(R.string.rich_retry_question)) }
                    }
                }
            }
            val composerFiles = conversation?.attachments.orEmpty().filterNot { it.usedAtMillis > 0 || it.id in sentAttachmentIds }
            if (conversation?.attachments.orEmpty().any { it.usedAtMillis > 0 || it.id in sentAttachmentIds }) TextButton(onClick = { showConversationFiles = true },
                modifier = Modifier.testTag("workspace-conversation-files")) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rich_conversation_files, conversation?.attachments.orEmpty().size))
            }
            if (composerFiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                composerFiles.forEach { attachment -> InputChip(selected = false, onClick = { reviewAttachmentId = attachment.id },
                    label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                    trailingIcon = { IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !ui.asking && !ui.importWorking,
                        modifier = Modifier.size(32.dp).testTag("attachment-remove-${attachment.id}")) { Icon(Icons.Outlined.Close, stringResource(R.string.workspace_remove_attachment), Modifier.size(16.dp)) } },
                    modifier = Modifier.testTag("attachment-${attachment.id}")) }
            }

            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column {
                    TextField(value = draft, onValueChange = ::setDraft, enabled = !ui.asking && !ui.importWorking,
                        placeholder = { Text(stringResource(R.string.workspace_question)) }, minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("workspace-question"),
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        shape = RoundedCornerShape(22.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { importFile.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown")) },
                            enabled = !ui.asking && !ui.importWorking && conversation?.attachments.orEmpty().size < 3,
                            modifier = Modifier.testTag("workspace-attach")) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.workspace_attach)) }
                        if (voiceAvailable) IconButton(onClick = {
                            speech.stop()
                            runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)) }
                                .onFailure { Toast.makeText(context, R.string.workspace_voice_error, Toast.LENGTH_SHORT).show() }
                        }, enabled = !ui.asking && !ui.importWorking, modifier = Modifier.testTag("workspace-voice")) {
                            Icon(Icons.Outlined.MicNone, stringResource(R.string.workspace_voice_input))
                        }
                        IconButton(onClick = { showVoiceSettings = true }, modifier = Modifier.testTag("workspace-voice-options")) {
                            Icon(Icons.Outlined.VolumeUp, stringResource(R.string.rich_voice_settings))
                        }
                        if (speech.readingId != null) IconButton(onClick = speech.stop) { Icon(Icons.Outlined.VolumeOff, stringResource(R.string.workspace_stop_audio)) }
                        Spacer(Modifier.weight(1f))
                        if (ui.asking) FilledTonalButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp).testTag("workspace-cancel")) {
                            Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.workspace_stop))
                        } else FilledIconButton(onClick = { send(draft) }, enabled = ui.connected && draft.isNotBlank() && !ui.importWorking,
                            modifier = Modifier.size(44.dp).testTag("workspace-send")) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.feed_ask_send), Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
    if (showConversationFiles) ModalBottomSheet(onDismissRequest = { showConversationFiles = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rich_conversation_files, conversation?.attachments.orEmpty().size), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rich_conversation_files_help), style = MaterialTheme.typography.bodyMedium)
            conversation?.attachments.orEmpty().forEach { attachment ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { reviewAttachmentId = attachment.id }, modifier = Modifier.weight(1f)) { Text(attachment.name) }
                    IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !ui.asking && !ui.importWorking) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.workspace_remove_attachment))
                    }
                }
            }
        }
    }
    if (showVoiceSettings) ModalBottomSheet(onDismissRequest = { showVoiceSettings = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rich_voice_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rich_voice_help), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.rich_voice_speed), style = MaterialTheme.typography.labelLarge)
            var rate by remember { mutableFloatStateOf(state?.speechRate ?: 1f) }
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.5f,
                onValueChangeFinished = { onSpeechPreferences(rate, state?.speechVoice.orEmpty()) }, modifier = Modifier.testTag("workspace-speech-rate"))
            Text(stringResource(R.string.rich_voice_choice), style = MaterialTheme.typography.labelLarge)
            if (!speech.available) Text(stringResource(R.string.rich_voice_unavailable), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { speech.stop(); onSpeechPreferences(rate, "") }) { Text(stringResource(R.string.rich_voice_default)) }
            speech.voices.forEach { (name, label) ->
                TextButton(onClick = { speech.stop(); onSpeechPreferences(rate, name) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f)); if (state?.speechVoice == name) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                }
            }
        }
    }
    if (showHistory) ModalBottomSheet(onDismissRequest = { showHistory = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.workspace_history), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.id }) { saved ->
                    Surface(color = if (saved.id == conversation?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(onClick = { speech.stop(); showHistory = false; onSelect(saved.id) }, color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.weight(1f).testTag("conversation-${saved.id}")) {
                                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(saved.title.ifBlank { stringResource(R.string.workspace_new) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(relativeTime(saved.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { deletingId = saved.id }, modifier = Modifier.testTag("conversation-delete-${saved.id}")) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.workspace_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showScopes) AlertDialog(onDismissRequest = { showScopes = false },
        title = { Text(stringResource(R.string.workspace_choose_context)) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.workspace_context_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showScopes = false; speech.stop(); onNew(null) }, modifier = Modifier.fillMaxWidth().testTag("workspace-scope-general")) {
                Text(stringResource(R.string.workspace_general), Modifier.fillMaxWidth())
            }
            state?.modules.orEmpty().filter { it.enabled }.forEach { choice -> TextButton(onClick = { showScopes = false; speech.stop(); onNew(choice.id) },
                modifier = Modifier.fillMaxWidth().testTag("workspace-scope-${choice.id}")) {
                Icon(moduleIcon(choice.kind), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(moduleName(choice), Modifier.weight(1f))
            } }
            TextButton(onClick = { showScopes = false; onContext() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workspace_manage_context)) }
        } }, confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showScopes = false }) { Text(stringResource(R.string.done)) } })
    deletingId?.let { target -> AlertDialog(onDismissRequest = { deletingId = null },
        title = { Text(stringResource(R.string.workspace_delete_title)) }, text = { Text(stringResource(R.string.workspace_delete_body)) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { onDelete(target); deletingId = null; showHistory = false }, modifier = Modifier.testTag("conversation-delete-confirm")) { Text(stringResource(R.string.workspace_delete)) } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } }) }
    conversation?.attachments?.find { it.id == reviewAttachmentId }?.let { attachment -> AlertDialog(onDismissRequest = { reviewAttachmentId = null },
        title = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workspace_attachment_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(attachment.text, style = MaterialTheme.typography.bodyMedium) }
        } }, confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { reviewAttachmentId = null }) { Text(stringResource(R.string.done)) } }) }
}

@Composable
private fun WorkspaceModelControl(label: String, connected: Boolean, onClick: () -> Unit, modifier: Modifier, wrapLabel: Boolean = false) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = modifier.testTag("workspace-model")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (connected) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = if (wrapLabel) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun WorkspaceScopeControl(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, wrapLabel: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag("workspace-scope")) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
        Text(label, maxLines = if (wrapLabel) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            modifier = if (wrapLabel) Modifier.weight(1f) else Modifier)
    }
}

@Composable
private fun WorkspaceStart(connected: Boolean, onConnect: () -> Unit, onContext: () -> Unit, notes: Int, scoped: Boolean, prompts: List<String>, onPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.workspace_start_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(if (scoped) R.string.workspace_scoped_notes else R.string.workspace_general_context, notes),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!connected) FilledTonalButton(onClick = onConnect, modifier = Modifier.testTag("workspace-connect")) {
            Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.feed_connect))
        } else {
            HorizontalRail(prompts.withIndex().toList(), itemKey = { it.index.toString() }) { (index, prompt) ->
                SuggestionChip(onClick = { onPrompt(prompt) }, label = { Text(prompt, maxLines=2, modifier=Modifier.widthIn(max=220.dp)) },
                    modifier=Modifier.heightIn(min=48.dp).testTag("workspace-starter-$index"))
            }
        }
        TextButton(onClick = onContext, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.workspace_manage_context), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WorkspaceTurn(turn: AiTurn, speech: WorkspaceSpeech) {
    val context = LocalContext.current
    val clipboardLabel = stringResource(R.string.workspace_title)
    val user = turn.role == "user"
    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(start = if (user) 28.dp else 0.dp).testTag("turn-${turn.id}")) {
        Column(Modifier.padding(if (user) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeBotMark(Modifier.size(24.dp))
                Text(turn.model.ifBlank { stringResource(R.string.workspace_title) }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if(user) SelectionContainer { Text(turn.text, style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onPrimaryContainer) }
            else RichResponse(turn.text, citations=turn.citations)
            if (!user) {
                val represented = remember(turn.text) { RichContent.links(turn.text).map { FeedLogic.canonicalUrl(it.url) }.toSet() }
                ConversationSources(turn.citations.filterNot { FeedLogic.canonicalUrl(it.url) in represented }, "turn-source-${turn.id}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(clipboardLabel, turn.text))
                    }, modifier = Modifier.testTag("turn-copy-${turn.id}")) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.workspace_copy), Modifier.size(18.dp)) }
                    if (speech.available) IconButton(onClick = { if (speech.readingId == turn.id) speech.stop() else speech.read(turn.id, turn.text) }, modifier = Modifier.testTag("turn-read-${turn.id}")) {
                        Icon(if (speech.readingId == turn.id) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                            stringResource(if (speech.readingId == turn.id) R.string.workspace_stop_audio else R.string.workspace_read), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
