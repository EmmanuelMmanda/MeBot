package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.ModuleCatalog
import mebot.nguli.com.core.ModuleDiscovery

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModuleFilters083(query: String, status: String, category: String, resultCount: Int,
    onQuery: (String) -> Unit, onStatus: (String) -> Unit, onCategory: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.modules_filters_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(query, { onQuery(it.take(120)) }, placeholder = { Text(stringResource(R.string.modules_find)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag("module-search-clear")) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.modules_clear_search))
                } }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("module-search"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ModuleDiscovery.ALL to R.string.modules_filter_all, ModuleDiscovery.ENABLED to R.string.modules_filter_enabled,
                    ModuleDiscovery.DISABLED to R.string.modules_filter_disabled).forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { onStatus(key) }, label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("module-status-$key"))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.modules_categories_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(ModuleDiscovery.ALL) + ModuleCatalog.groups).forEach { key ->
                    FilterChip(selected = category == key, onClick = { onCategory(key) }, label = { Text(stringResource(when(key) {
                        "growth" -> R.string.module_group_growth; "life" -> R.string.module_group_life; "signals" -> R.string.module_group_signals; else -> R.string.modules_all_categories
                    })) }, modifier = Modifier.testTag("module-category-$key"))
                }
            }
            Text(stringResource(R.string.modules_results_count, resultCount), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
