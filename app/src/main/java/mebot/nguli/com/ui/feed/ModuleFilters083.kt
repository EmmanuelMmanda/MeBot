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
    Column(Modifier.fillMaxWidth().testTag("module-filters"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query, { onQuery(it.take(120)) }, placeholder = { Text(stringResource(R.string.modules_find)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
            trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag("module-search-clear")) {
                Icon(Icons.Outlined.Close, stringResource(R.string.modules_clear_search))
            } }, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("module-search"))
        HorizontalRail(listOf(ModuleDiscovery.ALL to R.string.modules_filter_all, ModuleDiscovery.ENABLED to R.string.modules_filter_enabled,
            ModuleDiscovery.DISABLED to R.string.modules_filter_disabled), itemKey = { it.first }, selectedKey = status,
            modifier = Modifier.testTag("module-status-rail")) { (key, label) ->
            FilterChip(selected = status == key, onClick = { onStatus(key) }, label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("module-status-$key"))
        }
        HorizontalRail(listOf(ModuleDiscovery.ALL) + ModuleCatalog.groups, itemKey = { it }, selectedKey = category,
            modifier = Modifier.testTag("module-category-rail")) { key ->
            FilterChip(selected = category == key, onClick = { onCategory(key) }, label = { Text(stringResource(when(key) {
                "growth" -> R.string.module_group_growth; "life" -> R.string.module_group_life; "signals" -> R.string.module_group_signals; else -> R.string.modules_all_categories
            })) }, modifier = Modifier.heightIn(min = 48.dp).testTag("module-category-$key"))
        }
        Text(stringResource(R.string.modules_results_count, resultCount), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
