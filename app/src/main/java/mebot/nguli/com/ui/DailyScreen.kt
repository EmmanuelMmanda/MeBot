package mebot.nguli.com.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val dailyVisitSaver = Saver<DailyVisit, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<DailyVisit>(it) }.getOrNull() },
)

@Composable
fun arrivalOptions(): List<Pair<String, String>> = listOf(
    "low" to stringResource(R.string.arrival_low), "tense" to stringResource(R.string.arrival_tense),
    "scattered" to stringResource(R.string.arrival_scattered), "steady" to stringResource(R.string.arrival_steady),
    "ready" to stringResource(R.string.arrival_ready),
)

@Composable
fun departureOptions(): List<Pair<String, String>> = listOf(
    "clearer" to stringResource(R.string.departure_clearer), "calmer" to stringResource(R.string.departure_calmer),
    "ready" to stringResource(R.string.departure_ready), "same" to stringResource(R.string.departure_same),
    "time" to stringResource(R.string.departure_need_time),
)

@Composable
fun DurationChoices(selected: Int, onSelect: (Int) -> Unit) {
    val values = (listOf(5, 10, 15, 25, 45) + selected).distinct().sorted()
    ChoiceRow(values.map { it.toString() to stringResource(R.string.minutes_value, it) }, selected.toString()) { onSelect(it.toInt()) }
}

/** A single short visit. Mood and notes remain optional; accepting a suggestion is explicit. */
@Composable
fun DailyScreen(initial: DailyVisit, onSave: (DailyVisit) -> Unit, onComplete: (DailyVisit) -> Unit) {
    var draft by rememberSaveable(initial.id, stateSaver = dailyVisitSaver) { mutableStateOf(initial) }
    var editing by rememberSaveable(initial.id) { mutableStateOf(false) }
    var showNote by rememberSaveable(initial.id) { mutableStateOf(initial.note.isNotBlank()) }
    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val clearAction = stringResource(R.string.mb_suggestion_clear)
    val resetAction = stringResource(R.string.mb_suggestion_reset)
    val focusAction = stringResource(R.string.mb_suggestion_focus)
    val suggestedAction = when (draft.arrival) {
        "scattered" -> clearAction
        "low", "tense" -> resetAction
        else -> focusAction
    }
    val action = draft.nextStep.ifBlank { suggestedAction }
    val feature = MeBotPalette.current
    fun update(next: DailyVisit) {
        draft = next
        if (initial.completedAtMillis == null) onSave(next)
    }
    ScreenContainer(Modifier.testTag("daily-screen")) {
        ScreenTitle(stringResource(R.string.mb_check_in_title))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.mb_how_are_you), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.mb_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoodChoices(draft.arrival) { mood ->
                val nextMood = if (draft.arrival == mood) "" else mood
                val nextMinutes = if (draft.nextStep.isBlank()) {
                    if (nextMood in listOf("low", "tense", "scattered")) 5 else initial.focusMinutes
                } else draft.focusMinutes
                update(draft.copy(arrival = nextMood, focusMinutes = nextMinutes))
            }
        }
        AppCard(color = feature.blueSurface) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FeatureIcon(Icons.Outlined.NorthEast, feature.onBlue, MaterialTheme.colorScheme.surface.copy(alpha = .65f))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.mb_suggested_step), style = MaterialTheme.typography.labelMedium, color = feature.onBlue, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSuggestions = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.mb_change), color = feature.onBlue, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = draft.nextStep,
                    onValueChange = { if (it.length <= AppLimits.NEXT_STEP_CHARACTERS) update(draft.copy(nextStep = it)) },
                    label = { Text(stringResource(R.string.next_step)) },
                    placeholder = { Text(suggestedAction) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                )
                DurationChoices(draft.focusMinutes) { update(draft.copy(focusMinutes = it)) }
            } else {
                Text(action, style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(17.dp), tint = feature.onBlue)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.minutes_value, draft.focusMinutes), style = MaterialTheme.typography.bodySmall, color = feature.onBlue)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { update(draft.copy(nextStep = action)); editing = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = feature.onBlue)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mb_make_it_mine), color = feature.onBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (showNote) {
            OutlinedTextField(
                draft.note, { if (it.length <= AppLimits.NOTE_CHARACTERS) update(draft.copy(note = it)) },
                label = { Text(stringResource(R.string.mb_note_optional)) }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 7, shape = RoundedCornerShape(16.dp),
            )
        } else TextButton(onClick = { showNote = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.mb_add_note))
        }
        PrimaryAction(stringResource(R.string.mb_use_plan), enabled = action.isNotBlank()) {
            focus.clearFocus()
            val existing = draft.outcomes.filter { it.text.isNotBlank() }
            val generatedOutcomeText = action.trim().take(AppLimits.OUTCOME_CHARACTERS).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }.trimEnd()
            val chosen = existing.firstOrNull { it.id == draft.selectedOutcomeId } ?: existing.firstOrNull()
                ?: Outcome(text = generatedOutcomeText)
            val outcomes = if (existing.isEmpty()) listOf(chosen) else existing
            onComplete(draft.copy(nextStep = action, outcomes = outcomes, selectedOutcomeId = chosen.id))
        }
    }
    if (showSuggestions) AlertDialog(
        onDismissRequest = { showSuggestions = false },
        title = { Text(stringResource(R.string.mb_choose_step)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(resetAction to 5, clearAction to 5, focusAction to initial.focusMinutes).forEach { (text, minutes) ->
                    Surface(onClick = { update(draft.copy(nextStep = text, focusMinutes = minutes)); showSuggestions = false },
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.minutes_value, minutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showSuggestions = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun MoodChoices(selected: String, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) 3 else 5
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            arrivalOptions().chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (key, label) ->
                        val active = key == selected
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(key) })
                                .padding(vertical = 12.dp, horizontal = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MoodFace(key, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2, maxLines = 2)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun MoodFace(mood: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        drawCircle(color, radius = w * .43f, style = Stroke(stroke))
        if (mood == "low") {
            drawLine(color, Offset(w * .27f, h * .42f), Offset(w * .39f, h * .42f), stroke)
            drawLine(color, Offset(w * .61f, h * .42f), Offset(w * .73f, h * .42f), stroke)
        } else {
            drawCircle(color, radius = w * .035f, center = Offset(w * .33f, h * .4f))
            drawCircle(color, radius = w * .035f, center = Offset(w * .67f, h * .4f))
        }
        when (mood) {
            "ready", "steady" -> drawArc(color, 10f, 160f, false, Offset(w * .3f, h * .47f), Size(w * .4f, h * .25f), style = Stroke(stroke))
            "tense" -> drawArc(color, 190f, 160f, false, Offset(w * .33f, h * .61f), Size(w * .34f, h * .2f), style = Stroke(stroke))
            "scattered" -> drawCircle(color, radius = w * .07f, center = Offset(w * .5f, h * .66f), style = Stroke(stroke))
            else -> drawLine(color, Offset(w * .36f, h * .66f), Offset(w * .64f, h * .66f), stroke)
        }
    }
}
