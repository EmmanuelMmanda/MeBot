package mebot.nguli.com.documents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.time.LocalDate

internal enum class DocumentOperation(val importing: Boolean, val basename: String) {
    IMPORT_CONTEXT(true, "context"), EXPORT_CONTEXT(false, "context"),
    IMPORT_FEED(true, "feed"), EXPORT_FEED(false, "feed"),
    IMPORT_LOCAL(true, "backup"), EXPORT_LOCAL(false, "backup"),
    IMPORT_ATTACHMENT(true, "attachment"), EXPORT_CHECKPOINT(false, "checkpoint"),
}

class DocumentActions internal constructor(
    private val invalidate: () -> Unit = {},
    private val request: (DocumentOperation) -> Unit,
) {
    /** Call synchronously before an explicit reset or confirmed full backup replacement. */
    fun invalidatePending() = invalidate()
    fun exportCheckpoint() = request(DocumentOperation.EXPORT_CHECKPOINT)
    fun importAttachment() = request(DocumentOperation.IMPORT_ATTACHMENT)
    fun importContext() = request(DocumentOperation.IMPORT_CONTEXT)
    fun exportContext() = request(DocumentOperation.EXPORT_CONTEXT)
    fun importFeedBackup() = request(DocumentOperation.IMPORT_FEED)
    fun exportFeedBackup() = request(DocumentOperation.EXPORT_FEED)
    fun importLocalBackup() = request(DocumentOperation.IMPORT_LOCAL)
    fun exportLocalBackup() = request(DocumentOperation.EXPORT_LOCAL)
}

// Standalone previews may render controls without an Activity host. Production always supplies one.
val LocalDocumentActions = staticCompositionLocalOf { DocumentActions { error("DocumentActionsHost is missing") } }

/** Must remain mounted above the app-lock gate, not inside an individual destination. */
@Composable
internal fun DocumentActionsHost(
    locked: Boolean,
    targetIdentity: (DocumentOperation) -> String? = { null },
    canUseDocuments: () -> Boolean,
    onDocument: (DocumentOperation, Uri, String?) -> Unit,
    onLaunchFailure: () -> Unit,
    content: @Composable () -> Unit,
) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var generation by rememberSaveable { mutableLongStateOf(0) }
    var pendingGeneration by rememberSaveable { mutableLongStateOf(0) }
    var pendingTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val currentIdentity by rememberUpdatedState(targetIdentity)
    val currentFailure by rememberUpdatedState(onLaunchFailure)
    val currentDocument by rememberUpdatedState(onDocument)
    val currentAllowed by rememberUpdatedState(canUseDocuments)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    fun received(uri: Uri?) {
        if (uri == null || pendingGeneration != generation ||
            (pending?.let { name -> DocumentOperation.entries.firstOrNull { it.name == name } }?.let { pendingTarget != currentIdentity(it) } == true)) { pending = null; selectedUri = null }
        else if (pending != null) selectedUri = uri.toString()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onResult = ::received)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), onResult = ::received)
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), onResult = ::received)
    val actions = remember(open, create, png) { DocumentActions(invalidate = {
        generation++
        // An outstanding platform request must drain before a new one can be launched; otherwise
        // its late result could be mistaken for the replacement request. An already queued URI
        // has no outstanding platform callback and can be discarded immediately.
        if (selectedUri != null) { pending = null; selectedUri = null }
    }) { operation ->
        // Ignore duplicate taps and stale actions from a destination that is being locked/disposed.
        if (pending == null && currentAllowed()) {
            pendingTarget = currentIdentity(operation)
            pendingGeneration = generation
            pending = operation.name
            selectedUri = null
            try {
                if (operation.importing) open.launch(if (operation == DocumentOperation.IMPORT_ATTACHMENT)
                    arrayOf("text/plain", "text/markdown", "text/x-markdown")
                    else arrayOf("application/json", "text/plain", "application/octet-stream"))
                else if (operation == DocumentOperation.EXPORT_CHECKPOINT) png.launch("mebot-checkpoint.png")
                else create.launch("mebot-${operation.basename}-${LocalDate.now()}.json")
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null; currentFailure()
            } catch (_: SecurityException) {
                pending = null; currentFailure()
            } catch (_: IllegalStateException) {
                pending = null; currentFailure()
            }
        }
    } }
    LaunchedEffect(pending, selectedUri, locked, lifecycle, generation) {
        if (selectedUri != null && pending?.let { name -> DocumentOperation.entries.firstOrNull { it.name == name } }?.let { pendingTarget != currentIdentity(it) } == true) {
            pending = null; selectedUri = null
        }
        // A picker result can arrive before onResume, or while the lock screen is visible.
        // Keep it queued; never start an export or import behind the authentication gate.
        if (lifecycle == Lifecycle.State.RESUMED && !locked && currentAllowed()) {
            val operation = pending?.let { value -> DocumentOperation.entries.firstOrNull { it.name == value } }
            val uri = selectedUri
            if (operation != null && uri != null && pendingGeneration == generation) {
                pending = null; selectedUri = null
                // Local utility restore already received explicit confirmation before opening Files.
                if (operation == DocumentOperation.IMPORT_LOCAL) generation++
                currentDocument(operation, Uri.parse(uri), pendingTarget)
            }
        }
    }
    CompositionLocalProvider(LocalDocumentActions provides actions, LocalDocumentEpoch provides { generation }, content = content)
}

/** A comparison identity only, not a copy of checkpoint image bytes or its underlying code. */
internal fun checkpointDocumentIdentity(code: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
