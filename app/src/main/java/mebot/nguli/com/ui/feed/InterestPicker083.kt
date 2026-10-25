package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.ModuleCatalog
import mebot.nguli.com.core.ModuleDiscovery

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InterestPicker083(selectedKinds: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(ModuleDiscovery.ALL) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labels = ModuleCatalog.presets.associate { it.kind to "${stringResource(moduleNameResource(it.kind))} ${stringResource(moduleDescriptionResource(it.kind))}" }
    val matches = ModuleCatalog.presets.filter { preset ->
        (group == ModuleDiscovery.ALL || preset.group == group) && ModuleDiscovery.matches(query, labels[preset.kind].orEmpty())
    }
    val visible = if (expanded || query.isNotBlank() || group != ModuleDiscovery.ALL) matches else matches.take(8)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.interests_selected, selectedKinds.size), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("onboarding-selected-count"))
        OutlinedTextField(query, { query = it.take(120) }, enabled = enabled, singleLine = true,
            placeholder = { Text(stringResource(R.string.interests_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, enabled = enabled) { Icon(Icons.Outlined.Close, stringResource(R.string.modules_clear_search)) } },
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("onboarding-interest-search"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf(ModuleDiscovery.ALL) + ModuleCatalog.groups).forEach { key ->
                FilterChip(selected = group == key, enabled = enabled, onClick = { group = key },
                    label = { Text(stringResource(when(key) { "growth" -> R.string.module_group_growth; "life" -> R.string.module_group_life; "signals" -> R.string.module_group_signals; else -> R.string.modules_all_categories })) },
                    modifier = Modifier.testTag("onboarding-interest-group-$key"))
            }
        }
        visible.forEach { preset ->
            val isSelected = preset.kind in selectedKinds
            Surface(onClick = { onChange(if (isSelected) selectedKinds - preset.kind else selectedKinds + preset.kind) }, enabled = enabled,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("onboarding-interest-${preset.kind}")
                    .semantics { selected = isSelected; role = Role.Checkbox }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(moduleIcon(preset.kind), null, Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(moduleNameResource(preset.kind)), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(moduleDescriptionResource(preset.kind)), style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(checked = isSelected, onCheckedChange = null, enabled = enabled)
                }
            }
        }
        if (matches.isEmpty()) Text(stringResource(R.string.interests_empty), style = MaterialTheme.typography.bodyMedium)
        if (matches.size > 8 && query.isBlank() && group == ModuleDiscovery.ALL) TextButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("onboarding-interests-more")) {
            Text(stringResource(if (expanded) R.string.interests_less else R.string.interests_more))
        }
    }
}
