package mebot.nguli.com.ui.jobs

import android.text.Html
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.ui.*

@Composable
internal fun CompanyMark(company: String, large: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Box(Modifier.size(if (large) 56.dp else 42.dp), contentAlignment = Alignment.Center) {
            Text(company.trim().take(1).uppercase().ifBlank { "·" }, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JobCard(job: JobPosting, preferences: JobPreferences, application: JobApplication?, onSelect: () -> Unit, onSave: () -> Unit, saving: Boolean = false) {
    Surface(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag("job-card-${job.id}"),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompanyMark(job.company)
                Text(job.company, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                JobBookmark(application, saving, onSave)
            }
            Text(job.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                JobFact(job.location.ifBlank { stringResource(R.string.jobs_location_unknown) })
                if (job.remote) JobFact(stringResource(R.string.jobs_remote))
                if (job.employmentType.isNotBlank()) JobFact(employmentLabel(job.employmentType))
            }
            if (job.salary.isNotBlank()) Text(job.salary, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val reasons = JobsMatcher.reasons(job, preferences).filter { it.endsWith("_match") }.take(2)
            if (reasons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Text(reasons.map { reasonLabel(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (application != null) statusLabel(application.status) else job.source, style = MaterialTheme.typography.labelMedium,
                    color = if (application != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (job.postedAt > 0) Text(shortJobDate(job.postedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ArrowForward, stringResource(R.string.jobs_details), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun JobBookmark(application: JobApplication?, saving: Boolean, onSave: () -> Unit) {
    if (application == null || (application.status == "saved" && application.notes.isBlank() && application.draft.isBlank())) {
        IconButton(onClick = onSave, enabled = !saving) {
            Icon(if (application == null) Icons.Outlined.BookmarkBorder else Icons.Outlined.Bookmark,
                stringResource(if (application == null) R.string.jobs_save else R.string.jobs_unsave), tint = MaterialTheme.colorScheme.primary)
        }
    } else Icon(Icons.Outlined.Bookmark, stringResource(R.string.jobs_saved), Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun JobFact(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JobDetails(job: JobPosting, preferences: JobPreferences, application: JobApplication?, saving: Boolean, isSnapshot: Boolean,
    onBack: () -> Unit, onSave: () -> Unit, onPrepare: () -> Unit, onOpen: () -> Unit, onAsk: () -> Unit, related: @Composable () -> Unit = {}) {
    var expanded by rememberSaveable(job.id) { mutableStateOf(false) }
    val description = remember(job.description) { Html.fromHtml(job.description, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
    Column(Modifier.fillMaxSize().testTag("jobs-detail"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.jobs_back)) }
            Text(stringResource(R.string.jobs_details), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            JobBookmark(application, saving, onSave)
        }
        Column(Modifier.weight(1f).widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MeBotTokens.ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CompanyMark(job.company, large = true)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(job.company, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(job.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                if (application != null) Text(statusLabel(application.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JobFact(job.location.ifBlank { stringResource(R.string.jobs_location_unknown) })
                if (job.remote) JobFact(stringResource(R.string.jobs_remote))
                if (job.employmentType.isNotBlank()) JobFact(employmentLabel(job.employmentType))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.jobs_salary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(job.salary.ifBlank { stringResource(R.string.jobs_not_disclosed) }, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            val reasons = JobsMatcher.reasons(job, preferences)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.jobs_match), style = MaterialTheme.typography.titleMedium)
                val positive = reasons.filter { it.endsWith("_match") }
                if (positive.isEmpty()) SupportingText(stringResource(R.string.jobs_explore_reason))
                positive.forEach { DetailCheck(reasonLabel(it), true) }
                Text(stringResource(R.string.jobs_verify_heading), style = MaterialTheme.typography.labelLarge)
                reasons.filterNot { it.endsWith("_match") }.forEach { DetailCheck(reasonLabel(it), false) }
            }
            if (isSnapshot) SupportingText(stringResource(R.string.jobs_snapshot_warning))
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.jobs_requirements), style = MaterialTheme.typography.titleLarge)
                Text(description.ifBlank { stringResource(R.string.jobs_no_description) }, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 14, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (expanded) R.string.jobs_read_less else R.string.jobs_read_more))
                }
            }
            if (job.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                job.tags.distinct().take(8).forEach { JobFact(it) }
            }
            OutlinedButton(onClick = onAsk, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.jobs_understand_role))
            }
            related()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.jobs_source_named, job.source), style = MaterialTheme.typography.labelLarge)
                if (job.postedAt > 0) SupportingText(stringResource(R.string.jobs_posted, date(job.postedAt)))
                if (job.fetchedAt > 0) SupportingText(stringResource(R.string.jobs_last_refresh, date(job.fetchedAt)))
                SupportingText(stringResource(R.string.jobs_source_note))
            }
        }
        Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = job.url.startsWith("https://"), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("jobs-apply-source")) {
                    Text(stringResource(R.string.jobs_apply_on_source, job.source), Modifier.weight(1f)); Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                }
                OutlinedButton(onClick = onPrepare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("jobs-prepare")) {
                    Text(stringResource(R.string.jobs_prepare))
                }
            }
        }
    }
}

@Composable
private fun DetailCheck(text: String, positive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        Icon(if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(18.dp),
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shortJobDate(timestamp: Long) = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(timestamp))
