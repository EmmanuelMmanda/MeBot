package app.mynguli.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mynguli.R
import app.mynguli.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: AppState,
    date: LocalDate,
    onVisit: () -> Unit,
    onFocus: (String?, Int?) -> Unit,
    onTools: () -> Unit,
    onOutcome: (String, String) -> Unit,
    onAlarms: () -> Unit = onTools,
    onHistory: () -> Unit = onTools,
    onSettings: () -> Unit = onTools,
) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(60_000) } }
    val greeting = stringResource(when (now.hour) {
        in 0..11 -> R.string.good_morning; in 12..17 -> R.string.good_afternoon; else -> R.string.good_evening
    })
    val today = state.visits.firstOrNull { it.date == date.toString() }
    val completed = today?.completedAtMillis != null
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val alarm = if ("alarms" in state.settings.enabledModules) state.alarms.filter { it.enabled }
        .minByOrNull { nextAlarmOccurrence(it, now).toInstant() } else null
    val feature = MeBotPalette.current
    val activeFocus = state.focus
    ScreenContainer {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                MeBotMark(Modifier.size(29.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(greeting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.profile.name.ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSettings, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title), Modifier.size(22.dp))
            }
        }
        if ("alarms" in state.settings.enabledModules) {
            WaveCard(contentPadding = PaddingValues(20.dp), verticalSpacing = 10.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (alarm == null) R.string.mb_wake_up else R.string.mb_next_alarm), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Outlined.Alarm, null, Modifier.size(24.dp))
                }
                if (alarm == null) {
                    Text(stringResource(R.string.mb_alarm_empty_title), style = MaterialTheme.typography.headlineLarge)
                    Surface(onClick = onAlarms, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .16f),
                        contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(50)) {
                        Row(Modifier.heightIn(min = MeBotTokens.TouchTarget).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.mb_add_alarm), style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
                        }
                    }
                } else {
                    val clock = LocalTime.of(alarm.hour, alarm.minute)
                    val use24 = DateFormat.is24HourFormat(LocalContext.current)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val scale = LocalDensity.current.fontScale
                        val periodWidth = if (use24) 0.dp else (36f * scale).dp
                        val clockSize = minOf(64f, (maxWidth - periodWidth).value / (3.1f * scale)).coerceAtLeast(24f).sp
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(clock.format(DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm", locale)),
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = clockSize, lineHeight = clockSize * 1.1f, fontFeatureSettings = "tnum"), maxLines = 1)
                            if (!use24) Text(clock.format(DateTimeFormatter.ofPattern("a", locale)),
                                modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val occurrence = nextAlarmOccurrence(alarm, now)
                    val day = when (occurrence.toLocalDate()) {
                        date -> stringResource(R.string.today)
                        date.plusDays(1) -> stringResource(R.string.mb_tomorrow)
                        else -> occurrence.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(day, style = MaterialTheme.typography.bodyMedium)
                            if (alarm.label.isNotBlank()) Text(alarm.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(onClick = onAlarms, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .16f),
                            contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(50)) {
                            Row(Modifier.heightIn(min = MeBotTokens.TouchTarget).padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.alarms), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactAction(stringResource(R.string.mb_check_in), Icons.Outlined.WbSunny, onVisit, Modifier.weight(1f))
                if ("focus" in state.settings.enabledModules) CompactAction(stringResource(R.string.focus_tab), Icons.Outlined.Timer,
                    { onFocus(null, null) }, Modifier.weight(1f))
                CompactAction(stringResource(R.string.tools), Icons.Outlined.GridView, onTools, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mb_your_day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (completed && today != null) {
            val singleOutcome = today.outcomes.singleOrNull()?.takeIf { it.text.trim() == today.nextStep.trim() }
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (singleOutcome != null) {
                        Row(Modifier.weight(1f).heightIn(min = MeBotTokens.TouchTarget)
                            .toggleable(value = singleOutcome.done, role = Role.Checkbox, onValueChange = { onOutcome(today.id, singleOutcome.id) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(singleOutcome.done, onCheckedChange = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.mb_todays_plan), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(today.nextStep, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else {
                        FeatureIcon(Icons.Outlined.Notes, feature.onBlue, feature.blueSurface)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.mb_todays_plan), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(today.nextStep, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    IconButton(onClick = onVisit) { Icon(Icons.Outlined.Edit, stringResource(R.string.mb_edit_plan), Modifier.size(19.dp)) }
                }
                if (singleOutcome == null) today.outcomes.forEach { outcome ->
                    Row(Modifier.fillMaxWidth().heightIn(min = MeBotTokens.TouchTarget)
                        .toggleable(value = outcome.done, role = Role.Checkbox, onValueChange = { onOutcome(today.id, outcome.id) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(outcome.done, onCheckedChange = null)
                        Text(outcome.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                if ("focus" in state.settings.enabledModules) PrimaryAction(stringResource(if (state.focus != null) R.string.resume_focus else R.string.mb_start_focus_minutes, today.focusMinutes)) {
                    onFocus(today.nextStep, today.focusMinutes)
                }
            }
        } else {
            Surface(onClick = onVisit, color = feature.blueSurface, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.mb_check_in), color = feature.onBlue, style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.mb_small_next_step), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.mb_check_in_detail), color = feature.onBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = feature.onBlue)
                    }
                }
            }
        }
        if (!completed && activeFocus != null && "focus" in state.settings.enabledModules) {
            Surface(onClick = { onFocus(null, null) }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FeatureIcon(Icons.Outlined.Timer, feature.onViolet, feature.violetSurface)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.resume_focus), style = MaterialTheme.typography.titleSmall)
                        Text(activeFocus.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if ("history" in state.settings.enabledModules) {
            SectionHeader(stringResource(R.string.mb_this_week), stringResource(R.string.mb_view_all), onHistory)
            ActivityStrip(state, date)
        }
        if (state.profile.intention.isNotBlank()) {
            AppCard {
                Text(stringResource(R.string.intention_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.profile.intention, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ActivityStrip(state: AppState, date: LocalDate) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val dates = (6L downTo 0L).map { date.minusDays(it) }
    val completed = state.visits.filter { it.completedAtMillis != null }.map { it.date }.toSet()
    val focusDates = state.focusHistory.map { Instant.ofEpochMilli(it.completedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.toSet()
    val active = dates.count { it.toString() in completed || it.toString() in focusDates }
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.mb_active_days, active), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.mb_last_seven), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            dates.forEach { day ->
                val recorded = day.toString() in completed || day.toString() in focusDates
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(
                        if (recorded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                        if (recorded) Icon(Icons.Outlined.Check, stringResource(R.string.mb_recorded), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        else Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
                    }
                    Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall,
                        color = if (day == date) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (day == date) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
