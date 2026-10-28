package mebot.nguli.com.ui.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mebot.nguli.com.R
import mebot.nguli.com.core.ContextEntry
import mebot.nguli.com.core.ModuleCatalog
import mebot.nguli.com.core.PersonalContextImport
import mebot.nguli.com.feed.FeedJson
import mebot.nguli.com.ui.AppCard
import kotlinx.serialization.encodeToString

/** The prompt contains only a format example and module IDs, never a user's local notes. */
internal object ContextSetup {
    const val MAX_PASTED_CHARACTERS = 128 * 1024

    fun example(entryText: String): String = FeedJson.encodeToString(PersonalContextImport(
        entries = listOf(ContextEntry(id = "preference-1", text = entryText, category = "preference",
            scopes = setOf("news"), confirmed = true, origin = "imported", updatedAtMillis = 0)),
        modules = emptyList(),
    ))

    fun prompt(context: Context): String = context.getString(R.string.setup_context_prompt,
        example(context.getString(R.string.setup_context_example)), ModuleCatalog.kinds.sorted().joinToString(", "))
}

private const val CHATGPT_HOME_URL_099 = "https://chatgpt.com/"

@Composable
internal fun ContextImportActions(enabled: Boolean, onImport: () -> Unit, onPaste: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = onImport, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("context-import"), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.setup_import_file))
        }
        OutlinedButton(onClick = onPaste, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("context-paste"), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.setup_paste_json))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextPromptActions(enabled: Boolean = true, guided: Boolean = false) {
    val context = LocalContext.current
    val prompt = stringResource(R.string.setup_context_prompt,
        ContextSetup.example(stringResource(R.string.setup_context_example)), ModuleCatalog.kinds.sorted().joinToString(", "))
    val clipboardLabel = stringResource(R.string.setup_prompt_label)
    var copied by remember(prompt) { mutableStateOf(false) }
    var viewPrompt by rememberSaveable { mutableStateOf(false) }
    fun copyPrompt(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, prompt))
        copied = true
        return true
    }
    AppCard(modifier = Modifier.testTag("context-setup")) {
        Text(stringResource(if (guided) R.string.context_step_one_092 else R.string.setup_context_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (guided) R.string.context_prompt_step_092 else R.string.setup_context_body), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { copyPrompt() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-starter-prompt"), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(if(copied) R.string.setup_prompt_copied else R.string.starter_copy))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { viewPrompt = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("context-view-prompt")) {
                Text(stringResource(R.string.setup_view_prompt))
            }
            TextButton(onClick = { openSource(context, CHATGPT_HOME_URL_099) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("context-open-chatgpt")) {
                Text(stringResource(R.string.setup099_open_chatgpt))
            }
        }
        if (copied) Text(stringResource(R.string.starter_paste_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("context-prompt-copied"))
    }
    if (viewPrompt) AlertDialog(onDismissRequest = { viewPrompt = false },
        title = { Text(stringResource(R.string.setup_prompt_label)) },
        text = { SelectionContainer { Text(prompt, Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { copyPrompt(); viewPrompt = false }) { Text(stringResource(R.string.setup_copy_prompt)) } },
        dismissButton = { TextButton(onClick = { viewPrompt = false }) { Text(stringResource(R.string.done)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextPasteDialog(text: String, onTextChanged: (String) -> Unit, working: Boolean, error: String?,
    onDismiss: () -> Unit, onReview: (String) -> Unit) {
    var tooLarge by rememberSaveable { mutableStateOf(false) }
    val dialogTitle = stringResource(R.string.setup_paste_json)
    Dialog(onDismissRequest = { if (!working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(460.dp))
                .testTag("context-paste-dialog").semantics { paneTitle = dialogTitle },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp)) {
                    Text(dialogTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(16.dp))
                    // Give the editor a measured viewport; the action row stays outside the scrollable body.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.setup_paste_body), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(text, { value ->
                            tooLarge = value.length > ContextSetup.MAX_PASTED_CHARACTERS
                            if (!tooLarge) onTextChanged(value)
                        }, modifier = Modifier.fillMaxWidth().height(150.dp).testTag("context-paste-input"),
                            enabled = !working, isError = tooLarge,
                            label = { Text(stringResource(R.string.setup_json_label)) }, shape = RoundedCornerShape(16.dp))
                        if (tooLarge) Text(stringResource(R.string.setup_paste_too_large), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        if (error != null) FeedStatus(error)
                        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(16.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onDismiss, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = { onReview(text.trim()) }, enabled = !working && text.isNotBlank() && !tooLarge,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("context-paste-review")) {
                            Text(stringResource(R.string.setup_review_context))
                        }
                    }
                }
            }
        }
    }
}

/** Full-page import guide; parsing, review and confirmation still use the existing repository pipeline. */
@Composable
fun ContextImportScreen(
    ui: mebot.nguli.com.feed.FeedUiState,
    vm: mebot.nguli.com.feed.FeedViewModel,
    onManualContext: (() -> Unit)? = null,
    onInterests: (() -> Unit)? = null,
    onImported: () -> Unit,
) {
    val documents = mebot.nguli.com.documents.LocalDocumentActions.current
    var text by rememberSaveable { mutableStateOf("") }
    var tooLarge by rememberSaveable { mutableStateOf(false) }
    // Only a newly committed context import returns to the notes editor. Invalid, cancelled,
    // working, or stale completion state remains on this screen for review and recovery.
    LaunchedEffect(ui.importSummary) {
        if (ui.importSummary == "context") {
            text = ""
            tooLarge = false
            onImported()
        }
    }
    val enabled = !ui.importWorking && !ui.contextSaving
    mebot.nguli.com.ui.ScreenContainer(Modifier.testTag("context-import-screen")) {
        mebot.nguli.com.ui.ScreenTitle(stringResource(R.string.context_import_title_092))
        verifiedConfiguredChatGptModel(ui)?.let { model ->
            AppCard(modifier = Modifier.testTag("context-import-chatgpt-verified")) {
                Text(stringResource(R.string.setup099_connected_model, model), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.setup099_connected_model_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ImportFeedback(ui)
        ContextPromptActions(enabled, guided = true)
        AppCard {
            Text(stringResource(R.string.context_step_two_092), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.context_result_step_092), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = documents::importContext, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-import"), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.setup_import_file))
            }
            OutlinedTextField(text, { value ->
                tooLarge = value.length > ContextSetup.MAX_PASTED_CHARACTERS
                if (!tooLarge) { text = value; vm.clearMessage() }
            }, label = { Text(stringResource(R.string.setup_paste_json)) }, enabled = enabled, isError = tooLarge,
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("context-paste-input"), shape = RoundedCornerShape(16.dp))
            if (tooLarge) Text(stringResource(R.string.setup_paste_too_large), color = MaterialTheme.colorScheme.error)
            onManualContext?.let { manual ->
                HorizontalDivider()
                Text(stringResource(R.string.setup099_manual_context_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = manual, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-manual-entry"),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup099_manual_context))
                }
            }
            onInterests?.let { choose ->
                OutlinedButton(onClick = choose, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-import-interests"),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.context_import_interests_099))
                }
            }
        }
        if (ui.importPreview == null) ui.error?.let { FeedStatus(it) }
        Text(stringResource(R.string.context_review_step_092), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.prepareContextText(text.trim()) }, enabled = enabled && text.isNotBlank() && !tooLarge,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("context-paste-review"), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.setup_review_context))
        }
    }
    FeedImportReview(ui, vm::confirmImport, vm::cancelImport)
}

private fun verifiedConfiguredChatGptModel(ui: mebot.nguli.com.feed.FeedUiState): String? {
    val state = ui.state ?: return null
    if (!ui.connected || state.connection.mode != "chatgpt" || state.connection.accountId.isBlank() || state.connection.model.isBlank()) return null
    val account = ui.chatGpt.accounts.firstOrNull { it.id == state.connection.accountId && it.connected } ?: return null
    return account.models.firstOrNull { it.slug == state.connection.model }?.displayName?.takeIf { it.isNotBlank() }
}
