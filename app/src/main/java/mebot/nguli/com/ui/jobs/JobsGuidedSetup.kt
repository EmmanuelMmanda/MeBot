package mebot.nguli.com.ui.jobs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mebot.nguli.com.R
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.ui.*

private data class JobChoice(val value: String, val label: Int)
private val roleChoices = listOf(
    JobChoice("engineer, developer", R.string.jobs_role_technology), JobChoice("design", R.string.jobs_role_design),
    JobChoice("data, analyst", R.string.jobs_role_data), JobChoice("marketing, content", R.string.jobs_role_marketing),
    JobChoice("sales, business development", R.string.jobs_role_sales), JobChoice("support, customer", R.string.jobs_role_support),
    JobChoice("operations, project", R.string.jobs_role_operations), JobChoice("finance, accounting", R.string.jobs_role_finance))
private fun tokens(value: String) = value.split(',',';','\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()
private fun toggleChoice(value: String, choice: String): String {
    val current = tokens(value); val target = tokens(choice)
    return (if (target.all { it in current }) current - target.toSet() else current + target).distinct().joinToString(", ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JobsGuidedSetup(preferences: JobPreferences, onboarding: Boolean, busy: Boolean,
    contextSnapshot: JobsContextSnapshot, jobs: List<JobPosting>, onDraft: (JobPreferences) -> Unit,
    onSave: (JobPreferences) -> Unit, onSkip: (JobPreferences) -> Unit, onBack: () -> Unit, onAdvanced: (JobPreferences) -> Unit) {
    var draftJson by rememberSaveable { mutableStateOf(Json.encodeToString(preferences)) }
    val draft = remember(draftJson) { Json.decodeFromString<JobPreferences>(draftJson) }
    var step by rememberSaveable { mutableIntStateOf(preferences.onboardingStep.coerceIn(0,3)) }
    var customRole by rememberSaveable { mutableStateOf(false) }
    var customSkill by rememberSaveable { mutableStateOf(false) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    var prefillConsidered by rememberSaveable { mutableStateOf(false) }
    var prefillSource by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    fun update(value: JobPreferences) { prefillConsidered = true; draftJson = Json.encodeToString(value.copy(onboardingStep = step)); onDraft(value.copy(onboardingStep = step)) }
    fun go(next: Int) { step = next; update(draft.copy(onboardingStep = next)) }
    LaunchedEffect(contextSnapshot.revision) {
        if (!prefillConsidered && draft.roles.isBlank()) {
            contextSnapshot.candidates.firstOrNull { it.field.name == "ROLE_INTEREST" && it.reasonKey == "explicit_role_review" }?.let { candidate ->
                prefillSource = candidate.sourceLabel
                update(draft.copy(roles = candidate.value))
            }
        }
    }
    BackHandler { if (step > 0) go(step - 1) else onBack() }
    val titles = listOf(R.string.jobs_setup_roles, R.string.jobs_setup_skills, R.string.jobs_setup_location, R.string.jobs_setup_review)
    val subtitles = listOf(R.string.jobs_setup_roles_body, R.string.jobs_setup_skills_body, R.string.jobs_setup_location_body, R.string.jobs_setup_review_body)
    val skillChoices = remember(draft.roles) {
        buildList {
            if (listOf("engineer", "developer", "data", "analyst").any { draft.roles.contains(it, true) }) addAll(listOf("Python", "JavaScript", "SQL", "Kotlin", "Excel"))
            if (draft.roles.contains("design", true)) addAll(listOf("Figma", "UX", "Research", "Prototyping"))
            if (listOf("marketing", "content", "sales").any { draft.roles.contains(it, true) }) addAll(listOf("SEO", "Copywriting", "CRM", "Analytics"))
            if (listOf("operations", "project", "finance", "accounting").any { draft.roles.contains(it, true) }) addAll(listOf("Excel", "Project management", "Accounting", "Reporting"))
            addAll(listOf("Communication", "Customer service", "Writing", "Leadership"))
        }.distinct()
    }
    Column(Modifier.fillMaxSize().testTag("jobs-guided-setup"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step > 0) go(step - 1) else onBack() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.jobs_previous))
            }
            Text(stringResource(R.string.jobs_step_of, step + 1, 4), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (onboarding) TextButton(onClick = { onSkip(draft) }, enabled = !busy) { Text(stringResource(R.string.jobs_setup_later)) }
        }
        LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Column(Modifier.weight(1f).widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().verticalScroll(scroll)
            .padding(MeBotTokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(titles[step]), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("jobs-setup-step-$step"))
                SupportingText(stringResource(subtitles[step]))
            }
            when (step) {
                0 -> {
                    if (prefillSource.isNotBlank()) SupportingText(stringResource(R.string.jobs_suggested_prefill, prefillSource))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.roles.isBlank(), { update(draft.copy(roles = "")) }, label = { Text(stringResource(R.string.jobs_explore_all_roles)) }, modifier = Modifier.heightIn(min = 48.dp))
                        roleChoices.forEach { choice -> FilterChip(tokens(choice.value).all { it in tokens(draft.roles) },
                            { update(draft.copy(roles = toggleChoice(draft.roles, choice.value))) },
                            label = { Text(stringResource(choice.label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("jobs-role-${choice.value.substringBefore(",").replace(" ", "-")}")) }
                    }
                    val suggestions = contextSnapshot.candidates.filter { it.field.name == "ROLE_INTEREST" && !draft.roles.contains(it.value, true) }.take(4)
                    if (suggestions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.jobs_from_mebot), style = MaterialTheme.typography.titleSmall)
                        SupportingText(stringResource(R.string.jobs_context_suggestion_help))
                        suggestions.forEach { candidate -> OutlinedButton(onClick = { update(draft.copy(roles = (tokens(draft.roles) + candidate.value).distinct().joinToString(", "))) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) { Text(candidate.value); Text(candidate.sourceLabel, style = MaterialTheme.typography.labelSmall) }
                            Icon(Icons.Outlined.Add, null)
                        } }
                    }
                    if (customRole) OutlinedTextField(draft.roles, { update(draft.copy(roles = it.take(500))) },
                        label = { Text(stringResource(R.string.jobs_selected_roles)) }, supportingText = { Text(stringResource(R.string.jobs_roles_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("jobs-role-custom"), shape = RoundedCornerShape(16.dp))
                    else TextButton(onClick = { customRole = true }) { Text(stringResource(if (draft.roles.isBlank()) R.string.jobs_add_own_role else R.string.jobs_edit_keywords)) }
                    SupportingText(stringResource(if (draft.roles.isBlank()) R.string.jobs_all_roles_default else R.string.jobs_choices_editable))
                }
                1 -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (skillChoices + tokens(draft.skills)).distinct().forEach { skill -> FilterChip(skill in tokens(draft.skills),
                            { update(draft.copy(skills = toggleChoice(draft.skills, skill))) }, label = { Text(skill) }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                    val suggestions = contextSnapshot.candidates.filter { it.field.name == "SKILL_TO_CONFIRM" }.take(5)
                    suggestions.forEach { candidate -> OutlinedButton(onClick = { update(draft.copy(skills = toggleChoice(draft.skills, candidate.value))) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text(candidate.value); Text(stringResource(R.string.jobs_confirm_skill_source, candidate.sourceLabel), style = MaterialTheme.typography.labelSmall) }
                        Icon(if (candidate.value in tokens(draft.skills)) Icons.Outlined.Check else Icons.Outlined.Add, null)
                    } }
                    if (customSkill) OutlinedTextField(draft.skills, { update(draft.copy(skills = it.take(1000))) }, label = { Text(stringResource(R.string.jobs_skills)) },
                        supportingText = { Text(stringResource(R.string.jobs_skills_hint)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    else TextButton(onClick = { customSkill = true }) { Text(stringResource(R.string.jobs_add_own_skill)) }
                    SupportingText(stringResource(R.string.jobs_skills_optional))
                }
                2 -> {
                    Text(stringResource(R.string.jobs_work_style), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.remoteOnly, { update(draft.copy(remoteOnly = false)) }, label = { Text(stringResource(R.string.jobs_any_arrangement)) }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(draft.remoteOnly, { update(draft.copy(remoteOnly = true)) }, label = { Text(stringResource(R.string.jobs_remote_only)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    Text(stringResource(R.string.jobs_country), style = MaterialTheme.typography.titleMedium)
                    JobSelectionRow(stringResource(R.string.jobs_any_location), draft.country.isBlank()) { update(draft.copy(country = "")) }
                    if (draft.country.isNotBlank()) JobSelectionRow(draft.country, true) { update(draft.copy(country = "")) }
                    contextSnapshot.candidates.filter { it.field.name == "COUNTRY" && it.value != draft.country }.take(2).forEach { candidate ->
                        JobSelectionRow(stringResource(R.string.jobs_suggested_country, candidate.value, candidate.sourceLabel), false) { update(draft.copy(country = candidate.value)) }
                    }
                    OutlinedTextField(countryQuery, { countryQuery = it }, singleLine = true, label = { Text(stringResource(R.string.jobs_find_country)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("jobs-country-search"), shape = RoundedCornerShape(16.dp))
                    if (countryQuery.isNotBlank()) {
                        val countries = remember(countryQuery) { Locale.getISOCountries().map { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) }
                            .filter { it.contains(countryQuery, true) }.sorted().take(8) }
                        countries.forEach { name -> JobSelectionRow(name, draft.country == name) { update(draft.copy(country = name)); countryQuery = "" } }
                        if (countries.isEmpty()) SupportingText(stringResource(R.string.jobs_country_no_results))
                    }
                    Text(stringResource(R.string.jobs_employment), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("", "full_time", "part_time", "contract").forEach { type -> FilterChip(draft.employmentType == type,
                            { update(draft.copy(employmentType = type)) }, label = { Text(employmentLabel(type)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                    SupportingText(stringResource(R.string.jobs_eligibility_unknown))
                }
                else -> {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SummaryChoice(R.string.jobs_roles, draft.roles.ifBlank { stringResource(R.string.jobs_all_roles_default) }) { go(0) }
                            SummaryChoice(R.string.jobs_skills, draft.skills.ifBlank { stringResource(R.string.jobs_no_skills_selected) }) { go(1) }
                            SummaryChoice(R.string.jobs_country, draft.country.ifBlank { stringResource(R.string.jobs_any_location) }) { go(2) }
                            Text((if (draft.remoteOnly) stringResource(R.string.jobs_remote_only) else stringResource(R.string.jobs_any_arrangement)) + " · " + employmentLabel(draft.employmentType), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.jobs_ready_heading), style = MaterialTheme.typography.titleLarge)
                    SupportingText(stringResource(R.string.jobs_ready_body))
                    if (jobs.isNotEmpty()) Text(stringResource(R.string.jobs_preview_count, jobs.count { JobsMatcher.matches(it, draft) }), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { onAdvanced(draft) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jobs-advanced-preferences")) { Text(stringResource(R.string.jobs_advanced_preferences)) }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(onClick = { if (step < 3) go(step + 1) else onSave(draft.copy(onboardingStep = 3)) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(if (step == 3) "jobs-preferences-save" else "jobs-setup-next")) {
                    Text(stringResource(if (step == 3) R.string.jobs_show_matches else R.string.jobs_next))
                }
            }
        }
    }
}

@Composable
private fun JobSelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().semantics { this.selected = selected; role = Role.RadioButton }) {
        Row(Modifier.padding(12.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SummaryChoice(label: Int, value: String, onEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onEdit) { Text(stringResource(R.string.jobs_edit_choice)) }
        }
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
