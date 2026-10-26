package mebot.nguli.com.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mebot.nguli.com.MeBotViewModel
import mebot.nguli.com.R
import mebot.nguli.com.alarm.AlarmActivity
import mebot.nguli.com.alarm.AlarmAudioAccess
import mebot.nguli.com.alarm.AlarmReadiness
import mebot.nguli.com.core.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val alarmPlanSaver = Saver<AlarmPlan, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<AlarmPlan>(it) },
)

@Composable
private fun weekdayLabels(): List<String> = listOf(
    R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
    R.string.day_fri, R.string.day_sat, R.string.day_sun,
).map { stringResource(it) }

@Composable
private fun alarmTime(plan: AlarmPlan): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val format = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    return LocalTime.of(plan.hour, plan.minute).format(DateTimeFormatter.ofPattern(format, locale))
}

@Composable
private fun repeatLabel(plan: AlarmPlan): String {
    val labels = weekdayLabels()
    return when {
        plan.days.isEmpty() -> stringResource(R.string.mebot_no_repeat)
        plan.days == setOf(1, 2, 3, 4, 5) -> stringResource(R.string.mebot_weekdays)
        plan.days.size == 7 -> stringResource(R.string.mebot_every_day)
        else -> plan.days.sorted().joinToString(" · ") { labels[it - 1] }
    }
}

@Composable
private fun modeLabel(plan: AlarmPlan): String = when (plan.effectiveDismissMode) {
    AlarmDismissMode.MATH -> pluralStringResource(R.plurals.mebot_math_questions, plan.mathQuestionCount, plan.mathQuestionCount)
    AlarmDismissMode.QR -> stringResource(R.string.mebot_dismiss_qr)
    AlarmDismissMode.STANDARD -> stringResource(R.string.mebot_dismiss_tap)
}

@Composable
private fun rememberAlarmReadiness(vm: MeBotViewModel): AlarmReadiness {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(revision) { vm.alarms.readiness() }
}

@Composable
fun AlarmsScreen(state: AppState, vm: MeBotViewModel, onEdit: (String?) -> Unit, onCheckpoint: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccess by rememberSaveable { mutableStateOf(false) }
    var pendingEnable by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = rememberAlarmReadiness(vm)
    val context = LocalContext.current
    val plans = state.alarms.sortedWith(compareBy<AlarmPlan> { !it.enabled }.thenBy {
        nextAlarmOccurrence(it, ZonedDateTime.now()).toInstant()
    })
    ScreenContainer {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.alarms), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = { onEdit(null) }, enabled = state.alarms.size < AppLimits.ALARMS,
                modifier = Modifier.size(48.dp).testTag("alarm-create"), shape = CircleShape) {
                Icon(Icons.Outlined.Add, stringResource(R.string.add_alarm))
            }
        }
        if (vm.alarms.session() != null) {
            FilledTonalButton(onClick = { context.startActivity(Intent(context, AlarmActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Alarm, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_active_alarm))
            }
        }
        if (plans.isEmpty()) {
            WaveCard {
                Icon(Icons.Outlined.Alarm, null, Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.mebot_empty_alarm), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.mebot_empty_alarm_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onEdit(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_alarm))
                }
            }
        }
        plans.forEachIndexed { index, plan ->
            AlarmListCard(plan, prominent = index == 0 && plan.enabled,
                onEdit = { onEdit(plan.id) }, onDelete = { deleting = plan.id }, onToggle = { enabled ->
                    if (enabled && !vm.alarms.readiness().exactAllowed) {
                        pendingEnable = plan.id; showAccess = true
                    } else vm.saveAlarm(plan.copy(enabled = enabled)) {}
                })
        }
        AlarmAccessCard(ready) { showAccess = true }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onCheckpoint) {
                Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mebot_checkpoint_short))
            }
        }
    }
    if (showAccess) AlarmAccessSheet(vm, onDismiss = { showAccess = false; pendingEnable = null },
        onContinue = pendingEnable?.let { id -> {
            state.alarms.find { it.id == id }?.let { plan -> vm.saveAlarm(plan.copy(enabled = true)) {
                showAccess = false; pendingEnable = null
            } }
        } })
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = { Text(stringResource(R.string.alarm_delete_body)) },
            confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { deleting = null; vm.deleteAlarm(id) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun AlarmListCard(plan: AlarmPlan, prominent: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clock = alarmTime(plan)
    val ink = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondary = if (prominent) ink.copy(alpha = .83f) else MaterialTheme.colorScheme.onSurfaceVariant
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.label.ifBlank { stringResource(R.string.mebot_morning_alarm) },
                    style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clock, style = MaterialTheme.typography.displaySmall, color = ink)
                Text(repeatLabel(plan), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            Switch(checked = plan.enabled, onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = clock },
                colors = if (prominent) SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.onPrimary, checkedBorderColor = Color.Transparent)
                else SwitchDefaults.colors())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = ink.copy(alpha = if (prominent) .14f else .045f), contentColor = ink, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(when (plan.effectiveDismissMode) {
                        AlarmDismissMode.MATH -> Icons.Outlined.Calculate
                        AlarmDismissMode.QR -> Icons.Outlined.QrCodeScanner
                        AlarmDismissMode.STANDARD -> Icons.Outlined.TouchApp
                    }, null, Modifier.size(16.dp))
                    Text(modeLabel(plan), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit_alarm), Modifier.size(20.dp), tint = ink) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.mebot_alarm_options), tint = ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
                    DropdownMenuItem(text = { Text(stringResource(R.string.mebot_remove_alarm)) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
    if (prominent) WaveCard(content = content) else AppCard(content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditScreen(state: AppState, vm: MeBotViewModel, id: String?, onSaved: () -> Unit, onCheckpoint: () -> Unit) {
    var plan by rememberSaveable(id, stateSaver = alarmPlanSaver) {
        mutableStateOf(state.alarms.find { it.id == id } ?: AlarmPlan(enabled = true,
            dismissMode = AlarmDismissMode.MATH, soundId = AlarmAudioSettings.DEFAULT_SOUND))
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showAccess by rememberSaveable { mutableStateOf(false) }
    var pendingAccessAction by rememberSaveable { mutableStateOf("review") }
    val audioDraftToken = rememberSaveable(id) { UUID.randomUUID().toString() }
    val audioDraft = remember(audioDraftToken) { AlarmAudioAccess.beginDraft(plan.soundUri, audioDraftToken) }
    DisposableEffect(audioDraft) { onDispose { audioDraft.close() } }
    var stopPreviewRequest by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val alarmEnabledDescription = stringResource(R.string.alarm_enabled)
    val days = weekdayLabels()
    val mode = plan.effectiveDismissMode
    // A saved plan can intentionally refer to a different checkpoint than the current default.
    val checkpointCode = plan.checkpointCode.ifBlank { state.settings.checkpointCode }
    val checkpointLabel = if (plan.checkpointCode.isNotBlank()) plan.checkpointLabel else state.settings.checkpointLabel
    val ready = rememberAlarmReadiness(vm)
    fun configured(enabled: Boolean = plan.enabled) = plan.copy(
        enabled = enabled,
        requireCheckpoint = mode == AlarmDismissMode.QR,
        checkpointCode = if (mode == AlarmDismissMode.QR) checkpointCode else "",
        checkpointLabel = if (mode == AlarmDismissMode.QR) checkpointLabel else "",
    )
    val checkpointReady = mode != AlarmDismissMode.QR || checkpointCode.isNotBlank()
    fun saved() { audioDraft.committed(); onSaved() }
    fun save() {
        stopPreviewRequest++
        if (plan.enabled && !vm.alarms.readiness().let { it.exactAllowed && it.notificationsAllowed && it.fullScreenAllowed }) {
            pendingAccessAction = "save"; showAccess = true
        } else vm.saveAlarm(configured(), ::saved)
    }
    ScreenContainer {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (id == null) R.string.add_alarm else R.string.edit_alarm),
                style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Switch(plan.enabled, { plan = plan.copy(enabled = it) }, modifier = Modifier.testTag("alarm-enabled")
                .semantics { contentDescription = alarmEnabledDescription })
        }
        WaveCard(Modifier.clickable(role = Role.Button, onClick = {
            TimePickerDialog(context, { _, hour, minute -> plan = plan.copy(hour = hour, minute = minute) },
                plan.hour, plan.minute, DateFormat.is24HourFormat(context)).show()
        }).testTag("alarm-time")) {
            Text(stringResource(R.string.mebot_time_title), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(alarmTime(plan), style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Schedule, null, Modifier.size(28.dp))
            }
            Text(stringResource(R.string.mebot_alarm_edit_hint), style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.repeat_days))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dayWidth = ((maxWidth - 24.dp) / 7).coerceAtLeast(MeBotTokens.TouchTarget)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { index, day ->
                    val selected = index + 1 in plan.days
                    Surface(onClick = { plan = plan.copy(days = if (selected) plan.days - (index + 1) else plan.days + (index + 1)) },
                        modifier = Modifier.width(dayWidth).heightIn(min = MeBotTokens.TouchTarget).semantics { this.selected = selected },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(14.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
                }
            }
            if (plan.days.isEmpty()) Text(stringResource(R.string.mebot_no_repeat), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppCard {
            SectionLabel(stringResource(R.string.mebot_dismiss_with))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(AlarmDismissMode.MATH, R.string.mebot_dismiss_math, Icons.Outlined.Calculate),
                    Triple(AlarmDismissMode.QR, R.string.mebot_dismiss_qr, Icons.Outlined.QrCodeScanner),
                    Triple(AlarmDismissMode.STANDARD, R.string.mebot_dismiss_tap, Icons.Outlined.TouchApp),
                ).forEach { (value, label, icon) ->
                    ModeChoice(stringResource(label), icon, mode == value, Modifier.weight(1f).testTag("alarm-mode-${value.name.lowercase()}")) {
                        plan = plan.copy(dismissMode = value, requireCheckpoint = value == AlarmDismissMode.QR)
                    }
                }
            }
            when (mode) {
                AlarmDismissMode.MATH -> {
                    Text(stringResource(R.string.mebot_math_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceRow(listOf(
                        MathDifficulty.EASY.name to stringResource(R.string.mebot_easy),
                        MathDifficulty.MEDIUM.name to stringResource(R.string.mebot_medium),
                        MathDifficulty.HARD.name to stringResource(R.string.mebot_hard),
                    ), plan.mathDifficulty.name) { plan = plan.copy(mathDifficulty = MathDifficulty.valueOf(it)) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mebot_question_count), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { plan = plan.copy(mathQuestionCount = plan.mathQuestionCount - 1) },
                            enabled = plan.mathQuestionCount > 1, modifier = Modifier.testTag("alarm-fewer-questions")) {
                            Icon(Icons.Outlined.Remove, stringResource(R.string.mebot_fewer_questions))
                        }
                        Text(plan.mathQuestionCount.toString(), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { plan = plan.copy(mathQuestionCount = plan.mathQuestionCount + 1) },
                            enabled = plan.mathQuestionCount < 5, modifier = Modifier.testTag("alarm-more-questions")) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.mebot_more_questions))
                        }
                    }
                }
                AlarmDismissMode.QR -> {
                    SupportingText(stringResource(R.string.mebot_qr_hint))
                    if (checkpointCode.isBlank()) SecondaryAction(stringResource(R.string.mebot_checkpoint_generate), onCheckpoint)
                    else {
                        Text(checkpointLabel.ifBlank { stringResource(R.string.mebot_checkpoint_short) }, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = onCheckpoint) {
                            Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.mebot_checkpoint_manage))
                        }
                        if (state.settings.checkpointCode.isNotBlank() && state.settings.checkpointCode != checkpointCode) {
                            TextButton(onClick = { plan = plan.copy(checkpointCode = state.settings.checkpointCode,
                                checkpointLabel = state.settings.checkpointLabel) }) {
                                Text(stringResource(R.string.mebot_use_checkpoint, state.settings.checkpointLabel))
                            }
                        }
                    }
                    SectionLabel(stringResource(R.string.mebot_qr_followup))
                    ChoiceRow(listOf(0, 1, 3, 5).map { it.toString() to if (it == 0) stringResource(R.string.mebot_no_followup) else stringResource(R.string.minutes_value, it) }, plan.followUpMinutes.toString()) {
                        plan = plan.copy(followUpMinutes = it.toInt())
                    }
                }
                AlarmDismissMode.STANDARD -> SupportingText(stringResource(R.string.mebot_tap_hint))
            }
        }
        Surface(shape = RoundedCornerShape(MeBotTokens.CardRadius), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable(onClick = { advanced = !advanced }).testTag("alarm-advanced")
                    .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    FeatureIcon(Icons.Outlined.VolumeUp, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.mebot_more_alarm), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.alarm_audio_summary, alarmSoundLabel(plan), plan.volumePercent, pluralStringResource(R.plurals.mebot_snoozes, plan.maxSnoozes, plan.maxSnoozes)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                AnimatedVisibility(advanced) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AlarmAudioControls(plan, state.settings.language, { updated ->
                            audioDraft.replace(updated.soundUri); plan = updated
                        }, canPreview = { vm.alarms.session()?.audible != true }, stopPreviewRequest = stopPreviewRequest)
                        OutlinedTextField(plan.label, { if (it.length <= 100) plan = plan.copy(label = it) },
                            label = { Text(stringResource(R.string.mebot_alarm_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.alarm_volume)); Text(stringResource(R.string.percent_value, plan.volumePercent))
                        }
                        Slider(plan.volumePercent.toFloat(), { plan = plan.copy(volumePercent = it.toInt()) }, valueRange = 10f..100f, steps = 8)
                        SectionLabel(stringResource(R.string.mebot_ramp))
                        ChoiceRow(listOf(0, 10, 20, 30).map { it.toString() to stringResource(R.string.mebot_seconds, it) }, plan.rampSeconds.toString()) { plan = plan.copy(rampSeconds = it.toInt()) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.vibration), Modifier.weight(1f)); Switch(plan.vibrate, { plan = plan.copy(vibrate = it) })
                        }
                        SectionLabel(stringResource(R.string.snooze_count))
                        ChoiceRow((0..3).map { it.toString() to it.toString() }, plan.maxSnoozes.toString()) { plan = plan.copy(maxSnoozes = it.toInt()) }
                        if (plan.maxSnoozes > 0) {
                            SectionLabel(stringResource(R.string.snooze_duration))
                            ChoiceRow(listOf(1, 3, 5, 10).map { it.toString() to stringResource(R.string.minutes_value, it) }, plan.snoozeMinutes.toString()) { plan = plan.copy(snoozeMinutes = it.toInt()) }
                        }
                    }
                }
            }
        }
        Button(onClick = ::save, enabled = checkpointReady, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("alarm-save"),
            shape = RoundedCornerShape(MeBotTokens.ControlRadius)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.mebot_save_alarm))
        }
        TextButton(onClick = {
            stopPreviewRequest++
            if (vm.alarms.readiness().exactAllowed) vm.testAlarm(configured())
            else { pendingAccessAction = "test"; showAccess = true }
        }, enabled = checkpointReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.test_alarm))
        }
        AlarmAccessCard(ready) { stopPreviewRequest++; pendingAccessAction = "review"; showAccess = true }
    }
    if (showAccess) AlarmAccessSheet(vm, onDismiss = { showAccess = false },
        onContinue = when (pendingAccessAction) {
            "test" -> ({ vm.testAlarm(configured()); showAccess = false })
            "save" -> ({ vm.saveAlarm(configured(), ::saved) })
            else -> null
        },
        onSaveOff = if (pendingAccessAction == "save") ({ vm.saveAlarm(configured(enabled = false), ::saved) }) else null,
        continueLabel = if (pendingAccessAction == "test") R.string.test_alarm else R.string.mebot_save_alarm)

}

@Composable
private fun ModeChoice(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 80.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(24.dp)); Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AlarmAccessCard(ready: AlarmReadiness, onOpen: () -> Unit) {
    val complete = ready.exactAllowed && ready.notificationsAllowed && ready.fullScreenAllowed
    val feature = MeBotPalette.current
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (complete) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Tune, null,
                tint = if (complete) feature.onMint else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(stringResource(if (complete) R.string.mebot_alarm_access_ready else R.string.mebot_alarm_access_needed),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.mebot_review), Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmAccessSheet(
    vm: MeBotViewModel,
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)? = null,
    onSaveOff: (() -> Unit)? = null,
    continueLabel: Int = R.string.mebot_save_alarm,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var notificationAttempted by rememberSaveable { mutableStateOf(false) }
    var tips by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAttempted = true; revision++
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val ready = remember(revision) { vm.alarms.readiness() }
    fun setting(action: String) {
        val intent = if (action == AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            Intent(action).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(action, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.mebot_alarm_access), style = MaterialTheme.typography.headlineSmall)
            SupportingText(stringResource(R.string.mebot_access_body))
            PermissionRow(stringResource(R.string.mebot_exact), Icons.Outlined.Schedule, ready.exactAllowed) {
                if (Build.VERSION.SDK_INT >= 31) setting(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
            PermissionRow(stringResource(R.string.mebot_notifications), Icons.Outlined.NotificationsNone, ready.notificationsAllowed) {
                if (Build.VERSION.SDK_INT >= 33 && !notificationAttempted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else setting(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
            PermissionRow(stringResource(R.string.mebot_lock_screen), Icons.Outlined.LockOpen, ready.fullScreenAllowed) {
                if (Build.VERSION.SDK_INT >= 34) setting(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
            if (onContinue != null) PrimaryAction(stringResource(continueLabel), enabled = ready.exactAllowed, onClick = onContinue)
            if (onSaveOff != null) TextButton(onClick = onSaveOff, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mebot_save_off))
            }
            if (onContinue == null) PrimaryAction(stringResource(R.string.done), onClick = onDismiss)
            TextButton(onClick = { tips = !tips }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mebot_device_tips)); Spacer(Modifier.width(8.dp))
                Icon(if (tips) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            if (tips) {
                SupportingText(stringResource(R.string.mebot_device_tips_body))
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        .onFailure { setting(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                }) { Text(stringResource(R.string.battery_setting)) }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, icon: ImageVector, allowed: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (allowed) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.permission_ready), tint = MeBotPalette.current.onMint, modifier = Modifier.size(22.dp))
        else TextButton(onClick = onAllow) { Text(stringResource(R.string.mebot_allow)) }
    }
}

@Composable
fun CheckpointScreen(state: AppState, vm: MeBotViewModel) {
    var label by rememberSaveable(state.settings.checkpointLabel) { mutableStateOf(state.settings.checkpointLabel) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(state.settings.checkpointCode) { state.settings.checkpointCode.takeIf { it.isNotBlank() }?.let(::checkpointBitmap) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bitmap != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } ?: error("Output unavailable") }
                vm.showMessage(R.string.checkpoint_saved)
            } catch (_: Exception) { vm.showMessage(R.string.file_error) }
        }
    }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.mebot_checkpoint_short), stringResource(R.string.mebot_checkpoint_short_body))
        AppCard {
            OutlinedTextField(label, { if (it.length <= 80) label = it }, label = { Text(stringResource(R.string.checkpoint_name)) },
                placeholder = { Text(stringResource(R.string.checkpoint_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            PrimaryAction(stringResource(if (bitmap == null) R.string.mebot_checkpoint_generate else R.string.save), enabled = label.isNotBlank()) { vm.createCheckpoint(label) }
        }
        if (bitmap != null) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.checkpoint_current, state.settings.checkpointLabel),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp))
            }
            PrimaryAction(stringResource(R.string.mebot_checkpoint_export)) { saveImage.launch("mebot-checkpoint.png") }
        }
    }
}

internal fun checkpointBitmap(code: String): Bitmap {
    val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 768, 768,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
