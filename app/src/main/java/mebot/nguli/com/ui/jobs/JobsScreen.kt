package mebot.nguli.com.ui.jobs

import android.text.Html
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import android.net.Uri
import android.app.NotificationManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import java.util.Locale
import java.util.Currency
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.jobs.JobsAssistantIntent
import mebot.nguli.com.jobs.JobsAssistantRequest
import mebot.nguli.com.ui.*
import java.text.DateFormat
import java.util.Date

/** Jobs remains usable without a model connection. The host owns persistence, fetching and AI. */
@Composable
fun JobsScreen(
    state: JobsState, loading: Boolean, error: String?, onRefresh: () -> Unit,
    onPreferences: (JobPreferences) -> Unit, onSave: (String) -> Unit,
    onApplication: (JobApplication) -> Unit, onAsk: (JobPosting, String) -> Unit,
    onOpen: (String) -> Unit, lastAiDraft: (JobPosting) -> String = { "" },
    onExport: (Uri, String) -> Unit = { _, _ -> }, exportBusy: Boolean = false,
    exportResult: String = "", onClearExportResult: () -> Unit = {},
    initialTab: String = "for_you", tabRequest: Int = 0,
    saving: Boolean = false, refreshPhase: String = "", onCancelRefresh: () -> Unit = {},
    contextSnapshot: JobsContextSnapshot = JobsContextSnapshot(), onOpenRelated: (String) -> Unit = {},
    onAssistant: ((JobPosting, JobsAssistantRequest) -> Unit)? = null, aiProviderLabel: String = "",
    aiConnected: Boolean = true, onConnectAi: (JobApplication) -> Unit = {},
) {
    var route by rememberSaveable { mutableStateOf(if (state.preferences.onboarded) "browse" else "onboard") }
    LaunchedEffect(state.preferences.onboarded) {
        if (state.preferences.onboarded && route == "onboard") route = "browse"
    }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("for_you") }
    var query by rememberSaveable { mutableStateOf("") }
    var handledTabRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tabRequest) {
        if (tabRequest > handledTabRequest) {
            tab = initialTab.takeIf { it in setOf("for_you", "recent", "saved", "applications") } ?: "for_you"
            route = "browse"; handledTabRequest = tabRequest
        }
    }
    val selected = state.jobs.firstOrNull { it.id == selectedId }
        ?: state.applications.firstOrNull { it.jobId == selectedId }?.snapshot
    val application = state.applications.firstOrNull { it.jobId == selectedId }
    var notes by rememberSaveable(selectedId) { mutableStateOf(application?.notes.orEmpty()) }
    var status by rememberSaveable(selectedId) { mutableStateOf(application?.status ?: "preparing") }
    var document by rememberSaveable(selectedId) { mutableStateOf(application?.draft.orEmpty()) }
    var notesTouched by rememberSaveable(selectedId) { mutableStateOf(false) }
    var documentTouched by rememberSaveable(selectedId) { mutableStateOf(false) }
    var statusTouched by rememberSaveable(selectedId) { mutableStateOf(false) }
    LaunchedEffect(application?.updatedAt, selectedId) {
        if (!notesTouched) notes = application?.notes.orEmpty()
        if (!documentTouched) document = application?.draft.orEmpty()
        if (!statusTouched) status = application?.status ?: "preparing"
    }
    var reviewed by rememberSaveable(selectedId) { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf("") }
    var exportForJob by rememberSaveable { mutableStateOf("") }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var assistantIntent by rememberSaveable { mutableStateOf("") }
    var assistantQuestion by rememberSaveable { mutableStateOf("") }
    fun reviewAssistant(intent: JobsAssistantIntent, question: String) {
        assistantIntent = intent.name; assistantQuestion = question
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && exportText.isNotBlank()) onExport(uri, exportText)
        exportText = ""
    }
    var pendingId by rememberSaveable { mutableStateOf("") }
    var pendingStatus by rememberSaveable { mutableStateOf("") }
    var pendingNotes by rememberSaveable { mutableStateOf("") }
    var pendingDocument by rememberSaveable { mutableStateOf("") }
    var pendingDestination by rememberSaveable { mutableStateOf("detail") }
    var pendingPreferences by rememberSaveable { mutableStateOf("") }
    val pendingSaving = pendingId.isNotBlank() || pendingPreferences.isNotBlank()
    var restoredPending by remember { mutableStateOf(pendingSaving) }
    var saveUnconfirmed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.applications, state.preferences, pendingId, pendingPreferences, error, saving) {
        if (error == "storage_error" || error == "state_changed") {
            if (error == "state_changed") saveUnconfirmed = true
            pendingId = ""; pendingPreferences = ""
        } else if (pendingId.isNotBlank()) {
            val persisted = state.applications.firstOrNull { it.jobId == pendingId }
            if (persisted != null && persisted.status == pendingStatus && persisted.notes == pendingNotes && persisted.draft == pendingDocument) {
                pendingId = ""; route = pendingDestination
            }
        } else if (pendingPreferences.isNotBlank() && Json.encodeToString(state.preferences) == pendingPreferences) {
            pendingPreferences = ""; route = "browse"
        }
        if (restoredPending && !saving && (pendingId.isNotBlank() || pendingPreferences.isNotBlank())) {
            pendingId = ""; pendingPreferences = ""; saveUnconfirmed = true
        }
        if (!saving) restoredPending = false
    }
    fun requestPreferences(value: JobPreferences) {
        saveUnconfirmed = false
        pendingPreferences = Json.encodeToString(value)
        onPreferences(value)
    }
    fun requestApplication(nextStatus: String, destination: String) {
        if (selected == null || pendingSaving || saving) return
        saveUnconfirmed = false
        pendingId = selectedId; pendingStatus = nextStatus; pendingNotes = notes; pendingDocument = document; pendingDestination = destination
        onApplication(JobApplication(selectedId, nextStatus, notes, System.currentTimeMillis(), selected, document))
    }
    var confirmApplied by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    fun back() { route = when(route) { "document" -> "workspace"; "workspace" -> "detail"; else -> "browse" } }
    fun saveProgress() {
        requestApplication(status, "detail")
    }
    val dirty = route == "workspace" && (notes != application?.notes.orEmpty() || document != application?.draft.orEmpty() || status != (application?.status ?: "preparing"))
    BackHandler(route != "browse") { if (!pendingSaving) { if (dirty) confirmLeave = true else back() } }
    Column(Modifier.fillMaxSize()) {
        if (saveUnconfirmed) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.jobs_save_unconfirmed), Modifier.fillMaxWidth().padding(16.dp).testTag("jobs-save-unconfirmed"))
        }
        if (error == "context_changed") Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.jobs_error_context_changed), Modifier.padding(16.dp))
        }
        if (error == "storage_error") Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.jobs_error_storage), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).testTag("jobs-storage-error"))
        }
        Box(Modifier.weight(1f)) {
    when (route) {
        "onboard", "preferences" -> JobsGuidedSetup(state.preferences, route == "onboard", pendingSaving,
            contextSnapshot, state.jobs, onDraft = onPreferences,
            onBack = { route = "browse" }, onSave = { requestPreferences(it.copy(onboarded = true)) },
            onSkip = { requestPreferences(it.copy(onboarded = true)) },
            onAdvanced = { onPreferences(it); route = "advanced" })
        "advanced" -> PreferencesScreen(state.preferences, false, pendingSaving,
            onBack = { route = "preferences" }, onDraft = onPreferences,
            onSave = { requestPreferences(it.copy(onboarded = true)) }, onSkip = {})
        "detail", "workspace", "document" -> {
            if (selected == null) ScreenContainer(scrollKey = "job-missing") {
                BackButton { route = "browse" }
                ScreenTitle(stringResource(R.string.jobs_unavailable), stringResource(R.string.jobs_unavailable_body))
            } else if (route == "detail") {
                val ask = stringResource(R.string.jobs_ask_prompt)
                JobDetails(selected, state.preferences, application, saving, state.jobs.none { it.id == selectedId },
                    onBack = { route = "browse" }, onSave = { onSave(selected.id) },
                    onPrepare = { route = "workspace" }, onOpen = { onOpen(selected.url) },
                    onAsk = { reviewAssistant(JobsAssistantIntent.REQUIREMENTS, ask) },
                    related = { RelatedJobsModules(contextSnapshot, onOpenRelated) })
            } else if (route == "document") {
                val latest = lastAiDraft(selected)
                val outline = stringResource(R.string.jobs_draft_outline, selected.title, selected.company)
                ScreenContainer(scrollKey = "job-document-$selectedId") {
                    BackButton { back() }
                    ScreenTitle(stringResource(R.string.jobs_document_title), selected.title)
                    SupportingText(stringResource(R.string.jobs_document_help))
                    if (document.isBlank()) OutlinedButton(onClick = {
                        documentTouched = true; document = outline; reviewed = false; onClearExportResult()
                    }, enabled = !exportBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_start_outline)) }
                    if (latest.isNotBlank()) OutlinedButton(onClick = {
                        if (document.isNotBlank()) confirmReplace = true else { documentTouched = true; document = latest.take(24000); reviewed = false; onClearExportResult() }
                    }, enabled = !exportBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_import_answer)) }
                    OutlinedTextField(document, { documentTouched = true; document = it.take(24000); reviewed = false; onClearExportResult() },
                        label = { Text(stringResource(R.string.jobs_document_text)) }, minLines = 14, enabled = !exportBusy,
                        modifier = Modifier.fillMaxWidth().testTag("jobs-document-text"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val reviewLabel = stringResource(R.string.jobs_document_reviewed)
                        Checkbox(reviewed, { reviewed = it }, enabled = document.isNotBlank() && !exportBusy, modifier = Modifier.semantics { contentDescription = reviewLabel })
                        Text(stringResource(R.string.jobs_document_reviewed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = {
                        exportText = document
                        exportForJob = selectedId
                        onClearExportResult()
                        exportLauncher.launch("MeBot-application-draft.pdf")
                    }, enabled = document.isNotBlank() && reviewed && !exportBusy,
                        modifier = Modifier.fillMaxWidth().testTag("jobs-export-pdf")) {
                        Text(stringResource(if (exportBusy) R.string.jobs_exporting else R.string.jobs_export_pdf))
                    }
                    if (exportForJob == selectedId && exportResult.isNotBlank()) SupportingText(stringResource(when (exportResult) {
                        "saved" -> R.string.jobs_export_success
                        "interrupted" -> R.string.jobs_export_interrupted
                        "unsupported" -> R.string.jobs_export_unsupported
                        else -> R.string.jobs_export_failure
                    }))
                    OutlinedButton(onClick = {
                        // Editing a document does not imply the application was submitted.
                        requestApplication(application?.status ?: "preparing", "workspace")
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_save_draft)) }
                }
            } else {
                val documentPrompt = stringResource(R.string.jobs_ai_cover_prompt)
                val cvPrompt = stringResource(R.string.jobs_ai_cv_prompt)
                val interviewPrompt = stringResource(R.string.jobs_ai_interview_prompt)
                ScreenContainer(scrollKey = "job-workspace-$selectedId") {
                    BackButton { if (dirty) confirmLeave = true else back() }
                    ScreenTitle(stringResource(R.string.jobs_prepare_heading), selected.title)
                    SupportingText(stringResource(R.string.jobs_prepare_body))
                    Text(stringResource(R.string.jobs_prepare_step_one), style = MaterialTheme.typography.titleMedium)
                    AppCard {
                        OutlinedTextField(notes, { notesTouched = true; notes = it.take(10000) }, label = { Text(stringResource(R.string.jobs_notes)) },
                            placeholder = { Text(stringResource(R.string.jobs_notes_hint)) }, minLines = 5,
                            modifier = Modifier.fillMaxWidth().testTag("jobs-notes"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
                    }
                    Text(stringResource(R.string.jobs_prepare_step_two), style = MaterialTheme.typography.titleMedium)
                    AppCard {
                        Text(stringResource(R.string.jobs_manual_note), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { route = "document" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_document_title)) }
                        OutlinedButton(onClick = { reviewAssistant(JobsAssistantIntent.COVER_LETTER, documentPrompt) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.jobs_ai_cover_letter))
                        }
                        TextButton(onClick = { reviewAssistant(JobsAssistantIntent.CV_IMPROVEMENTS, cvPrompt) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_ai_cv)) }
                        TextButton(onClick = { reviewAssistant(JobsAssistantIntent.INTERVIEW, interviewPrompt) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_ai_interview)) }
                    }
                    Text(stringResource(R.string.jobs_prepare_step_three), style = MaterialTheme.typography.titleMedium)
                    StatusPicker(status) { statusTouched = true; status = it }
                    Button(onClick = { if (status == "applied" && application?.status != "applied") confirmApplied = true else saveProgress() },
                        enabled = !saving && !pendingSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("jobs-save-progress")) { Text(stringResource(R.string.jobs_save_progress)) }
                    OutlinedButton(onClick = { onOpen(selected.url) }, enabled = selected.url.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.jobs_view_source))
                    }
                }
            }
        }
        else -> {
            val savedIds = state.applications.map { it.jobId }.toSet()
            val snapshots = state.applications.mapNotNull { it.snapshot }
            val all = (state.jobs + snapshots).distinctBy { it.id }
            val visible = when (tab) {
                "saved" -> all.filter { it.id in savedIds && JobsMatcher.matches(it, JobPreferences(), query) }
                "applications" -> all.filter { job -> state.applications.any { it.jobId == job.id && it.status != "saved" } && JobsMatcher.matches(job, JobPreferences(), query) }
                "recent" -> state.jobs.filter { JobsMatcher.matches(it, JobPreferences(), query) }.sortedByDescending { it.postedAt }
                else -> state.jobs.filter { JobsMatcher.matches(it, state.preferences, query) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(modifier = Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().testTag("jobs-screen"),
                    contentPadding = PaddingValues(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.jobs_tab), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { route = "preferences" }, modifier = Modifier.testTag("jobs-preferences")) {
                                Icon(Icons.Outlined.Tune, stringResource(R.string.jobs_preferences))
                            }
                            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("jobs-refresh")) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.jobs_refresh))
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.jobs_browse_headline), style = MaterialTheme.typography.titleLarge)
                        SupportingText(stringResource(R.string.jobs_search_summary))
                        if (state.preferences.roles.isNotBlank()) Text(state.preferences.roles, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    item {
                        OutlinedTextField(query, { query = it.take(200) }, singleLine = true, placeholder = { Text(stringResource(R.string.jobs_search)) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.jobs_clear_filters)) } },
                            modifier = Modifier.fillMaxWidth().testTag("jobs-search"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("for_you" to R.string.jobs_for_you, "recent" to R.string.jobs_recent, "saved" to R.string.jobs_saved, "applications" to R.string.jobs_applications).forEach { (key, label) ->
                                FilterChip(tab == key, { tab = key }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("jobs-tab-$key"))
                            }
                        }
                    }
                    if (refreshPhase in setOf("queued", "running", "retrying", "offline", "cancelled")) item {
                        AppCard(color = MaterialTheme.colorScheme.surfaceVariant) {
                            SupportingText(stringResource(when (refreshPhase) {
                                "queued" -> R.string.jobs_refresh_queued
                                "running" -> R.string.jobs_refresh_running
                                "retrying" -> R.string.jobs_refresh_retrying
                                "offline" -> R.string.jobs_refresh_offline
                                else -> R.string.jobs_refresh_cancelled
                            }))
                            if (refreshPhase != "cancelled") TextButton(onClick = onCancelRefresh) {
                                Text(stringResource(R.string.jobs_cancel_refresh))
                            }
                        }
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); SupportingText(stringResource(R.string.jobs_loading)) }
                    if (error != null && error != "storage_error" && error != "state_changed" && error != "context_changed") item {
                        AppCard(color = MaterialTheme.colorScheme.surfaceVariant) {
                            SupportingText(stringResource(when (error) { "offline" -> R.string.jobs_error_offline; "cached" -> R.string.jobs_error_cached; "partial" -> R.string.jobs_error_partial; "context_changed" -> R.string.jobs_error_context_changed; else -> R.string.jobs_error_provider }))
                        }
                    }
                    item {
                        if (state.lastRefreshAt > 0) SupportingText(stringResource(R.string.jobs_last_refresh, date(state.lastRefreshAt)))
                        Text(stringResource(R.string.jobs_count, visible.size), style = MaterialTheme.typography.labelLarge)
                    }
                    if (visible.isEmpty() && !loading) item {
                        val title = when { tab == "saved" -> R.string.jobs_empty_saved; tab == "applications" -> R.string.jobs_empty_tracker; state.jobs.isEmpty() -> R.string.jobs_empty; else -> R.string.jobs_no_matches }
                        val body = when { tab == "saved" -> R.string.jobs_empty_saved_body; tab == "applications" -> R.string.jobs_empty_tracker_body; state.jobs.isEmpty() -> R.string.jobs_empty_body; else -> R.string.jobs_no_matches_body }
                        AppCard {
                            Icon(Icons.Outlined.WorkOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                            SupportingText(stringResource(body))
                            TextButton(onClick = { tab = "recent"; query = "" }) { Text(stringResource(R.string.jobs_browse_latest)) }
                        }
                    }
                    items(visible, key = { it.id }) { job ->
                        JobCard(job, state.preferences, state.applications.firstOrNull { it.jobId == job.id },
                            onSelect = { selectedId = job.id; route = "detail" }, onSave = { onSave(job.id) }, saving = saving)
                    }
                    if (contextSnapshot.modules.isNotEmpty()) item { RelatedJobsModules(contextSnapshot, onOpenRelated) }
                    item { SupportingText(stringResource(R.string.jobs_source_note)) }
                    if (state.preferences.country.trim().lowercase(Locale.ROOT) in setOf("tanzania", "tz")) item {
                        AppCard {
                            Text(stringResource(R.string.jobs_tanzanian_sources), style = MaterialTheme.typography.titleMedium)
                            SupportingText(stringResource(R.string.jobs_external_sources_note))
                            OutlinedButton(onClick = { onOpen("https://work.tz/en/jobs/") }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.jobs_work_tz))
                            }
                            OutlinedButton(onClick = { onOpen("https://portal.ajira.go.tz/app/dashboard/") }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.jobs_ajira_portal))
                            }
                        }
                    }
                    // Keep the final item clear of the floating navigation and system gesture area.
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
        }
    }
    if (pendingSaving) AlertDialog(onDismissRequest = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.jobs_saving))
        }
    }, confirmButton = {}, dismissButton = {
        TextButton(onClick = { pendingId = ""; pendingPreferences = "" }) { Text(stringResource(R.string.jobs_keep_editing)) }
    })
    if (confirmApplied) AlertDialog(onDismissRequest = { confirmApplied = false },
        title = { Text(stringResource(R.string.jobs_applied_confirm_title)) }, text = { Text(stringResource(R.string.jobs_applied_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmApplied = false; saveProgress() }) { Text(stringResource(R.string.jobs_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmApplied = false }) { Text(stringResource(R.string.jobs_cancel)) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.jobs_notes)) }, text = {
            Column {
                Text(stringResource(R.string.jobs_notes_retained))
                TextButton(onClick = {
                    notes = application?.notes.orEmpty(); document = application?.draft.orEmpty()
                    status = application?.status ?: "preparing"; confirmLeave = false; back()
                }) { Text(stringResource(R.string.jobs_discard)) }
            }
        },
        confirmButton = { TextButton(onClick = {
            confirmLeave = false
            if (status == "applied" && application?.status != "applied") confirmApplied = true else saveProgress()
        }) { Text(stringResource(R.string.jobs_save_progress)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.jobs_cancel)) } })
    if (assistantIntent.isNotBlank() && selected != null) JobsAiReview(
        intent = JobsAssistantIntent.valueOf(assistantIntent), question = assistantQuestion, snapshot = contextSnapshot,
        application = JobApplication(selected.id, if (status == "applied" && application?.status != "applied") application?.status ?: "preparing" else status, notes, System.currentTimeMillis(), selected, document),
        provider = aiProviderLabel, supportsContext = onAssistant != null, connected = aiConnected,
        onConnect = { value -> assistantIntent = ""; onConnectAi(value) },
        onDismiss = { assistantIntent = "" }, onSend = { request ->
            assistantIntent = ""
            if (onAssistant != null) onAssistant(selected, request) else onAsk(selected, request.question)
        })
    if (confirmReplace && selected != null) AlertDialog(onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.jobs_replace_title)) }, text = { Text(stringResource(R.string.jobs_replace_body)) },
        confirmButton = { TextButton(onClick = { documentTouched = true; document = lastAiDraft(selected).take(24000); reviewed = false; onClearExportResult(); confirmReplace = false }) { Text(stringResource(R.string.jobs_replace)) } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.jobs_cancel)) } })
}

@Composable
private fun PreferencesScreen(preferences: JobPreferences, onboarding: Boolean, draftPaused: Boolean, onBack: () -> Unit, onDraft: (JobPreferences) -> Unit, onSave: (JobPreferences) -> Unit, onSkip: () -> Unit) {
    var roles by rememberSaveable { mutableStateOf(preferences.roles) }
    var skills by rememberSaveable { mutableStateOf(preferences.skills) }
    var country by rememberSaveable { mutableStateOf(preferences.country) }
    var remote by rememberSaveable { mutableStateOf(preferences.remoteOnly) }
    var employment by rememberSaveable { mutableStateOf(preferences.employmentType) }
    var salary by rememberSaveable { mutableStateOf(preferences.salaryMinimum) }
    var salaryCurrency by rememberSaveable { mutableStateOf(preferences.salaryCurrency) }
    var salaryPeriod by rememberSaveable { mutableStateOf(preferences.salaryPeriod) }
    val minimum = salary.trim().toDoubleOrNull()
    val minimumValid = salary.isBlank() || (minimum != null && minimum.isFinite() && minimum >= 0)
    val currencyValid = runCatching { Currency.getInstance(salaryCurrency.trim().uppercase(Locale.ROOT)) }.isSuccess
    val salaryReady = salary.isBlank() || (minimumValid && currencyValid && salaryPeriod in setOf("hour", "month", "year"))
    var alerts by rememberSaveable { mutableStateOf(preferences.alertsEnabled) }
    var frequency by rememberSaveable { mutableIntStateOf(preferences.alertFrequencyHours) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    // Disk state can arrive after initial composition. Never overwrite an active edit with a late save.
    LaunchedEffect(preferences) {
        if (!touched) {
            roles = preferences.roles; skills = preferences.skills; country = preferences.country
            remote = preferences.remoteOnly; employment = preferences.employmentType; salary = preferences.salaryMinimum
            salaryCurrency = preferences.salaryCurrency; salaryPeriod = preferences.salaryPeriod
            alerts = preferences.alertsEnabled; frequency = preferences.alertFrequencyHours
        }
    }
    LaunchedEffect(roles, skills, country, remote, employment, salary, salaryCurrency, salaryPeriod, draftPaused) {
        if (touched && !draftPaused) {
            delay(500)
            // Draft edits do not schedule notifications or finish onboarding. Alerts require Save preferences.
            onDraft(preferences.copy(roles = roles, skills = skills, country = country, remoteOnly = remote,
                employmentType = employment, salaryMinimum = salary, salaryCurrency = salaryCurrency.trim().uppercase(Locale.ROOT), salaryPeriod = salaryPeriod))
        }
    }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        touched = true; alerts = granted && notificationsAvailable(context); notificationDenied = !alerts
    }
    ScreenContainer(scrollKey = "jobs-preferences") {
        if (!onboarding) BackButton(onBack)
        ScreenTitle(stringResource(R.string.jobs_advanced_preferences), stringResource(R.string.jobs_advanced_body))
        AppCard {
            OutlinedTextField(salary, { touched = true; salary = it.take(40) },
                label = { Text(stringResource(R.string.jobs_salary_pref)) },
                supportingText = { Text(stringResource(if (minimumValid) R.string.jobs_salary_hint else R.string.jobs_salary_invalid)) },
                isError = !minimumValid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("jobs-salary-minimum"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
            OutlinedTextField(salaryCurrency, { touched = true; salaryCurrency = it.take(3).uppercase(Locale.ROOT) },
                label = { Text(stringResource(R.string.jobs_salary_currency)) },
                supportingText = { Text(stringResource(R.string.jobs_salary_currency_hint)) },
                isError = salaryCurrency.isNotBlank() && !currencyValid, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth().testTag("jobs-salary-currency"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
            Text(stringResource(R.string.jobs_salary_period), style = MaterialTheme.typography.labelLarge)
            DropdownChoice(salaryPeriod, listOf("", "hour", "month", "year"), { periodLabel(it) },
                modifier = Modifier.testTag("jobs-salary-period")) { touched = true; salaryPeriod = it }
            if (salary.isNotBlank() && !salaryReady) SupportingText(stringResource(R.string.jobs_salary_incomplete))
            SupportingText(stringResource(R.string.jobs_salary_note))
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.jobs_alerts_option), style = MaterialTheme.typography.titleMedium)
                    SupportingText(stringResource(R.string.jobs_alerts_help))
                }
                val alertsLabel = stringResource(R.string.jobs_alerts_option)
                Switch(alerts, { enabled ->
                    touched = true
                    if (!enabled) alerts = false
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        alerts = notificationsAvailable(context)
                        notificationDenied = !alerts
                    }
                }, modifier = Modifier.semantics { contentDescription = alertsLabel })
            }
            if (notificationDenied || (alerts && !notificationsAvailable(context))) SupportingText(stringResource(R.string.jobs_notification_denied))
            if (alerts) DropdownChoice(if (frequency >= 168) "weekly" else "daily", listOf("daily", "weekly"),
                { stringResource(if (it == "weekly") R.string.jobs_weekly else R.string.jobs_daily) }) { touched = true; frequency = if (it == "weekly") 168 else 24 }
        }
        Button(onClick = { onSave(preferences.copy(roles = roles.trim(), skills = skills.trim(), country = country.trim(), remoteOnly = remote, employmentType = employment, salaryMinimum = salary.trim(), salaryCurrency = salaryCurrency.trim().uppercase(Locale.ROOT), salaryPeriod = salaryPeriod, alertsEnabled = alerts, alertFrequencyHours = frequency)) },
            enabled = salaryReady, modifier = Modifier.fillMaxWidth().testTag("jobs-preferences-save")) { Text(stringResource(if (onboarding) R.string.jobs_continue else R.string.jobs_save_preferences)) }
        if (onboarding) TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_skip)) }
    }
}

@Composable
private fun PreferenceField(value: String, onValue: (String) -> Unit, label: Int, hint: Int) {
    OutlinedTextField(value, onValue, label = { Text(stringResource(label)) }, supportingText = { Text(stringResource(hint)) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(MeBotTokens.ControlRadius), maxLines = 3)
}

@Composable
private fun StatusPicker(status: String, onStatus: (String) -> Unit) = DropdownChoice(status,
    listOf("saved", "preparing", "applied", "interview", "offer", "closed"), { statusLabel(it) }, onSelect = onStatus)

@Composable
private fun DropdownChoice(value: String, values: List<String>, label: @Composable (String) -> String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(label(value), modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item -> DropdownMenuItem(text = { Text(label(item)) }, onClick = { expanded = false; onSelect(item) }) }
        }
    }
}

@Composable
private fun MetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) = TextButton(onClick = onBack) {
    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.jobs_back))
}

@Composable
internal fun employmentLabel(value: String): String = when (value.lowercase().replace('-', '_').replace(' ', '_')) {
    "" -> stringResource(R.string.jobs_any)
    "full_time", "fulltime" -> stringResource(R.string.jobs_full_time)
    "part_time", "parttime" -> stringResource(R.string.jobs_part_time)
    "contract" -> stringResource(R.string.jobs_contract)
    else -> value
}

@Composable
internal fun statusLabel(value: String): String = stringResource(when (value) {
    "preparing" -> R.string.jobs_status_preparing; "applied" -> R.string.jobs_status_applied
    "interview" -> R.string.jobs_status_interview; "offer" -> R.string.jobs_status_offer; "closed" -> R.string.jobs_status_closed
    else -> R.string.jobs_status_saved
})

@Composable
internal fun reasonLabel(value: String): String = stringResource(when (value) {
    "role_match" -> R.string.jobs_role_match; "skills_match" -> R.string.jobs_skills_match
    "remote_match" -> R.string.jobs_remote_match; "employment_match" -> R.string.jobs_employment_match
    "location_match" -> R.string.jobs_location_match; "salary_match" -> R.string.jobs_salary_match; "salary_unverified" -> R.string.jobs_salary_unverified
    else -> R.string.jobs_eligibility_unknown
})

internal fun date(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun notificationsAvailable(context: android.content.Context): Boolean {
    val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("mebot_jobs")
    return NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
}

@Composable
private fun periodLabel(value: String): String = stringResource(when (value) {
    "hour" -> R.string.jobs_salary_hour; "month" -> R.string.jobs_salary_month; "year" -> R.string.jobs_salary_year
    else -> R.string.jobs_salary_choose_period
})
