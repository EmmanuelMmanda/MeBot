package mebot.nguli.com.documents

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import mebot.nguli.com.MeBotApplication

internal val LocalDocumentEpoch = staticCompositionLocalOf<() -> Long> { { 0L } }

/** Specialized file/ringtone contracts retain their exact grant flags and caller-owned draft/temp
 * handling. Only minimal result metadata is retained; provider extras/file bytes are not saved. */
@Composable
internal fun rememberDeferredDocumentLauncher(
    ownerIdentity: String,
    allowResult: (() -> Boolean)? = null,
    onResult: (ActivityResult) -> Unit,
): (Intent) -> Unit {
    val controller = (LocalContext.current.applicationContext as? MeBotApplication)?.appLock
    val protection = controller?.state?.collectAsStateWithLifecycle()?.value
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle by lifecycleOwner.lifecycle.currentStateAsState()
    val epoch by rememberUpdatedState(LocalDocumentEpoch.current)
    val currentOwner by rememberUpdatedState(ownerIdentity)
    val currentCallback by rememberUpdatedState(onResult)
    val currentOverride by rememberUpdatedState(allowResult)
    val allowed = allowResult?.invoke() ?: (protection?.locked != true)
    fun currentlyAllowed() = currentOverride?.invoke() ?: (controller?.state?.value?.locked != true)
    var launched by rememberSaveable { mutableStateOf(false) }
    var launchOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var launchEpoch by rememberSaveable { mutableLongStateOf(0) }
    var resultCode by rememberSaveable { mutableStateOf<Int?>(null) }
    var resultUri by rememberSaveable { mutableStateOf<String?>(null) }
    var ringtoneUri by rememberSaveable { mutableStateOf<String?>(null) }
    var flags by rememberSaveable { mutableIntStateOf(0) }
    var expectsRingtone by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (launched) {
            try {
                resultUri = result.data?.data?.toString()
                @Suppress("DEPRECATION")
                val ringtone = if (expectsRingtone) result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) else null
                ringtoneUri = ringtone?.toString()
                flags = result.data?.flags ?: 0
                resultCode = result.resultCode
            } catch (_: android.os.BadParcelableException) {
                resultUri = null; ringtoneUri = null; flags = 0; resultCode = Activity.RESULT_CANCELED
            } catch (_: ClassCastException) {
                resultUri = null; ringtoneUri = null; flags = 0; resultCode = Activity.RESULT_CANCELED
            }
        }
    }
    LaunchedEffect(resultCode, lifecycle, allowed, ownerIdentity) {
        val code = resultCode
        if (code != null && lifecycle == Lifecycle.State.RESUMED && currentlyAllowed()) {
            val stale = launchOwner != currentOwner || launchEpoch != epoch()
            val result = if (stale || code != Activity.RESULT_OK) ActivityResult(Activity.RESULT_CANCELED, null) else ActivityResult(code,
                Intent().apply {
                    resultUri?.let { data = Uri.parse(it) }
                    ringtoneUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.parse(it)) }
                    addFlags(flags)
                })
            launched = false; resultCode = null; resultUri = null; ringtoneUri = null; flags = 0
            currentCallback(result)
        }
    }
    return remember(launcher, lifecycleOwner) { { intent ->
        check(!launched && currentlyAllowed() && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            "Document picker unavailable while paused, locked, or another request is pending"
        }
        run {
            launched = true; launchOwner = currentOwner; launchEpoch = epoch()
            expectsRingtone = intent.action == RingtoneManager.ACTION_RINGTONE_PICKER
            try { launcher.launch(intent) }
            catch (failure: Exception) { launched = false; throw failure }
        }
    } }
}
