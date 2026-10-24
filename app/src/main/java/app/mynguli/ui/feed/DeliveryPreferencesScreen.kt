package app.mynguli.ui.feed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.mynguli.R
import app.mynguli.core.RequestBudget
import app.mynguli.feed.FeedUiState
import app.mynguli.feed.FeedViewModel
import app.mynguli.ui.AppCard
import app.mynguli.ui.ScreenContainer
import app.mynguli.ui.ScreenTitle
import app.mynguli.ui.SectionLabel
import kotlinx.coroutines.delay

/** Durable settings stay editable after a failed or interrupted save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeliveryPreferencesScreen(ui: FeedUiState, vm: FeedViewModel, onDone: () -> Unit) {
    val state = ui.state ?: return
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(state.notifications) }
    var selectedLimit by rememberSaveable {
        mutableStateOf(when (state.dailyRequestLimit) {
            RequestBudget.UNLIMITED -> "unlimited"
            in RequestBudget.presets -> state.dailyRequestLimit.toString()
            else -> "custom"
        })
    }
    var customLimit by rememberSaveable {
        mutableStateOf(state.dailyRequestLimit.takeIf { it > 0 }?.toString() ?: RequestBudget.DEFAULT_LIMIT.toString())
    }
    val refreshPresets = listOf(1, 3, 6, 12, 24)
    var selectedHours by rememberSaveable {
        mutableStateOf(if (state.refreshHours in refreshPresets) state.refreshHours.toString() else "custom")
    }
    var customHours by rememberSaveable { mutableStateOf(state.refreshHours.toString()) }
    var quietStart by rememberSaveable { mutableStateOf(state.quietStartHour.toString()) }
    var quietEnd by rememberSaveable { mutableStateOf(state.quietEndHour.toString()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    val canEdit = !ui.deliverySaving
    val limit = when (selectedLimit) {
        "unlimited" -> RequestBudget.UNLIMITED
        "custom" -> customLimit.toIntOrNull()?.takeIf { it > 0 }
        else -> selectedLimit.toIntOrNull()
    }
    val hours = (if (selectedHours == "custom") customHours else selectedHours).toIntOrNull()?.takeIf { it in 1..24 }
    val from = quietStart.toIntOrNull()?.takeIf { it in 0..23 }
    val until = quietEnd.toIntOrNull()?.takeIf { it in 0..23 }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
        denied = !granted
    }
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = state.timezone) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val used = RequestBudget.usedToday(state, now)

    LaunchedEffect(state.notifications, state.refreshHours, state.dailyRequestLimit,
        state.quietStartHour, state.quietEndHour, pending, ui.deliverySaving, ui.error) {
        if (pending && !ui.deliverySaving) {
            pending = false
            val committed = state.notifications == enabled && state.refreshHours == hours &&
                state.dailyRequestLimit == limit && state.quietStartHour == from && state.quietEndHour == until
            if (committed && ui.error == null) onDone()
        }
    }

    ScreenContainer {
        val sw = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "sw"
        SectionLabel(if(sw) "Andaa taarifa zaidi" else "Prepare more content")
        Text(if(sw) "Chagua idadi ya mafungu kwa kila mada. Mafungu zaidi hutumia maombi zaidi ya AI." else "Discovery batches per topic. More batches use more AI requests.", style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (1..3).forEach { count ->
            FilterChip(selected=state.prefetchBatches==count,onClick={vm.savePrefetchBatches(count)},label={Text(count.toString())},modifier=Modifier.heightIn(min=48.dp))
        } }

        ScreenTitle(stringResource(R.string.feed_delivery), stringResource(R.string.feed_delivery_body))
        SectionLabel(stringResource(R.string.feed_request_limit))
        Text(when {
            used == Int.MAX_VALUE -> stringResource(R.string.delivery_usage_saturated)
            RequestBudget.isUnlimited(state.dailyRequestLimit) -> stringResource(R.string.delivery_usage_unlimited, used)
            else -> stringResource(R.string.delivery_usage_finite, used, state.dailyRequestLimit)
        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("delivery-usage"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestBudget.presets.forEach { value ->
                FilterChip(selected = selectedLimit == value.toString(), onClick = { selectedLimit = value.toString(); submitted = false },
                    enabled = canEdit,
                    label = { Text(if (value == RequestBudget.DEFAULT_LIMIT) stringResource(R.string.delivery_limit_default, value) else value.toString()) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-limit-$value"))
            }
            FilterChip(selected = selectedLimit == "custom", onClick = { selectedLimit = "custom"; submitted = false }, enabled = canEdit,
                label = { Text(stringResource(R.string.delivery_limit_custom)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-limit-custom"))
            FilterChip(selected = selectedLimit == "unlimited", onClick = { selectedLimit = "unlimited"; submitted = false }, enabled = canEdit,
                label = { Text(stringResource(R.string.delivery_limit_unlimited)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-limit-unlimited"))
        }
        if (selectedLimit == "custom") OutlinedTextField(customLimit,
            onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) { customLimit = it; submitted = false } },
            label = { Text(stringResource(R.string.delivery_limit_custom_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = canEdit,
            isError = submitted && limit == null,
            supportingText = if (submitted && limit == null) ({ Text(stringResource(R.string.delivery_limit_invalid)) }) else null,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("delivery-limit-input"))
        Text(stringResource(R.string.delivery_reset_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.delivery_limit_provider_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        AppCard {
            val backgroundLabel = stringResource(R.string.feed_delivery_enable)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(backgroundLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(enabled, { checked ->
                    if (checked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else { enabled = checked; denied = false }
                }, enabled = canEdit, modifier = Modifier.testTag("delivery-enabled").semantics { contentDescription = backgroundLabel })
            }
            Text(stringResource(R.string.feed_delivery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (denied) {
            Text(stringResource(R.string.feed_permission_denied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            }, enabled = canEdit) { Text(stringResource(R.string.feed_open_settings)) }
        }
        SectionLabel(stringResource(R.string.feed_refresh_interval))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            refreshPresets.forEach { value ->
                FilterChip(selected = selectedHours == value.toString(), onClick = { selectedHours = value.toString(); submitted = false }, enabled = canEdit,
                    label = { Text(stringResource(R.string.delivery_interval_hours, value)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-interval-$value"))
            }
            FilterChip(selected = selectedHours == "custom", onClick = { selectedHours = "custom"; submitted = false }, enabled = canEdit,
                label = { Text(stringResource(R.string.delivery_limit_custom)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-interval-custom"))
        }
        if (selectedHours == "custom") OutlinedTextField(customHours,
            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) { customHours = it; submitted = false } },
            label = { Text(stringResource(R.string.delivery_interval_custom_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = canEdit,
            isError = submitted && hours == null,
            supportingText = if (submitted && hours == null) ({ Text(stringResource(R.string.delivery_interval_invalid)) }) else null,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("delivery-interval-input"))
        SectionLabel(stringResource(R.string.feed_quiet_hours))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(quietStart, { if (it.length <= 2 && it.all(Char::isDigit)) { quietStart = it; submitted = false } },
                label = { Text(stringResource(R.string.feed_quiet_start)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("delivery-quiet-start"), singleLine = true, enabled = canEdit,
                isError = submitted && from == null, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(quietEnd, { if (it.length <= 2 && it.all(Char::isDigit)) { quietEnd = it; submitted = false } },
                label = { Text(stringResource(R.string.feed_quiet_end)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("delivery-quiet-end"), singleLine = true, enabled = canEdit,
                isError = submitted && until == null, shape = RoundedCornerShape(16.dp))
        }
        if (submitted && (from == null || until == null)) Text(stringResource(R.string.delivery_quiet_invalid), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.delivery_quiet_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.error?.let { FeedStatus(it) }
        Button(onClick = {
            submitted = true
            if (limit != null && hours != null && from != null && until != null) {
                pending = true
                vm.clearMessage()
                vm.saveDelivery(enabled, hours, limit, from, until)
            }
        }, enabled = canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("delivery-save"), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(if (ui.deliverySaving) R.string.delivery_saving else R.string.save))
        }
    }
}
