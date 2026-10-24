package app.mynguli.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.core.AppState
import app.mynguli.core.DailyVisit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HistoryScreen(state: AppState) {
    var tab by rememberSaveable { mutableStateOf("visits") }
    var selectedVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleVisits by rememberSaveable { mutableIntStateOf(20) }
    var visibleFocusRecords by rememberSaveable { mutableIntStateOf(20) }
    val visits = remember(state.visits) { state.visits.filter { it.completedAtMillis != null }.sortedByDescending { it.date } }
    val records = remember(state.focusHistory) { state.focusHistory.sortedByDescending { it.completedAtMillis } }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val feature = MeBotPalette.current
    ScreenContainer {
        ScreenTitle(stringResource(R.string.history))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(visits.size.toString(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.mb_check_ins), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Surface(color = feature.violetSurface, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(records.sumOf { it.minutes }.toString(), style = MaterialTheme.typography.headlineLarge, color = feature.onViolet)
                    Text(stringResource(R.string.mb_focus_minutes), style = MaterialTheme.typography.labelMedium, color = feature.onViolet)
                }
            }
        }
        ChoiceRow(listOf("visits" to stringResource(R.string.mb_check_ins), "focus" to stringResource(R.string.focus_tab)), tab) { tab = it }
        if (tab == "visits") {
            if (visits.isEmpty()) EmptyHistory(Icons.Outlined.WbSunny, stringResource(R.string.mb_no_check_ins))
            visits.take(visibleVisits).forEach { visit ->
                Surface(onClick = { selectedVisitId = visit.id }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = feature.blueSurface, shape = RoundedCornerShape(16.dp)) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                if (visit.arrival.isNotBlank()) MoodFace(visit.arrival, feature.onBlue, Modifier.size(27.dp))
                                else Icon(Icons.Outlined.CheckCircle, null, Modifier.size(23.dp), tint = feature.onBlue)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(formatVisitDate(visit.date, locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(visit.nextStep, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.entry_details), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (visits.size > visibleVisits) SecondaryAction(stringResource(R.string.secondary_show_more)) { visibleVisits += 20 }
        } else {
            if (records.isEmpty()) EmptyHistory(Icons.Outlined.Timer, stringResource(R.string.mb_no_focus_records))
            records.take(visibleFocusRecords).forEach { record ->
                AppCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FeatureIcon(Icons.Outlined.Timer, feature.onViolet, feature.violetSurface)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatFocusDate(record.completedAtMillis, locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.minutes_value, record.minutes), style = MaterialTheme.typography.labelMedium, color = feature.onViolet)
                    }
                }
            }
            if (records.size > visibleFocusRecords) SecondaryAction(stringResource(R.string.secondary_show_more)) { visibleFocusRecords += 20 }
        }
    }
    visits.find { it.id == selectedVisitId }?.let { VisitDetailsDialog(it, locale) { selectedVisitId = null } }
}

@Composable
private fun EmptyHistory(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureIcon(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 60.dp)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun VisitDetailsDialog(visit: DailyVisit, locale: Locale, onClose: () -> Unit) {
    val bodyHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .6f).toDp() }
    AlertDialog(onDismissRequest = onClose, title = { Text(formatVisitDate(visit.date, locale)) },
        text = {
            Column(Modifier.heightIn(max = bodyHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (visit.arrival.isNotBlank()) Text(stringResource(R.string.arrival_history, historyArrivalLabel(visit.arrival)))
                if (visit.departure.isNotBlank()) Text(stringResource(R.string.departure_history, historyDepartureLabel(visit.departure)))
                Text(visit.nextStep, style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.minutes_value, visit.focusMinutes))
                if (visit.note.isNotBlank()) {
                    HorizontalDivider()
                    SectionLabel(stringResource(R.string.your_note))
                    Text(visit.note)
                }
                if (visit.outcomes.isNotEmpty()) {
                    HorizontalDivider()
                    visit.outcomes.forEach { outcome ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (outcome.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                stringResource(if (outcome.done) R.string.secondary_outcome_done else R.string.secondary_outcome_pending), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(outcome.text)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun historyArrivalLabel(value: String): String = when (value) {
    "scattered" -> stringResource(R.string.arrival_scattered)
    "low", "low_energy" -> stringResource(R.string.arrival_low)
    "tense" -> stringResource(R.string.arrival_tense)
    "steady" -> stringResource(R.string.arrival_steady)
    "ready" -> stringResource(R.string.arrival_ready)
    else -> value
}

@Composable
private fun historyDepartureLabel(value: String): String = when (value) {
    "clearer" -> stringResource(R.string.departure_clearer)
    "calmer" -> stringResource(R.string.departure_calmer)
    "ready" -> stringResource(R.string.departure_ready)
    "same" -> stringResource(R.string.departure_same)
    "time", "need_time" -> stringResource(R.string.departure_need_time)
    else -> value
}

private fun formatVisitDate(value: String, locale: Locale): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}.getOrDefault(value)

private fun formatFocusDate(value: Long, locale: Locale): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
