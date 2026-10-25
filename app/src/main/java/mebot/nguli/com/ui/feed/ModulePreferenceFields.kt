package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.ModulePreferences
import mebot.nguli.com.core.ModulePreferenceDefinition
import mebot.nguli.com.ui.AppCard

@Composable
internal fun ModulePreferenceFields(kind: String, preferences: Map<String, String>, enabled: Boolean,
    onChange: (String, String) -> Unit, onReset: () -> Unit) {
    val definitions = remember(kind) { ModulePreferences.definitions(kind) }
    if (definitions.isEmpty()) return
    AppCard {
        Text(stringResource(R.string.preferences_for_topic_083), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.preferences_topic_body_083), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        definitions.forEach { definition ->
            val value = preferences[definition.key] ?: definition.defaultValue
            val label = stringResource(preferenceLabel(definition.key))
            if (definition.isText) OutlinedTextField(value, { if (it.length <= definition.maximumLength) onChange(definition.key, it) },
                label = { Text(label) }, singleLine = true, enabled = enabled, shape = RoundedCornerShape(14.dp),
                supportingText = { Text(stringResource(R.string.modules_location_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("module-pref-${definition.key}"))
            else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            ModulePreferenceSelector(kind, definition, value, label, enabled, onChange, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            ModulePreferenceSelector(kind, definition, value, label, enabled, onChange,
                                Modifier.widthIn(min = 128.dp, max = 166.dp).weight(1.2f))
                        }
                    }
                }
            }
        }
        if (preferences.isNotEmpty()) TextButton(onClick = onReset, enabled = enabled,
            modifier = Modifier.testTag("module-prefs-reset"), contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.modules_preferences_reset))
        }
    }
}

@Composable
private fun ModulePreferenceSelector(kind: String, definition: ModulePreferenceDefinition, value: String, label: String,
    enabled: Boolean, onChange: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(kind, definition.key) { mutableStateOf(false) }
    val selectedLabel = stringResource(preferenceValue(value))
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("module-pref-${definition.key}")
                .semantics { contentDescription = "$label: $selectedLabel" },
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
            Text(selectedLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            definition.choices.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(preferenceValue(option))) },
                    onClick = { expanded = false; onChange(definition.key, option) },
                    trailingIcon = { if (value == option) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("module-pref-${definition.key}-$option"))
            }
        }
    }
}

private fun preferenceLabel(key: String): Int = when (key) {
    "focus" -> R.string.modules_pref_focus
    "coverage" -> R.string.modules_pref_coverage
    "location" -> R.string.modules_pref_location
    "language" -> R.string.modules_pref_language
    "urgency" -> R.string.modules_pref_urgency
    "type" -> R.string.modules_pref_type
    "workplace" -> R.string.modules_pref_workplace
    "level" -> R.string.modules_pref_level
    "funding" -> R.string.modules_pref_funding
    "format" -> R.string.modules_pref_format
    "cost" -> R.string.modules_pref_cost
    "code_language" -> R.string.modules_pref_code_language
    "mood" -> R.string.modules_pref_mood
    "attendance" -> R.string.modules_pref_attendance
    "window" -> R.string.modules_pref_window
    "tone" -> R.string.modules_pref_tone
    "duration" -> R.string.modules_pref_duration
    else -> R.string.modules_preferences
}

private fun preferenceValue(value: String): Int = when (value) {
    "en" -> R.string.english
    "sw" -> R.string.swahili
    "auto" -> R.string.modules_option_auto
    "all" -> R.string.modules_option_all
    "any" -> R.string.modules_option_any
    "balanced" -> R.string.modules_option_balanced
    "local" -> R.string.modules_option_local
    "world" -> R.string.modules_option_world
    "ai" -> R.string.modules_option_ai
    "software" -> R.string.modules_option_software
    "infrastructure" -> R.string.modules_option_infrastructure
    "hardware" -> R.string.modules_option_hardware
    "cloud" -> R.string.modules_option_cloud
    "networks" -> R.string.modules_option_networks
    "critical" -> R.string.modules_option_critical
    "regional" -> R.string.modules_option_regional
    "operations" -> R.string.modules_option_operations
    "markets" -> R.string.modules_option_markets
    "health" -> R.string.modules_option_health
    "climate" -> R.string.modules_option_climate
    "space" -> R.string.modules_option_space
    "jobs" -> R.string.modules_option_jobs
    "contracts" -> R.string.modules_option_contracts
    "grants" -> R.string.modules_option_grants
    "remote" -> R.string.modules_option_remote
    "masters" -> R.string.modules_option_masters
    "phd" -> R.string.modules_option_phd
    "full" -> R.string.modules_option_full
    "beginner" -> R.string.modules_option_beginner
    "intermediate" -> R.string.modules_option_intermediate
    "advanced" -> R.string.modules_option_advanced
    "guides" -> R.string.modules_option_guides
    "courses" -> R.string.modules_option_courses
    "videos" -> R.string.modules_option_videos
    "free" -> R.string.modules_option_free
    "tools" -> R.string.modules_option_tools
    "libraries" -> R.string.modules_option_libraries
    "self_hosted" -> R.string.modules_option_self_hosted
    "python" -> R.string.modules_option_python
    "javascript" -> R.string.modules_option_javascript
    "php" -> R.string.modules_option_php
    "go" -> R.string.modules_option_go
    "rust" -> R.string.modules_option_rust
    "both" -> R.string.modules_option_both
    "films" -> R.string.modules_option_films
    "series" -> R.string.modules_option_series
    "tense" -> R.string.modules_option_tense
    "thoughtful" -> R.string.modules_option_thoughtful
    "light" -> R.string.modules_option_light
    "mixed" -> R.string.modules_option_mixed
    "listening" -> R.string.modules_option_listening
    "practice" -> R.string.modules_option_practice
    "performances" -> R.string.modules_option_performances
    "lessons" -> R.string.modules_option_lessons
    "nearby" -> R.string.modules_option_nearby
    "online" -> R.string.modules_option_online
    "month" -> R.string.modules_option_month
    "week" -> R.string.modules_option_week
    "weekend" -> R.string.modules_option_weekend
    "outdoors" -> R.string.modules_option_outdoors
    "culture" -> R.string.modules_option_culture
    "low_cost" -> R.string.modules_option_low_cost
    "general" -> R.string.modules_option_general
    "work" -> R.string.modules_option_work
    "relationships" -> R.string.modules_option_relationships
    "reflection" -> R.string.modules_option_reflection
    "calm" -> R.string.modules_option_calm
    "playful" -> R.string.modules_option_playful
    "explainers" -> R.string.modules_option_explainers
    "demonstrations" -> R.string.modules_option_demonstrations
    "interviews" -> R.string.modules_option_interviews
    "news" -> R.string.modules_option_news
    "short" -> R.string.modules_option_short
    "quick" -> R.string.modules_option_quick
    "articles" -> R.string.modules_option_articles
    else -> R.string.modules_option_any
}
