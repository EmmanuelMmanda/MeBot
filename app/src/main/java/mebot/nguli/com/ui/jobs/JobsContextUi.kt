package mebot.nguli.com.ui.jobs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import mebot.nguli.com.R
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.jobs.JobsAssistantIntent
import mebot.nguli.com.jobs.JobsAssistantRequest
import mebot.nguli.com.ui.SupportingText

@Composable
internal fun RelatedJobsModules(snapshot: JobsContextSnapshot, onOpen: (String) -> Unit, job: JobPosting? = null) {
    val modules = if (job == null) snapshot.modules.take(3) else JobsContextSuggestions.relevantModules(snapshot, job)
    if (modules.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.jobs_connected_modules), style = MaterialTheme.typography.titleMedium)
        SupportingText(stringResource(R.string.jobs_connected_modules_body))
        modules.forEach { module ->
            OutlinedButton(onClick = { onOpen(module.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Link, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(module.title, Modifier.weight(1f)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun JobsAiReview(intent: JobsAssistantIntent, question: String, snapshot: JobsContextSnapshot,
    application: JobApplication, provider: String, supportsContext: Boolean, connected: Boolean, onConnect: (JobApplication) -> Unit, onDismiss: () -> Unit,
    onSend: (JobsAssistantRequest) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var shareNotes by rememberSaveable { mutableStateOf(false) }
    var shareDraft by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(snapshot.revision) { selected = emptyList(); shareNotes = false; shareDraft = false }
    val personalText = buildList {
        snapshot.candidates.filter { it.id in selected }.distinctBy { it.sourceId to it.sourceText }.forEach { add(it.sourceText) }
        if (shareNotes) add(application.notes)
        if (shareDraft) add(application.draft)
    }.joinToString("\n\n")
    val valid = personalText.length <= 8000 && !personalText.contains('\u0000')
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.jobs_ai_review_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.jobs_ai_destination, provider.ifBlank { stringResource(R.string.jobs_ai_configured) }), style = MaterialTheme.typography.labelLarge)
                if (!connected) Text(stringResource(R.string.jobs_ai_not_connected))
                Text(stringResource(R.string.jobs_ai_review_body))
                Text(question, style = MaterialTheme.typography.bodyMedium)
                if (supportsContext && (snapshot.candidates.isNotEmpty() || application.notes.isNotBlank() || application.draft.isNotBlank())) {
                    Text(stringResource(R.string.jobs_ai_choose_context), style = MaterialTheme.typography.titleSmall)
                    snapshot.candidates.distinctBy { it.sourceId to it.sourceText }.forEach { candidate ->
                        ContextChoice(candidate.sourceLabel + "\n" + candidate.sourceText, candidate.id in selected) {
                            selected = if (candidate.id in selected) selected - candidate.id else selected + candidate.id
                        }
                    }
                    if (application.notes.isNotBlank()) ContextChoice(stringResource(R.string.jobs_ai_share_notes), shareNotes) { shareNotes = it }
                    if (application.draft.isNotBlank()) ContextChoice(stringResource(R.string.jobs_ai_share_draft), shareDraft) { shareDraft = it }
                }
                HorizontalDivider()
                Text(stringResource(R.string.jobs_ai_exact_preview), style = MaterialTheme.typography.titleSmall)
                Text(personalText.ifBlank { stringResource(R.string.jobs_ai_nothing_shared) }, modifier = Modifier.testTag("jobs-ai-context-preview"), style = MaterialTheme.typography.bodySmall)
                if (!valid) Text(stringResource(R.string.jobs_ai_limit), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            if (connected) TextButton(onClick = { onSend(JobsAssistantRequest(intent = intent, question = question, sharedContext = personalText,
                contextRevision = snapshot.revision, application = application)) }, enabled = valid, modifier = Modifier.testTag("jobs-ai-send")) {
                Text(stringResource(R.string.jobs_ai_send))
            } else TextButton(onClick = { onConnect(application) }, modifier = Modifier.testTag("jobs-ai-connect")) {
                Text(stringResource(R.string.jobs_connect_to_continue))
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jobs_cancel)) } })
}

@Composable
private fun ContextChoice(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(selected, onChange, modifier = Modifier.semantics { contentDescription = label })
        Text(label, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
