package mebot.nguli.com.ui.feed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.ui.*

/** Background preferences never overwrite the independent, atomic AI request allowance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeliveryPreferencesScreen(ui: FeedUiState, vm: FeedViewModel, onDone: () -> Unit, onConnection: (() -> Unit)? = null) {
    val state = ui.state ?: return
    val context = LocalContext.current
    var background by rememberSaveable { mutableStateOf(state.backgroundEnabled || state.notifications) }
    var notifications by rememberSaveable { mutableStateOf(state.notifications) }
    var wifi by rememberSaveable { mutableStateOf(state.backgroundWifiOnly) }
    var battery by rememberSaveable { mutableStateOf(state.backgroundBatteryNotLow) }
    var hours by rememberSaveable { mutableStateOf(state.refreshHours.toString()) }
    var customHours by rememberSaveable { mutableStateOf(state.refreshHours !in listOf(1,3,6,12,24)) }
    var start by rememberSaveable { mutableStateOf(state.quietStartHour.toString()) }
    var end by rememberSaveable { mutableStateOf(state.quietEndHour.toString()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifications = granted; denied = !granted }
    val canEdit = !ui.deliverySaving
    LaunchedEffect(state, pending, ui.deliverySaving) {
        if (pending && !ui.deliverySaving) {
            pending = false
            if (ui.error == null && state.backgroundEnabled == background && state.refreshHours == hours.toIntOrNull() &&
                state.notifications == (background && notifications) && state.backgroundWifiOnly == wifi && state.backgroundBatteryNotLow == battery &&
                state.quietStartHour == start.toIntOrNull() && state.quietEndHour == end.toIntOrNull()) onDone()
        }
    }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.feed_delivery), stringResource(R.string.background_intro))
        AppCard {
            SettingsToggle(stringResource(R.string.background_refresh), background, canEdit, "delivery-enabled") { background = it }
            Text(stringResource(R.string.background_system_timing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.lastRefreshMillis > 0) Text(stringResource(R.string.feed_updated, relativeTime(state.lastRefreshMillis)), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = vm::refresh, enabled = !ui.refreshing, modifier = Modifier.fillMaxWidth().testTag("background-refresh-now")) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.background_refresh_now))
            }
        }
        AppCard {
            Text(stringResource(R.string.feed_refresh_interval), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1,3,6,12,24).forEach { n -> FilterChip(hours == n.toString() && !customHours,
                    onClick = { hours = n.toString(); customHours = false }, enabled = canEdit,
                    label = { Text(stringResource(R.string.delivery_interval_hours, n)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("delivery-interval-$n")) }
                FilterChip(customHours, onClick = { customHours = true }, label = { Text(stringResource(R.string.delivery_limit_custom)) }, enabled = canEdit, modifier = Modifier.testTag("delivery-interval-custom"))
            }
            if (customHours) OutlinedTextField(hours, { if (it.length <= 2 && it.all(Char::isDigit)) hours = it },
                label = { Text(stringResource(R.string.delivery_interval_custom_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("delivery-interval-input"), isError = error && hours.toIntOrNull() !in 1..24)
            Text(stringResource(R.string.background_batches), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.background_batches_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { n -> FilterChip(state.prefetchBatches == n,
                onClick = { vm.savePrefetchBatches(n) }, label = { Text(n.toString()) }, enabled = canEdit,
                modifier = Modifier.heightIn(min = 48.dp).testTag("background-batches-$n")) } }
        }
        AppCard {
            Text(stringResource(R.string.background_conditions), style = MaterialTheme.typography.titleMedium)
            SettingsToggle(stringResource(R.string.background_wifi), wifi, canEdit, "background-wifi") { wifi = it }
            SettingsToggle(stringResource(R.string.background_battery), battery, canEdit, "background-battery") { battery = it }
            Text(stringResource(R.string.background_interactive_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppCard {
            SettingsToggle(stringResource(R.string.background_notifications), notifications, canEdit && background, "background-notifications") { checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { notifications = checked; denied = false }
            }
            Text(stringResource(R.string.feed_delivery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (denied) Text(stringResource(R.string.feed_permission_denied), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.feed_quiet_hours), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(start, { if (it.length <= 2 && it.all(Char::isDigit)) start = it }, label = { Text(stringResource(R.string.feed_quiet_start)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f).testTag("delivery-quiet-start"))
                OutlinedTextField(end, { if (it.length <= 2 && it.all(Char::isDigit)) end = it }, label = { Text(stringResource(R.string.feed_quiet_end)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f).testTag("delivery-quiet-end"))
            }
            Text(stringResource(R.string.delivery_quiet_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onConnection != null) Surface(onClick = onConnection, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null); Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feed_request_limit), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.background_allowance_link), style = MaterialTheme.typography.bodySmall)
                }; Icon(Icons.Outlined.ChevronRight, null)
            }
        }
        if (error) Text(stringResource(R.string.background_invalid), color = MaterialTheme.colorScheme.error)
        ui.error?.let { FeedStatus(it) }
        Button(onClick = {
            val h = hours.toIntOrNull(); val a = start.toIntOrNull(); val b = end.toIntOrNull()
            error = h !in 1..24 || a !in 0..23 || b !in 0..23
            if (!error) { pending = true; vm.saveBackgroundPreferences(background, notifications, h!!, a!!, b!!, wifi, battery) }
        }, enabled = canEdit, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("delivery-save")) {
            Text(stringResource(if (ui.deliverySaving) R.string.delivery_saving else R.string.save))
        }
    }
}

@Composable
internal fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}
