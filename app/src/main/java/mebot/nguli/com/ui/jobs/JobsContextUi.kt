package mebot.nguli.com.ui.jobs

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import mebot.nguli.com.ui.MeBotTokens
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
    provider: String, supportsContext: Boolean, connected: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit,
    onSend: (JobsAssistantRequest) -> Unit, customQuestion: String = "", onQuestionChange: (String) -> Unit = {}) {
    var selected by rememberSaveable(snapshot.revision) { mutableStateOf(emptyList<String>()) }
    var selectionRevision by rememberSaveable(snapshot.revision) { mutableStateOf(snapshot.revision) }
    // Gate in this composition, not a later effect: a changed source must never inherit consent.
    val effectiveSelection = if (selectionRevision == snapshot.revision) selected else emptyList()
    val personalText = buildList {
        snapshot.candidates.filter { it.id in effectiveSelection }.distinctBy { it.sourceId to it.sourceText }.forEach { add(it.sourceText) }
    }.joinToString("\n\n")
    val contextValid = personalText.length <= 8000 && !personalText.contains('\u0000') &&
        (personalText.isEmpty() || (personalText.isNotBlank() && snapshot.revision.isNotBlank()))
    val questionValid = customQuestion.length <= 1000 && !customQuestion.contains('\u0000')
    val valid = contextValid && questionValid
    val reviewTitle = stringResource(R.string.jobs_ai_review_title)
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize().testTag("jobs-ai-review-screen").semantics { paneTitle = reviewTitle }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(reviewTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.jobs_ai_destination, provider.ifBlank { stringResource(R.string.jobs_ai_configured) }), style = MaterialTheme.typography.labelLarge)
                if (!connected) Text(stringResource(R.string.jobs_ai_not_connected))
                Text(stringResource(R.string.jobs_ai_review_body))
                Text(stringResource(R.string.jobs_ai_fresh_chat), modifier = Modifier.testTag("jobs-ai-fresh-chat"), style = MaterialTheme.typography.bodySmall)
                Text(question, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(customQuestion, onQuestionChange,
                    label = { Text(stringResource(R.string.jobs_custom_question)) },
                    modifier = Modifier.fillMaxWidth().testTag("jobs-ai-question"), minLines = 2,
                    isError = !questionValid,
                    supportingText = {
                        Text(if (questionValid) stringResource(R.string.jobs_question_count, customQuestion.length)
                            else stringResource(R.string.jobs_question_invalid), modifier = Modifier.testTag("jobs-ai-question-validation"))
                    })
                if (supportsContext && snapshot.candidates.isNotEmpty()) {
                    Text(stringResource(R.string.jobs_ai_choose_context), style = MaterialTheme.typography.titleSmall)
                    snapshot.candidates.distinctBy { it.sourceId to it.sourceText }.forEach { candidate ->
                        ContextChoice(candidate.sourceLabel + "\n" + candidate.sourceText, candidate.id in effectiveSelection) {
                            selected = if (candidate.id in effectiveSelection) effectiveSelection - candidate.id else effectiveSelection + candidate.id
                            selectionRevision = snapshot.revision
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.jobs_ai_exact_preview), style = MaterialTheme.typography.titleSmall)
                Text(personalText.ifBlank { stringResource(R.string.jobs_ai_nothing_shared) }, modifier = Modifier.testTag("jobs-ai-context-preview"), style = MaterialTheme.typography.bodySmall)
                if (!contextValid) Text(stringResource(R.string.jobs_ai_limit), color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (connected) Button(onClick = { onSend(JobsAssistantRequest(intent = if (customQuestion.isBlank()) intent else JobsAssistantIntent.QUESTION, question = customQuestion.trim().ifBlank { question }, sharedContext = personalText,
                contextRevision = snapshot.revision)) }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jobs-ai-send")) {
                Text(stringResource(R.string.jobs_ai_send))
            } else Button(onClick = { onConnect() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jobs-ai-connect")) {
                Text(stringResource(R.string.jobs_connect_to_continue))
            }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jobs_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextChoice(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(selected, onChange, modifier = Modifier.semantics { contentDescription = label })
        Text(label, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
