package app.mynguli.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.mynguli.R
import app.mynguli.core.Appearance
import app.mynguli.core.Settings

@Composable
internal fun AppearanceControls(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(settings.customAccent) }
    var editCustom by rememberSaveable { mutableStateOf(settings.accent == "custom") }
    val labels = mapOf("ember" to R.string.mb_raspberry, "sage" to R.string.accent_sage,
        "blue" to R.string.accent_blue, "violet" to R.string.appearance_violet,
        "teal" to R.string.appearance_teal, "amber" to R.string.appearance_amber,
        "rose" to R.string.appearance_rose, "slate" to R.string.appearance_slate, "custom" to R.string.appearance_custom)
    Text(stringResource(R.string.secondary_theme), style = MaterialTheme.typography.titleSmall)
    ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)), settings.theme) { mode -> onChange { it.copy(theme = mode) } }
    Text(stringResource(R.string.appearance_color), style = MaterialTheme.typography.titleSmall)
    (Appearance.presets.keys.toList() + "custom").chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { key ->
                val selected = settings.accent == key
                val name = stringResource(labels.getValue(key))
                val hex = if (key == "custom") settings.customAccent else Appearance.presets.getValue(key)
                val rgb = Appearance.normalizeHex(hex)?.removePrefix("#")?.toInt(16) ?: 0x7251B5
                Surface(onClick = {
                    if (key == "custom") editCustom = true
                    else { editCustom = false; onChange { it.copy(accent = key) } }
                }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp).testTag("accent-$key")
                        .semantics { this.selected = selected; contentDescription = name; role = Role.RadioButton }) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(26.dp).background(Color(0xFF000000L or rgb.toLong()), CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp),
                                tint = Color(0xFF000000L or Appearance.foreground(rgb).toLong()))
                        }
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (editCustom) {
        val valid = Appearance.normalizeHex(custom)
        OutlinedTextField(value = custom, onValueChange = { custom = it.take(7) }, singleLine = true,
            label = { Text(stringResource(R.string.appearance_hex)) },
            supportingText = { Text(stringResource(R.string.appearance_hex_help)) },
            isError = custom.isNotEmpty() && valid == null,
            leadingIcon = { Box(Modifier.size(24.dp).background(
                Color(0xFF000000L or ((valid?.removePrefix("#")?.toLong(16)) ?: 0x7251B5L)), CircleShape)) },
            modifier = Modifier.fillMaxWidth().testTag("custom-accent-input"))
        FilledTonalButton(onClick = { valid?.let { hex -> onChange { it.copy(accent = "custom", customAccent = hex) } } },
            enabled = valid != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("custom-accent-apply")) {
            Text(stringResource(R.string.appearance_apply))
        }
    }
    Text(stringResource(R.string.appearance_skin), style = MaterialTheme.typography.titleSmall)
    val skins = listOf("classic" to R.string.appearance_classic, "paper" to R.string.appearance_paper,
        "tinted" to R.string.appearance_tinted, "oled" to R.string.appearance_oled)
    skins.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (key, title) ->
                FilterChip(selected = settings.skin == key, onClick = { onChange { it.copy(skin = key) } },
                    label = { Text(stringResource(title)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("skin-$key"))
            }
        }
    }
    if (settings.skin == "oled") Text(stringResource(R.string.appearance_oled_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Article, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.appearance_preview), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.appearance_preview_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}
