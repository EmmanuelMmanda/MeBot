package mebot.nguli.com.security

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import mebot.nguli.com.R

@Composable
fun AppLockScreen(controller: AppLockController, onBiometric: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") } // Deliberately never rememberSaveable.
    var recovery by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { pin = ""; recovery = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { /* Back must never reveal the protected navigation tree. */ }
    Surface(Modifier.fillMaxSize().testTag("app-lock-screen")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(36.dp))
            Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.app_lock_enter_pin), style = MaterialTheme.typography.bodyMedium)
            PinField(pin, { pin = it }, R.string.app_lock_pin, !state.busy)
            LockError(state)
            Button(onClick = { val input = pin.toCharArray(); pin = ""; scope.launch { controller.unlock(input) } },
                enabled = !state.busy && pin.length >= 6, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("app-lock-unlock")) {
                Text(stringResource(if (state.busy) R.string.app_lock_checking else R.string.app_lock_unlock))
            }
            if (state.biometric) OutlinedButton(onClick = { pin = ""; onBiometric() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_lock_biometric))
            }
            TextButton(onClick = { pin = ""; recovery = true }) { Text(stringResource(R.string.app_lock_forgot)) }
        }
    }
    if (recovery) AlertDialog(onDismissRequest = { recovery = false }, title = { Text(stringResource(R.string.app_lock_forgot)) },
        text = { Text(stringResource(R.string.app_lock_recovery)) },
        confirmButton = { TextButton(onClick = { recovery = false }) { Text(stringResource(R.string.app_lock_close)) } })
}

@Composable
fun AppLockSettingsScreen(controller: AppLockController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { current = ""; next = ""; confirm = "" }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!controller.state.value.enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun clear() { current = ""; next = ""; confirm = ""; mismatch = false; done = false }
    BackHandler { clear(); onBack() }
    Surface(Modifier.fillMaxSize().testTag("app-lock-settings")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = { clear(); onBack() }) { Text(stringResource(R.string.app_lock_back)) }
            Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.app_lock_boundary), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (state.enabled) R.string.app_lock_enabled else R.string.app_lock_disabled), style = MaterialTheme.typography.titleMedium)
            if (state.enabled) PinField(current, { current = it; done = false }, R.string.app_lock_current_pin, !state.busy)
            PinField(next, { next = it; mismatch = false; done = false }, R.string.app_lock_new_pin, !state.busy)
            PinField(confirm, { confirm = it; mismatch = false }, R.string.app_lock_confirm_pin, !state.busy)
            Text(stringResource(R.string.app_lock_pin_rules), style = MaterialTheme.typography.bodySmall)
            if (mismatch) Text(stringResource(R.string.app_lock_mismatch), color = MaterialTheme.colorScheme.error)
            LockError(state)
            if (done) Text(stringResource(R.string.app_lock_saved), color = MaterialTheme.colorScheme.primary)
            Button(onClick = {
                if (next != confirm) { mismatch = true; return@Button }
                val old = current.toCharArray(); val replacement = next.toCharArray(); clear()
                scope.launch {
                    done = if (state.enabled) controller.changePin(old, replacement)
                        else { old.fill('\u0000'); controller.setup(replacement) }
                }
            }, enabled = !state.busy && next.length >= 6 && (!state.enabled || current.length >= 6), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.enabled) R.string.app_lock_change else R.string.app_lock_enable))
            }
            if (state.enabled) {
                Text(stringResource(R.string.app_lock_biometric_help), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val input = current.toCharArray(); clear()
                    scope.launch {
                        val enabled = !state.biometric
                        if (enabled && (activity == null || !BiometricUnlock(activity, controller).available())) {
                            input.fill('\u0000'); controller.biometricFailure()
                        } else done = controller.setBiometric(input, enabled, BiometricUnlock::prepareKey)
                    }
                }, enabled = !state.busy && current.length >= 6, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.biometric) R.string.app_lock_biometric_disable else R.string.app_lock_biometric_enable))
                }
                OutlinedButton(onClick = { val input = current.toCharArray(); clear(); scope.launch { done = controller.disable(input) } },
                    enabled = !state.busy && current.length >= 6, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_lock_disable))
                }
            }
        }
    }
}

internal fun android.content.Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PinField(value: String, changed: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 32 && it.all { c -> c in '0'..'9' }) changed(it) },
        label = { Text(stringResource(label)) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LockError(state: AppLockState) {
    val message = when (state.problem) {
        LockProblem.WRONG_PIN -> R.string.app_lock_wrong
        LockProblem.WAIT -> R.string.app_lock_wait
        LockProblem.STORAGE -> R.string.app_lock_storage
        LockProblem.INVALID_PIN -> R.string.app_lock_pin_rules
        LockProblem.CANCELLED -> R.string.app_lock_cancelled
        LockProblem.BIOMETRIC -> R.string.app_lock_biometric_error
        null -> return
    }
    Text(if (state.problem == LockProblem.WAIT) stringResource(message, ((state.waitMillis + 999) / 1000).toInt()) else stringResource(message),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
