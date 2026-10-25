package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.ui.AppCard
import kotlinx.coroutines.delay
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestAllowanceCard(state: FeedState, enabled: Boolean, onSave: (Int) -> Unit) {
    var custom by rememberSaveable(state.dailyRequestLimit) { mutableStateOf(state.dailyRequestLimit.takeIf { it > 0 }?.toString().orEmpty()) }
    var editingCustom by rememberSaveable(state.dailyRequestLimit) { mutableStateOf(state.dailyRequestLimit !in RequestBudget.presets && state.dailyRequestLimit != 0) }
    var error by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), state.timezone) { while (true) { value = System.currentTimeMillis(); delay(60_000) } }
    val used = RequestBudget.usedToday(state, now)
    AppCard(modifier = Modifier.testTag("connection-request-allowance")) {
        Text(stringResource(R.string.feed_request_limit), style = MaterialTheme.typography.titleMedium)
        Text(if (state.dailyRequestLimit == 0) stringResource(R.string.delivery_usage_unlimited, used)
            else stringResource(R.string.delivery_usage_finite, used, state.dailyRequestLimit), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("connection-usage"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            RequestBudget.presets.forEach { n -> FilterChip(state.dailyRequestLimit == n && !editingCustom,
                onClick = { editingCustom = false; error = false; onSave(n) }, enabled = enabled,
                label = { Text(n.toString()) }, modifier = Modifier.heightIn(min = 48.dp).testTag("connection-limit-$n")) }
            FilterChip(editingCustom, onClick = { editingCustom = true }, enabled = enabled,
                label = { Text(stringResource(R.string.delivery_limit_custom)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("connection-limit-custom-toggle"))
            FilterChip(state.dailyRequestLimit == 0 && !editingCustom, onClick = { editingCustom = false; error = false; onSave(0) }, enabled = enabled,
                label = { Text(stringResource(R.string.delivery_limit_unlimited)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("connection-limit-unlimited"))
        }
        if (editingCustom) {
            OutlinedTextField(custom, { if (it.length <= 10 && it.all(Char::isDigit)) { custom = it; error = false } },
                label = { Text(stringResource(R.string.delivery_limit_custom_label)) }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = error,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("connection-limit-custom"))
            if (error) Text(stringResource(R.string.delivery_limit_invalid), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { val n = custom.toIntOrNull(); error = n == null || n <= 0; if (!error) onSave(n!!) }, enabled = enabled,
                modifier = Modifier.testTag("connection-limit-apply")) { Text(stringResource(R.string.allowance_apply)) }
        }
        Text(stringResource(R.string.allowance_timezone, state.timezone.ifBlank { ZoneId.systemDefault().id }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.delivery_limit_provider_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
