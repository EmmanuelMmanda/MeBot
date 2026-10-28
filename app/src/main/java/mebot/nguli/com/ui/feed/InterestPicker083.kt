package mebot.nguli.com.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.ModuleCatalog
import mebot.nguli.com.core.ModuleDiscovery

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InterestPicker083(
    selectedKinds: List<String>,
    enabled: Boolean,
    tagPrefix: String = "onboarding",
    onChange: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(ModuleDiscovery.ALL) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labels = ModuleCatalog.presets.associate { it.kind to "${stringResource(moduleNameResource(it.kind))} ${stringResource(moduleDescriptionResource(it.kind))}" }
    val matches = ModuleCatalog.presets.filter { preset ->
        (group == ModuleDiscovery.ALL || preset.group == group) && ModuleDiscovery.matches(query, labels[preset.kind].orEmpty())
    }
    val visible = if (expanded || query.isNotBlank() || group != ModuleDiscovery.ALL) matches else matches.take(12)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.interests_selected, selectedKinds.size), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("$tagPrefix-selected-count"))
        OutlinedTextField(query, { query = it.take(120) }, enabled = enabled, singleLine = true,
            placeholder = { Text(stringResource(R.string.interests_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, enabled = enabled) { Icon(Icons.Outlined.Close, stringResource(R.string.modules_clear_search)) } },
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-interest-search"))
        HorizontalRail(listOf(ModuleDiscovery.ALL) + ModuleCatalog.groups, itemKey = { it }, selectedKey = group) { key ->
            FilterChip(selected = group == key, enabled = enabled, onClick = { group = key },
                label = { Text(stringResource(when(key) { "growth" -> R.string.module_group_growth; "life" -> R.string.module_group_life; "signals" -> R.string.module_group_signals; else -> R.string.modules_all_categories })) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-interest-group-$key"))
        }
        // Restore the original equal-width icon tiles. Keep large-text accessibility,
        // rather than shrinking the system font scale to force two cramped columns.
        val configuration = LocalConfiguration.current
        val columns = if (configuration.screenWidthDp < 360 && LocalDensity.current.fontScale > 1.15f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { preset ->
                        val isSelected = preset.kind in selectedKinds
                        Surface(onClick = { onChange(if (isSelected) selectedKinds - preset.kind else selectedKinds + preset.kind) }, enabled = enabled,
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tagPrefix-interest-${preset.kind}")
                                .semantics { selected = isSelected; role = Role.Checkbox; toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(moduleIcon(preset.kind), null, Modifier.size(18.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                                Text(stringResource(moduleNameResource(preset.kind)), style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (matches.isEmpty()) Text(stringResource(R.string.interests_empty), style = MaterialTheme.typography.bodyMedium)
        if (matches.size > 12 && query.isBlank() && group == ModuleDiscovery.ALL) TextButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-interests-more")) {
            Text(stringResource(if (expanded) R.string.interests_less else R.string.interests_more))
        }
    }
}
