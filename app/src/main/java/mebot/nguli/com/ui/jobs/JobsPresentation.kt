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
        Box(Modifier.size(if (large) 40.dp else 32.dp), contentAlignment = Alignment.Center) {
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompanyMark(job.company)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(job.company, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                JobBookmark(application, saving, onSave)
            }
            Text(buildList {
                add(jobLocationLabel(job))
                if (job.remote) add(stringResource(R.string.jobs_remote))
                if (job.employmentType.isNotBlank()) add(employmentLabel(job.employmentType))
            }.distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (job.salary.isNotBlank()) Text(job.salary, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val reasons = JobsMatcher.reasons(job, preferences).filter { it.endsWith("_match") }.take(1)
            if (reasons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Text(reasons.map { reasonLabel(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.source, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("job-source-${job.id}"))
                if (job.postedAt > 0) Text(shortJobDate(job.postedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ArrowForward, stringResource(R.string.jobs_details), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun JobBookmark(application: JobApplication?, saving: Boolean, onSave: () -> Unit) {
    val saved = application?.bookmarked == true
    IconButton(onClick = onSave, enabled = !saving) {
        Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            stringResource(if (saved) R.string.jobs_unsave else R.string.jobs_save), tint = MaterialTheme.colorScheme.primary)
    }
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
    onBack: () -> Unit, onSave: () -> Unit, onOpen: () -> Unit, onAsk: () -> Unit, related: @Composable () -> Unit = {}) {
    var expanded by rememberSaveable(job.id) { mutableStateOf(false) }
    val description = remember(job.description) { Html.fromHtml(job.description, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
    val sections = remember(job.description) { publishedJobSections(job.description) }
    Column(Modifier.fillMaxSize().testTag("jobs-detail"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.jobs_back)) }
            Text(stringResource(R.string.jobs_details), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            JobBookmark(application, saving, onSave)
        }
        Column(Modifier.weight(1f).widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MeBotTokens.ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompanyMark(job.company, large = true)
                Text(job.company, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(job.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JobFact(jobLocationLabel(job))
                if (job.remote) JobFact(stringResource(R.string.jobs_remote))
                if (job.employmentType.isNotBlank()) JobFact(employmentLabel(job.employmentType))
            }
            Text(job.salary.ifBlank { stringResource(R.string.jobs_salary_unknown) }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.sourceStatus == "closed")
                Text(stringResource(R.string.jobs_listing_closed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("jobs-listing-closed"))
            Text(stringResource(when (job.sourceStatus) {
                "active" -> R.string.jobs_status_source_active
                "closed" -> R.string.jobs_status_source_closed
                else -> R.string.jobs_status_source_unknown
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.hasUnassignedJobicyRegion()) Text(stringResource(R.string.jobs_source_region_raw, job.location),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.statusCheckedAt > 0) Text(stringResource(R.string.jobs_status_checked, date(job.statusCheckedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("jobs-status-checked"))
            if (isSnapshot) SupportingText(stringResource(R.string.jobs_snapshot_warning))
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.jobs_requirements), style = MaterialTheme.typography.titleLarge)
                SupportingText(stringResource(R.string.jobs_source_named, job.source))
                if (sections.isEmpty()) Text(stringResource(R.string.jobs_no_description), style = MaterialTheme.typography.bodyLarge)
                (if (expanded) sections else sections.take(3)).forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (section.heading.isNotBlank()) Text(section.heading, style = MaterialTheme.typography.titleMedium)
                        if (section.body.isNotBlank()) Text(section.body, style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (expanded) Int.MAX_VALUE else if (sections.size == 1) 14 else 6, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (description.isNotBlank()) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (expanded) R.string.jobs_read_less else R.string.jobs_read_more))
                }
            }
            val reasons = JobsMatcher.reasons(job, preferences)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.jobs_match), style = MaterialTheme.typography.titleMedium)
                val positive = reasons.filter { it.endsWith("_match") }
                if (positive.isEmpty()) SupportingText(stringResource(R.string.jobs_explore_reason))
                positive.forEach { DetailCheck(reasonLabel(it), true) }
                Text(stringResource(R.string.jobs_verify_heading), style = MaterialTheme.typography.labelLarge)
                reasons.filterNot { it.endsWith("_match") }.forEach { DetailCheck(reasonLabel(it), false) }
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

/** Preserve publisher headings and wording. This is presentation, not AI-inferred requirements. */
private data class PublishedJobSection(val heading: String, val body: String)
private fun publishedJobSections(html: String): List<PublishedJobSection> {
    fun plain(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    val headings = Regex("<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html).toList()
    if (headings.isEmpty()) return plain(html).takeIf { it.isNotBlank() }?.let { listOf(PublishedJobSection("", it)) }.orEmpty()
    return buildList {
        plain(html.substring(0, headings.first().range.first)).takeIf { it.isNotBlank() }?.let { add(PublishedJobSection("", it)) }
        headings.forEachIndexed { index, heading ->
            val end = headings.getOrNull(index + 1)?.range?.first ?: html.length
            val body = plain(html.substring(heading.range.last + 1, end))
            val title = plain(heading.groupValues[1])
            if (title.isNotBlank() || body.isNotBlank()) add(PublishedJobSection(title, body))
        }
    }
}

/** Jobicy's Anywhere category is unassigned geography, not worldwide eligibility. */
internal fun JobPosting.hasUnassignedJobicyRegion(): Boolean =
    source.trim().equals("Jobicy", ignoreCase = true) && location.trim().equals("Anywhere", ignoreCase = true)

@Composable
private fun jobLocationLabel(job: JobPosting): String = when {
    job.hasUnassignedJobicyRegion() -> stringResource(R.string.jobs_jobicy_region_unassigned)
    job.location.isBlank() -> stringResource(R.string.jobs_location_unknown)
    else -> job.location
}
