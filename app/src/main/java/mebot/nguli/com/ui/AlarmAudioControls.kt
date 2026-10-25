package mebot.nguli.com.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mebot.nguli.com.R
import mebot.nguli.com.alarm.AlarmAudioAccess
import mebot.nguli.com.alarm.AlarmSpeech
import mebot.nguli.com.alarm.AlarmTonePlayback
import mebot.nguli.com.core.AlarmAudioSettings
import mebot.nguli.com.core.AlarmPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun alarmSoundLabel(plan: AlarmPlan): String = when (plan.soundId) {
    "pulse" -> stringResource(R.string.alarm_audio_pulse)
    "chime" -> stringResource(R.string.alarm_audio_chime)
    "bell" -> stringResource(R.string.alarm_audio_bell)
    "custom" -> plan.soundName.ifBlank { stringResource(R.string.alarm_audio_custom) }
    "system" -> plan.soundName.ifBlank { stringResource(R.string.alarm_audio_phone_default) }
    else -> stringResource(R.string.alarm_audio_classic)
}

/** Editors own the plan and URI draft lease. This panel owns only its bounded preview. */
@Composable
internal fun AlarmAudioControls(
    plan: AlarmPlan,
    language: String,
    onChange: (AlarmPlan) -> Unit,
    canPreview: () -> Boolean = { true },
    stopPreviewRequest: Int = 0,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentPlan by rememberUpdatedState(plan)
    val currentChange by rememberUpdatedState(onChange)
    val currentCanPreview by rememberUpdatedState(canPreview)
    val speechLabel = stringResource(R.string.alarm_audio_speech)
    val defaultMessage = stringResource(R.string.alarm_audio_default_message)
    var selectionVersion by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<Int?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val tone = remember(context) { AlarmTonePlayback(context,
        onFallback = { status = R.string.alarm_audio_fallback },
        onUnavailable = { status = R.string.alarm_audio_no_sound }) }
    val speech = remember(context, tone) { AlarmSpeech(context,
        onSpeaking = { tone.setGain(if (it) .25f else 1f) },
        onUnavailable = { status = R.string.alarm_audio_voice_unavailable }) }
    fun stopPreview() {
        previewJob?.cancel(); previewJob = null
        previewing = false
        speech.stop(); tone.stop()
    }
    DisposableEffect(owner, tone, speech) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopPreview()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stopPreview(); speech.close(); tone.close()
        }
    }
    LaunchedEffect(stopPreviewRequest) { stopPreview() }
    fun change(value: AlarmPlan) {
        selectionVersion++
        stopPreview(); status = null
        currentChange(value)
    }
    fun picked(uri: Uri, flags: Int, custom: Boolean) {
        if (!AlarmAudioSettings.isContentUri(uri.toString())) {
            status = R.string.alarm_audio_pick_failed
            return
        }
        val version = ++selectionVersion
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                if (custom) AlarmAudioAccess.take(context, uri, flags)
                else {
                    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
                        AlarmAudioAccess.take(context, uri, flags)
                    true
                }
            }
            if (version != selectionVersion) return@launch
            if (!accepted) { status = R.string.alarm_audio_pick_failed; return@launch }
            stopPreview()
            // Name lookup is optional and may involve a document provider. Selection
            // is usable immediately; a stale provider reply cannot overwrite a new sound.
            currentChange(currentPlan.copy(soundId = if (custom) "custom" else "system",
                soundUri = uri.toString(), soundName = ""))
            status = null
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull().orEmpty().filterNot(Char::isISOControl).take(160)
            }
            if (version == selectionVersion && name.isNotBlank())
                currentChange(currentPlan.copy(soundId = if (custom) "custom" else "system",
                    soundUri = uri.toString(), soundName = name))
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            data.data?.let { picked(it, data.flags, custom = true) }
        }
    }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            val uri = if (Build.VERSION.SDK_INT >= 33)
                data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) picked(uri, data.flags, custom = false)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("alarm-audio-controls")) {
        SectionLabel(stringResource(R.string.alarm_audio_title))
        val choices = listOf(
            Triple("pulse", R.string.alarm_audio_pulse, R.string.alarm_audio_pulse_note),
            Triple("chime", R.string.alarm_audio_chime, R.string.alarm_audio_chime_note),
            Triple("bell", R.string.alarm_audio_bell, R.string.alarm_audio_bell_note),
            Triple("classic", R.string.alarm_audio_classic, R.string.alarm_audio_classic_note),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.15f) 2 else 1
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (id, title, description) ->
                            val selected = plan.soundId == id
                            Surface(shape = RoundedCornerShape(14.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.weight(1f).selectable(selected, role = Role.RadioButton,
                                    onClick = { change(currentPlan.copy(soundId = id, soundUri = "", soundName = "")) })
                                    .testTag("alarm-sound-$id")) {
                                Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                        null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                                        Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (plan.soundId in setOf("system", "custom")) {
            Text(stringResource(R.string.alarm_audio_selected, alarmSoundLabel(plan)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("alarm-audio-selected"))
        }
        OutlinedButton(onClick = {
            stopPreview(); status = null
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    if (plan.soundId == "system" && AlarmAudioSettings.isContentUri(plan.soundUri)) Uri.parse(plan.soundUri)
                    else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            runCatching { phonePicker.launch(intent) }.onFailure { status = R.string.alarm_audio_picker_unavailable }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm-sound-phone")) {
            Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.alarm_audio_phone))
        }
        OutlinedButton(onClick = {
            stopPreview(); status = null
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            runCatching { filePicker.launch(intent) }.onFailure { status = R.string.alarm_audio_picker_unavailable }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm-sound-file")) {
            Icon(Icons.Outlined.AudioFile, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.alarm_audio_file))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(speechLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(plan.speechEnabled, { change(currentPlan.copy(speechEnabled = it)) },
                modifier = Modifier.testTag("alarm-speech-enabled").semantics { contentDescription = speechLabel })
        }
        if (plan.speechEnabled) {
            OutlinedTextField(plan.speechMessage, {
                if (it.length <= AlarmAudioSettings.MAX_MESSAGE && it.none { c -> c.isISOControl() && c != '\n' })
                    change(currentPlan.copy(speechMessage = it))
            }, label = { Text(stringResource(R.string.alarm_audio_message)) },
                placeholder = { Text(stringResource(R.string.alarm_audio_default_message)) },
                supportingText = { Text(stringResource(R.string.alarm_audio_message_hint)) },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("alarm-speech-message"))
            Text(stringResource(R.string.alarm_audio_speech_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = {
            if (previewing) stopPreview()
            else if (!canPreview()) status = R.string.alarm_audio_active
            else {
                status = null; previewing = true
                val preview = currentPlan
                tone.setGain(1f); tone.play(preview)
                previewJob = scope.launch {
                    repeat(48) { tick ->
                        delay(250)
                        if (!currentCanPreview()) {
                            stopPreview(); status = R.string.alarm_audio_active
                            return@launch
                        }
                        if (tick == 3 && preview.speechEnabled)
                            speech.speak(preview.speechMessage.trim().ifBlank { defaultMessage }, language)
                    }
                    stopPreview()
                }
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("alarm-sound-preview")) {
            Icon(if (previewing) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (previewing) R.string.alarm_audio_stop else R.string.alarm_audio_preview))
        }
        Text(stringResource(R.string.alarm_audio_preview_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("alarm-audio-status")) }
        HorizontalDivider()
    }
}
