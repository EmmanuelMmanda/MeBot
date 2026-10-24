package app.mynguli.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mynguli.BuildConfig
import app.mynguli.MyNguliViewModel
import app.mynguli.R
import app.mynguli.core.AppState
import java.time.LocalDate

@Composable
fun SettingsScreen(state: AppState, vm: MyNguliViewModel, onErase: () -> Unit = { vm.erase() }, onHelp: (() -> Unit)? = null) {
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable(state.profile.name) { mutableStateOf(state.profile.name) }
    var intention by rememberSaveable(state.profile.intention) { mutableStateOf(state.profile.intention) }
    var showImportConfirmation by rememberSaveable { mutableStateOf(false) }
    var showEraseConfirmation by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    val language = stringResource(when (state.settings.language) { "sw" -> R.string.swahili; "en" -> R.string.english; else -> R.string.theme_system })
    val theme = stringResource(when (state.settings.theme) { "dark" -> R.string.theme_dark; "light" -> R.string.theme_light; else -> R.string.theme_system })
    ScreenContainer {
        ScreenTitle(stringResource(R.string.settings_title))
        SectionLabel(stringResource(R.string.mb_preferences))
        SettingsGroup {
            SettingsRow(Icons.Outlined.Palette, stringResource(R.string.appearance), theme) { panel = "appearance" }
            SettingsDivider()
            SettingsRow(Icons.Outlined.Language, stringResource(R.string.language), language) { panel = "language" }
            SettingsDivider()
            SettingsRow(Icons.Outlined.Timer, stringResource(R.string.default_focus), stringResource(R.string.minutes_value, state.settings.defaultFocusMinutes)) { panel = "duration" }
            SettingsDivider()
            SettingsRow(Icons.Outlined.GridView, stringResource(R.string.modules), null) { panel = "features" }
        }
        SectionLabel(stringResource(R.string.mb_your_data))
        SettingsGroup {
            SettingsRow(Icons.Outlined.FileUpload, stringResource(R.string.export_data), null) {
                exportLauncher.launch("mebot-backup-${LocalDate.now()}.json")
            }
            SettingsDivider()
            SettingsRow(Icons.Outlined.FileDownload, stringResource(R.string.import_data), null) { showImportConfirmation = true }
            SettingsDivider()
            SettingsRow(Icons.Outlined.DeleteOutline, stringResource(R.string.erase_data), null, destructive = true) { showEraseConfirmation = true }
        }
        Text(stringResource(R.string.mb_data_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onHelp != null) SettingsGroup {
            SettingsRow(Icons.Outlined.HelpOutline, stringResource(R.string.setup_faq_title), stringResource(R.string.setup_faq_summary), onClick = onHelp)
        }
        TextButton(onClick = { panel = "about" }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("${stringResource(R.string.app_name)} · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
        }
    }
    val panelTitle = when (panel) {
        "profile" -> R.string.profile; "appearance" -> R.string.appearance; "language" -> R.string.language
        "duration" -> R.string.default_focus; "features" -> R.string.modules; else -> R.string.about
    }
    if (panel != null) AlertDialog(
        onDismissRequest = { panel = null },
        title = { Text(stringResource(panelTitle)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                when (panel) {
                    "profile" -> {
                        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(stringResource(R.string.name_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(intention, { if (it.length <= 500) intention = it }, label = { Text(stringResource(R.string.your_intention)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    }
                    "appearance" -> {
                        Text(stringResource(R.string.secondary_theme), style = MaterialTheme.typography.titleSmall)
                        ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)), state.settings.theme) { selected -> vm.saveSettings { it.copy(theme = selected) } }
                        Text(stringResource(R.string.secondary_accent), style = MaterialTheme.typography.titleSmall)
                        ChoiceRow(listOf("ember" to stringResource(R.string.mb_raspberry), "sage" to stringResource(R.string.accent_sage), "blue" to stringResource(R.string.accent_blue)), state.settings.accent) { selected -> vm.saveSettings { it.copy(accent = selected) } }
                    }
                    "language" -> ChoiceRow(listOf("en" to stringResource(R.string.english), "sw" to stringResource(R.string.swahili), "system" to stringResource(R.string.theme_system)), state.settings.language) { selected -> vm.saveSettings { it.copy(language = selected) } }
                    "duration" -> DurationChoices(state.settings.defaultFocusMinutes) { selected -> vm.saveSettings { it.copy(defaultFocusMinutes = selected) } }
                    "features" -> {
                        ModuleSetting(stringResource(R.string.alarms), "alarms" in state.settings.enabledModules) { checked -> vm.saveSettings { it.copy(enabledModules = (if (checked) it.enabledModules + "alarms" else it.enabledModules - "alarms") + "daily") } }
                        ModuleSetting(stringResource(R.string.focus_tab), "focus" in state.settings.enabledModules) { checked -> vm.saveSettings { it.copy(enabledModules = (if (checked) it.enabledModules + "focus" else it.enabledModules - "focus") + "daily") } }
                        ModuleSetting(stringResource(R.string.history), "history" in state.settings.enabledModules) { checked -> vm.saveSettings { it.copy(enabledModules = (if (checked) it.enabledModules + "history" else it.enabledModules - "history") + "daily") } }
                    }
                    else -> {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        SupportingText(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
                        SupportingText(stringResource(R.string.mb_data_summary))
                        SupportingText(stringResource(R.string.license_label))
                        SupportingText(stringResource(R.string.credits))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (panel == "profile") vm.saveProfile(name, intention, state.profile.onboardingComplete)
                panel = null
            }) { Text(stringResource(if (panel == "profile") R.string.save else R.string.mb_done)) }
        },
        dismissButton = { if (panel == "profile") TextButton(onClick = { name = state.profile.name; intention = state.profile.intention; panel = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showImportConfirmation) AlertDialog(
        onDismissRequest = { showImportConfirmation = false }, title = { Text(stringResource(R.string.import_confirm_title)) },
        text = { Text(stringResource(R.string.import_confirm_body)) },
        confirmButton = { TextButton(onClick = { showImportConfirmation = false; importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.import_confirm_action)) } },
        dismissButton = { TextButton(onClick = { showImportConfirmation = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showEraseConfirmation) AlertDialog(
        onDismissRequest = { showEraseConfirmation = false }, title = { Text(stringResource(R.string.erase_title)) },
        text = { Text(stringResource(R.string.erase_body)) },
        confirmButton = { TextButton(onClick = { showEraseConfirmation = false; onErase() }) { Text(stringResource(R.string.erase_action), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showEraseConfirmation = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) { Column(content = content) }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 64.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)) }

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String?, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(icon, null, Modifier.size(22.dp), tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    color = if (destructive) tint else MaterialTheme.colorScheme.onSurface)
                if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModuleSetting(title: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = enabled, onCheckedChange = null)
    }
}
