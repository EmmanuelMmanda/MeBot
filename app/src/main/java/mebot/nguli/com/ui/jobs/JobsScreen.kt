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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mebot.nguli.com.jobs.JobsAssistant
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
    var route by rememberSaveable { mutableStateOf(if (state.preferences.onboarded) "browse" else "onboard") }
    LaunchedEffect(state.preferences.onboarded) {
        if (state.preferences.onboarded && route == "onboard") route = "browse"
    }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("for_you") }
    var query by rememberSaveable { mutableStateOf("") }
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
    var exportBusy by remember { mutableStateOf(false) }
    var exportResult by rememberSaveable(selectedId) { mutableStateOf("") }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && exportText.isNotBlank()) {
            val snapshot = exportText
            exportBusy = true
            scope.launch {
                exportResult = try {
                    withContext(Dispatchers.IO) {
                        val bytes = JobsAssistant.draftPdfBytes(snapshot)
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                    }
                    "saved"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "failed" }
                finally { exportBusy = false; exportText = "" }
            }
        } else { exportText = "" }
    }
    var confirmApplied by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    fun back() { route = when(route) { "document" -> "workspace"; "workspace" -> "detail"; else -> "browse" } }
    fun saveProgress() {
        onApplication(JobApplication(selectedId, status, notes, System.currentTimeMillis(), selected, document))
        route = "detail"
    }
    val dirty = route == "workspace" && (notes != application?.notes.orEmpty() || document != application?.draft.orEmpty() || status != (application?.status ?: "preparing"))
    BackHandler(route != "browse") { if (dirty) confirmLeave = true else back() }
    when (route) {
        "onboard", "preferences" -> PreferencesScreen(state.preferences, route == "onboard",
            onBack = { route = "browse" }, onDraft = onPreferences,
            onSave = { onPreferences(it.copy(onboarded = true)); route = "browse" },
            onSkip = { onPreferences(state.preferences.copy(onboarded = true)); route = "browse" })
        "detail", "workspace", "document" -> {
            if (selected == null) ScreenContainer(scrollKey = "job-missing") {
                BackButton { route = "browse" }
                ScreenTitle(stringResource(R.string.jobs_unavailable), stringResource(R.string.jobs_unavailable_body))
            } else if (route == "detail") {
                val ask = stringResource(R.string.jobs_ask_prompt)
                ScreenContainer(scrollKey = "job-$selectedId") {
                    BackButton { route = "browse" }
                    ScreenTitle(selected.title, selected.company, stringResource(R.string.jobs_details))
                    AppCard {
                        MetaRow(Icons.Outlined.LocationOn, selected.location.ifBlank { stringResource(R.string.jobs_location_unknown) })
                        if (selected.remote) MetaRow(Icons.Outlined.Language, stringResource(R.string.jobs_remote))
                        if (selected.employmentType.isNotBlank()) MetaRow(Icons.Outlined.WorkOutline, employmentLabel(selected.employmentType))
                        if (selected.postedAt > 0) SupportingText(stringResource(R.string.jobs_posted, date(selected.postedAt)))
                        HorizontalDivider()
                        Text(stringResource(R.string.jobs_salary), style = MaterialTheme.typography.labelLarge)
                        Text(selected.salary.ifBlank { stringResource(R.string.jobs_not_disclosed) })
                        Text(stringResource(R.string.jobs_source), style = MaterialTheme.typography.labelLarge)
                        Text(selected.source)
                        if (selected.fetchedAt > 0) SupportingText(stringResource(R.string.jobs_last_refresh, date(selected.fetchedAt)))
                    }
                    AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.jobs_match), style = MaterialTheme.typography.titleMedium)
                        JobsMatcher.reasons(selected, state.preferences).forEach { SupportingText(reasonLabel(it)) }
                    }
                    Button(onClick = { route = "workspace" }, modifier = Modifier.fillMaxWidth().testTag("jobs-prepare")) {
                        Text(stringResource(R.string.jobs_prepare))
                    }
                    if (application == null || (application.status == "saved" && application.notes.isBlank() && application.draft.isBlank())) {
                        OutlinedButton(onClick = { onSave(selected.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (application != null) R.string.jobs_unsave else R.string.jobs_save))
                        }
                    } else SupportingText(statusLabel(application.status))
                    OutlinedButton(onClick = { onOpen(selected.url) }, enabled = selected.url.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.jobs_view_source))
                    }
                    AppCard {
                        Text(stringResource(R.string.jobs_requirements), style = MaterialTheme.typography.titleMedium)
                        val description = remember(selected.description) { Html.fromHtml(selected.description, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                        Text(description.ifBlank { stringResource(R.string.jobs_no_description) }, style = MaterialTheme.typography.bodyMedium)
                    }
                    AppCard {
                        Text(stringResource(R.string.jobs_assistant_hint), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onAsk(selected, ask) }) { Text(stringResource(R.string.jobs_assistant)) }
                    }
                    SupportingText(stringResource(R.string.jobs_source_note))
                }
            } else if (route == "document") {
                val latest = lastAiDraft(selected)
                ScreenContainer(scrollKey = "job-document-$selectedId") {
                    BackButton { back() }
                    ScreenTitle(stringResource(R.string.jobs_document_title), selected.title)
                    SupportingText(stringResource(R.string.jobs_document_help))
                    if (latest.isNotBlank()) OutlinedButton(onClick = {
                        if (document.isNotBlank()) confirmReplace = true else { documentTouched = true; document = latest.take(24000); reviewed = false }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_import_answer)) }
                    OutlinedTextField(document, { documentTouched = true; document = it.take(24000); reviewed = false; exportResult = "" },
                        label = { Text(stringResource(R.string.jobs_document_text)) }, minLines = 14,
                        modifier = Modifier.fillMaxWidth().testTag("jobs-document-text"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(reviewed, { reviewed = it }, enabled = document.isNotBlank())
                        Text(stringResource(R.string.jobs_document_reviewed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = {
                        exportText = document
                        exportResult = ""
                        exportLauncher.launch("MeBot-application-draft.pdf")
                    }, enabled = document.isNotBlank() && reviewed && !exportBusy,
                        modifier = Modifier.fillMaxWidth().testTag("jobs-export-pdf")) {
                        Text(stringResource(if (exportBusy) R.string.jobs_exporting else R.string.jobs_export_pdf))
                    }
                    if (exportResult.isNotBlank()) SupportingText(stringResource(if (exportResult == "saved") R.string.jobs_export_success else R.string.jobs_export_failure))
                    OutlinedButton(onClick = {
                        // Editing a document does not imply the application was submitted.
                        onApplication(JobApplication(selectedId, application?.status ?: "preparing", notes, System.currentTimeMillis(), selected, document))
                        route = "workspace"
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_save_draft)) }
                }
            } else {
                val documentPrompt = stringResource(R.string.jobs_document_prompt)
                ScreenContainer(scrollKey = "job-workspace-$selectedId") {
                    BackButton { if (dirty) confirmLeave = true else back() }
                    ScreenTitle(stringResource(R.string.jobs_workspace), selected.title)
                    AppCard {
                        Text(stringResource(R.string.jobs_status), style = MaterialTheme.typography.titleMedium)
                        StatusPicker(status) { statusTouched = true; status = it }
                        OutlinedTextField(notes, { notesTouched = true; notes = it.take(10000) }, label = { Text(stringResource(R.string.jobs_notes)) },
                            placeholder = { Text(stringResource(R.string.jobs_notes_hint)) }, minLines = 5,
                            modifier = Modifier.fillMaxWidth().testTag("jobs-notes"), shape = RoundedCornerShape(MeBotTokens.ControlRadius))
                        Button(onClick = { if (status == "applied" && application?.status != "applied") confirmApplied = true else saveProgress() },
                            modifier = Modifier.fillMaxWidth().testTag("jobs-save-progress")) { Text(stringResource(R.string.jobs_save_progress)) }
                    }
                    AppCard {
                        Text(stringResource(R.string.jobs_manual_note), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { route = "document" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_document_title)) }
                        OutlinedButton(onClick = { onAsk(selected, documentPrompt) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.jobs_ai_documents))
                        }
                    }
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
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); SupportingText(stringResource(R.string.jobs_loading)) }
                    if (error != null) item {
                        AppCard(color = MaterialTheme.colorScheme.surfaceVariant) {
                            SupportingText(stringResource(when (error) { "offline" -> R.string.jobs_error_offline; "cached" -> R.string.jobs_error_cached; "partial" -> R.string.jobs_error_partial; else -> R.string.jobs_error_provider }))
                        }
                    }
                    item {
                        SupportingText(stringResource(R.string.jobs_source_note))
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
                            onSelect = { selectedId = job.id; route = "detail" }, onSave = { onSave(job.id) })
                    }
                    // Keep the final item clear of the floating navigation and system gesture area.
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
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
    if (confirmReplace && selected != null) AlertDialog(onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.jobs_replace_title)) }, text = { Text(stringResource(R.string.jobs_replace_body)) },
        confirmButton = { TextButton(onClick = { documentTouched = true; document = lastAiDraft(selected).take(24000); reviewed = false; confirmReplace = false }) { Text(stringResource(R.string.jobs_replace)) } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.jobs_cancel)) } })
}

@Composable
private fun JobCard(job: JobPosting, preferences: JobPreferences, application: JobApplication?, onSelect: () -> Unit, onSave: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).clickable(onClick = onSelect).padding(vertical = 4.dp)) {
                Text(job.company, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(job.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (application == null || (application.status == "saved" && application.notes.isBlank() && application.draft.isBlank())) IconButton(onClick = onSave) { Icon(if (application == null) Icons.Outlined.BookmarkBorder else Icons.Outlined.Bookmark,
                stringResource(if (application == null) R.string.jobs_save else R.string.jobs_unsave), tint = MaterialTheme.colorScheme.primary) }
        }
        MetaRow(Icons.Outlined.LocationOn, job.location.ifBlank { stringResource(R.string.jobs_location_unknown) })
        if (job.employmentType.isNotBlank()) SupportingText(employmentLabel(job.employmentType))
        Text(job.salary.ifBlank { stringResource(R.string.jobs_not_disclosed) }, style = MaterialTheme.typography.bodyMedium)
        Text(job.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (application != null) SuggestionChip(onClick = onSelect, label = { Text(statusLabel(application.status)) })
        JobsMatcher.reasons(job, preferences).take(2).forEach { SupportingText(reasonLabel(it)) }
        TextButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.jobs_details)) }
    }
}

@Composable
private fun PreferencesScreen(preferences: JobPreferences, onboarding: Boolean, onBack: () -> Unit, onDraft: (JobPreferences) -> Unit, onSave: (JobPreferences) -> Unit, onSkip: () -> Unit) {
    var roles by rememberSaveable { mutableStateOf(preferences.roles) }
    var skills by rememberSaveable { mutableStateOf(preferences.skills) }
    var country by rememberSaveable { mutableStateOf(preferences.country) }
    var remote by rememberSaveable { mutableStateOf(preferences.remoteOnly) }
    var employment by rememberSaveable { mutableStateOf(preferences.employmentType) }
    var salary by rememberSaveable { mutableStateOf(preferences.salaryMinimum) }
    var alerts by rememberSaveable { mutableStateOf(preferences.alertsEnabled) }
    var frequency by rememberSaveable { mutableIntStateOf(preferences.alertFrequencyHours) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    // Disk state can arrive after initial composition. Never overwrite an active edit with a late save.
    LaunchedEffect(preferences) {
        if (!touched) {
            roles = preferences.roles; skills = preferences.skills; country = preferences.country
            remote = preferences.remoteOnly; employment = preferences.employmentType; salary = preferences.salaryMinimum
            alerts = preferences.alertsEnabled; frequency = preferences.alertFrequencyHours
        }
    }
    LaunchedEffect(roles, skills, country, remote, employment, salary) {
        if (touched) {
            delay(500)
            // Draft edits do not schedule notifications or finish onboarding. Alerts require Save preferences.
            onDraft(preferences.copy(roles = roles, skills = skills, country = country, remoteOnly = remote,
                employmentType = employment, salaryMinimum = salary))
        }
    }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts = granted; notificationDenied = !granted
    }
    ScreenContainer(scrollKey = "jobs-preferences") {
        if (!onboarding) BackButton(onBack)
        ScreenTitle(stringResource(if (onboarding) R.string.jobs_intro else R.string.jobs_preferences), stringResource(R.string.jobs_intro_body))
        AppCard {
            PreferenceField(roles, { touched = true; roles = it.take(500) }, R.string.jobs_roles, R.string.jobs_roles_hint)
            PreferenceField(skills, { touched = true; skills = it.take(1000) }, R.string.jobs_skills, R.string.jobs_skills_hint)
            PreferenceField(country, { touched = true; country = it.take(100) }, R.string.jobs_country, R.string.jobs_country_hint)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.jobs_remote_only), modifier = Modifier.weight(1f))
                Switch(remote, { touched = true; remote = it })
            }
        }
        AppCard {
            Text(stringResource(R.string.jobs_employment), style = MaterialTheme.typography.titleMedium)
            DropdownChoice(employment, listOf("", "full_time", "part_time", "contract"), { employmentLabel(it) }) { touched = true; employment = it }
            PreferenceField(salary, { touched = true; salary = it.take(120) }, R.string.jobs_salary_pref, R.string.jobs_salary_hint)
            SupportingText(stringResource(R.string.jobs_salary_note))
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.jobs_alerts_option), style = MaterialTheme.typography.titleMedium)
                    SupportingText(stringResource(R.string.jobs_alerts_help))
                }
                Switch(alerts, { enabled ->
                    if (!enabled) alerts = false
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        alerts = NotificationManagerCompat.from(context).areNotificationsEnabled()
                        notificationDenied = !alerts
                    }
                })
            }
            if (notificationDenied) SupportingText(stringResource(R.string.jobs_notification_denied))
            if (alerts) DropdownChoice(if (frequency >= 168) "weekly" else "daily", listOf("daily", "weekly"),
                { stringResource(if (it == "weekly") R.string.jobs_weekly else R.string.jobs_daily) }) { frequency = if (it == "weekly") 168 else 24 }
        }
        Button(onClick = { onSave(preferences.copy(roles = roles.trim(), skills = skills.trim(), country = country.trim(), remoteOnly = remote, employmentType = employment, salaryMinimum = salary.trim(), alertsEnabled = alerts, alertFrequencyHours = frequency)) },
            modifier = Modifier.fillMaxWidth().testTag("jobs-preferences-save")) { Text(stringResource(if (onboarding) R.string.jobs_continue else R.string.jobs_save_preferences)) }
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
    listOf("saved", "preparing", "applied", "interview", "offer", "closed"), { statusLabel(it) }, onStatus)

@Composable
private fun DropdownChoice(value: String, values: List<String>, label: @Composable (String) -> String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
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
private fun employmentLabel(value: String): String = when (value.lowercase().replace('-', '_').replace(' ', '_')) {
    "" -> stringResource(R.string.jobs_any)
    "full_time", "fulltime" -> stringResource(R.string.jobs_full_time)
    "part_time", "parttime" -> stringResource(R.string.jobs_part_time)
    "contract" -> stringResource(R.string.jobs_contract)
    else -> value
}

@Composable
private fun statusLabel(value: String): String = stringResource(when (value) {
    "preparing" -> R.string.jobs_status_preparing; "applied" -> R.string.jobs_status_applied
    "interview" -> R.string.jobs_status_interview; "offer" -> R.string.jobs_status_offer; "closed" -> R.string.jobs_status_closed
    else -> R.string.jobs_status_saved
})

@Composable
private fun reasonLabel(value: String): String = stringResource(when (value) {
    "role_match" -> R.string.jobs_role_match; "skills_match" -> R.string.jobs_skills_match
    "remote_match" -> R.string.jobs_remote_match; "employment_match" -> R.string.jobs_employment_match
    "location_match" -> R.string.jobs_location_match; "salary_unverified" -> R.string.jobs_salary_unverified
    else -> R.string.jobs_eligibility_unknown
})

private fun date(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
