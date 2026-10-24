package app.mynguli.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mynguli.R
import app.mynguli.MyNguliViewModel
import app.mynguli.core.*
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun FocusScreen(state: AppState, vm: MyNguliViewModel, suggestedTitle: String? = null, suggestedMinutes: Int? = null) {
    val session = state.focus
    val todayStep = state.visits.firstOrNull { it.date == LocalDate.now().toString() }?.nextStep.orEmpty()
    val defaultTitle = stringResource(R.string.mb_focus_default)
    var title by rememberSaveable(suggestedTitle) { mutableStateOf(suggestedTitle ?: todayStep) }
    var minutes by rememberSaveable(suggestedMinutes) { mutableIntStateOf(suggestedMinutes ?: state.settings.defaultFocusMinutes) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(session?.id) { while (session != null) { now = System.currentTimeMillis(); delay(500) } }
    val remaining = session?.let { FocusLogic.remainingMillis(it, now) } ?: minutes * 60_000L
    val totalSeconds = (remaining + 999) / 1000
    val complete = session != null && remaining == 0L
    val paused = session?.pausedRemainingMillis != null
    ScreenContainer {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(stringResource(R.string.focus_tab))
            Spacer(Modifier.weight(1f))
            if (session != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                Text(stringResource(if (complete) R.string.mb_done else if (paused) R.string.focus_paused else R.string.mb_in_progress),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (session != null) Text(session.title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            TimerDial(totalSeconds, if (session == null) 0f else 1f - remaining.toFloat() / (session.durationMinutes * 60_000L), complete)
        }
        if (session == null) {
            AppCard {
                Text(stringResource(R.string.mb_duration), style = MaterialTheme.typography.titleSmall)
                DurationChoices(minutes) { minutes = it }
                OutlinedTextField(title, { if (it.length <= AppLimits.NEXT_STEP_CHARACTERS) title = it },
                    label = { Text(stringResource(R.string.mb_focus_task_optional)) }, placeholder = { Text(defaultTitle) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3, shape = RoundedCornerShape(16.dp))
            }
            PrimaryAction(stringResource(R.string.focus_start)) { vm.startFocus(title.ifBlank { defaultTitle }, minutes) }
        } else {
            if (complete) PrimaryAction(stringResource(R.string.save_session)) { vm.completeFocus() }
            else PrimaryAction(stringResource(if (paused) R.string.resume else R.string.pause)) {
                if (paused) vm.resumeFocus() else vm.pauseFocus()
            }
            TextButton(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.end_session), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.mb_focus_silent), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text(stringResource(R.string.focus_cancel_title)) },
        text = { Text(stringResource(R.string.mb_focus_end_body)) },
        confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endFocus() }) { Text(stringResource(R.string.end_session)) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TimerDial(seconds: Long, progress: Float, complete: Boolean) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.sizeIn(maxWidth = 284.dp, maxHeight = 284.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        val widthFactor = if (seconds / 60 >= 100) 3.7f else 3.1f
        val clockSize = minOf(52f, (maxWidth.value - 64f) / (widthFactor * LocalDensity.current.fontScale)).coerceAtLeast(20f).sp
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = 10.dp.toPx()
            drawCircle(colors.primaryContainer, radius = size.minDimension * .5f - inset, style = Stroke(stroke))
            drawCircle(colors.surface, radius = size.minDimension * .5f - inset - 15.dp.toPx())
            if (progress > 0) drawArc(colors.primary, -90f, progress.coerceIn(0f, 1f) * 360f, false,
                Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke, cap = StrokeCap.Round))
            else drawCircle(colors.primary, radius = 5.dp.toPx(), center = Offset(size.width * .5f, inset))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.Timer, null, Modifier.size(28.dp), tint = colors.primary)
            Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayMedium.copy(fontSize = clockSize, lineHeight = clockSize * 1.1f, fontFeatureSettings = "tnum"))
            Text(stringResource(if (complete) R.string.mb_time_up else R.string.mb_time_remaining),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
