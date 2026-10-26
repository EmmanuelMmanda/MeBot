package mebot.nguli.com.documents

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var request = 0
        var launches = 0
        var failure: RuntimeException? = null
        var intent: Intent? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            failure?.let { throw it }
            request = requestCode; launches++
            intent = contract.createIntent(org.robolectric.RuntimeEnvironment.getApplication(), input)
        }
        fun result(uri: String? = "content://synthetic/document") = dispatchResult(request,
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(Uri.parse(it)) })
    }
    private val registry = Registry()
    private val locked = mutableStateOf(false)
    private val attachmentTarget = mutableStateOf<String?>("synthetic-thread-one")
    private val received = mutableListOf<Pair<DocumentOperation, Uri>>()
    private var errors = 0
    private lateinit var actions: DocumentActions
    private fun mount(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                DocumentActionsHost(locked.value, { operation -> if (operation == DocumentOperation.IMPORT_ATTACHMENT) attachmentTarget.value else null }, { !locked.value }, { operation, uri, _ -> received += operation to uri }, { errors++ }) {
                    actions = LocalDocumentActions.current
                    if (!locked.value) {
                        val documents = LocalDocumentActions.current
                        androidx.compose.foundation.layout.Column {
                        Button(onClick = documents::exportCheckpoint) { Text("Checkpoint") }
                        Button(onClick = documents::importAttachment) { Text("Attach") }
                        Button(onClick = documents::importContext) { Text("Import") }
                        Button(onClick = documents::exportFeedBackup) { Text("Export") }
                        Button(onClick = documents::exportContext) { Text("Export context") }
                        Button(onClick = documents::importFeedBackup) { Text("Import feed") }
                        Button(onClick = documents::importLocalBackup) { Text("Import local") }
                        Button(onClick = documents::exportLocalBackup) { Text("Export local") }
                        }
                    } else Text("Locked")
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }
    @Test fun baselineUnguardedAndroidLauncherPropagatesMissingPickerException() {
        registry.failure = ActivityNotFoundException("Synthetic missing picker")
        val launcher = registry.register("baseline-unguarded", androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { }
        assertThrows(ActivityNotFoundException::class.java) { launcher.launch(arrayOf("application/json")) }
        launcher.unregister()
    }
    @Test fun launchFailuresAreVisibleAndRetryableWithoutCrashing() {
        mount()
        for (failure in listOf(ActivityNotFoundException(), SecurityException(), IllegalStateException())) {
            registry.failure = failure
            compose.onNodeWithText("Import").performClick()
            compose.waitForIdle()
        }
        assertEquals(3, errors)
        registry.failure = null
        compose.onNodeWithText("Import").performClick()
        assertEquals(1, registry.launches)
        compose.runOnUiThread { registry.result() }
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.IMPORT_CONTEXT, received.single().first)
    }
    @Test fun resultWaitsForUnlockAndOnlyDeliversOnce() {
        mount()
        compose.onNodeWithText("Export").performClick()
        compose.runOnUiThread { locked.value = true }
        compose.waitForIdle()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.runOnUiThread { locked.value = false }
        compose.waitForIdle()
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.EXPORT_FEED, received.single().first)
        compose.runOnUiThread { locked.value = true }; compose.waitForIdle()
        compose.runOnUiThread { locked.value = false }; compose.waitForIdle()
        assertEquals(1, received.size)
    }
    @Test fun pickerCancellationDoesNotImportAndAllowsAnotherOperation() {
        mount()
        compose.onNodeWithText("Import").performClick()
        compose.runOnUiThread { registry.result(null) }
        compose.waitForIdle()
        assertTrue(received.isEmpty()); assertEquals(0, errors)
        compose.onNodeWithText("Export").performClick()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.intent?.action)
        compose.runOnUiThread { registry.result() }
        compose.waitUntil { received.size == 1 }
    }
    @Test fun duplicateTapsCannotReplaceOutstandingOperation() {
        mount()
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Export").performClick()
        assertEquals(1, registry.launches)
        compose.runOnUiThread { registry.result() }
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.IMPORT_CONTEXT, received.single().first)
    }
    @Test fun queuedResultAndOperationSurviveSavedStateRecreationWhileLocked() {
        val restoration = StateRestorationTester(compose)
        mount(restoration)
        compose.onNodeWithText("Export").performClick()
        compose.runOnUiThread { locked.value = true; registry.result() }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(received.isEmpty())
        compose.runOnUiThread { locked.value = false }
        compose.waitForIdle()
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.EXPORT_FEED, received.single().first)
    }
    @Test fun allEightDocumentRoutesKeepTheirOperationAndContract() {
        mount()
        for ((label, operation) in listOf(
            "Checkpoint" to DocumentOperation.EXPORT_CHECKPOINT,
            "Attach" to DocumentOperation.IMPORT_ATTACHMENT,
            "Import" to DocumentOperation.IMPORT_CONTEXT,
            "Export context" to DocumentOperation.EXPORT_CONTEXT,
            "Import feed" to DocumentOperation.IMPORT_FEED,
            "Export" to DocumentOperation.EXPORT_FEED,
            "Import local" to DocumentOperation.IMPORT_LOCAL,
            "Export local" to DocumentOperation.EXPORT_LOCAL,
        )) {
            compose.onNodeWithText(label).performClick()
            assertEquals(if (operation.importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT, registry.intent?.action)
            if (operation == DocumentOperation.IMPORT_ATTACHMENT) assertArrayEquals(arrayOf("text/plain", "text/markdown", "text/x-markdown"), registry.intent!!.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            if (operation == DocumentOperation.EXPORT_CHECKPOINT) {
                assertEquals("image/png", registry.intent?.type)
                assertEquals("mebot-checkpoint.png", registry.intent?.getStringExtra(Intent.EXTRA_TITLE))
            } else if (!operation.importing) assertTrue(registry.intent!!.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("mebot-${operation.basename}-"))
            compose.runOnUiThread { registry.result() }
            compose.waitUntil { received.lastOrNull()?.first == operation }
        }
        assertEquals(8, received.size)
    }
    @Test fun inFlightPickerRegistrationSurvivesSavedStateRecreationBeforeResult() {
        val restoration = StateRestorationTester(compose)
        mount(restoration)
        compose.onNodeWithText("Import").performClick()
        compose.runOnUiThread { locked.value = true }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.runOnUiThread { locked.value = false }
        compose.waitForIdle()
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.IMPORT_CONTEXT, received.single().first)
    }
    @Test fun resetInvalidatesOutstandingPickerAndLateResultCannotBecomeNewOperation() {
        mount()
        compose.onNodeWithText("Import").performClick()
        compose.runOnUiThread { actions.invalidatePending() }
        compose.waitForIdle()
        compose.onNodeWithText("Export").performClick()
        assertEquals(1, registry.launches)
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.onNodeWithText("Export").performClick()
        compose.runOnUiThread { registry.result() }
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.EXPORT_FEED, received.single().first)
    }
    @Test fun resetDiscardsLockedQueuedResultAcrossRecreationAndAllowsFreshPicker() {
        val restoration = StateRestorationTester(compose)
        mount(restoration)
        compose.onNodeWithText("Export").performClick()
        compose.runOnUiThread { locked.value = true; registry.result() }
        compose.waitForIdle()
        compose.runOnUiThread { actions.invalidatePending() }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnUiThread { locked.value = false }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.onNodeWithText("Import").performClick()
        compose.runOnUiThread { registry.result() }
        compose.waitUntil { received.size == 1 }
        assertEquals(DocumentOperation.IMPORT_CONTEXT, received.single().first)
    }
    @Test fun attachmentReturnCannotBeAppliedToDifferentConversation() {
        mount()
        compose.onNodeWithText("Attach").performClick()
        compose.runOnUiThread { attachmentTarget.value = "synthetic-thread-two" }
        compose.waitForIdle()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.onNodeWithText("Attach").performClick()
        compose.runOnUiThread { locked.value = true; registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.runOnUiThread { locked.value = false }
        compose.waitForIdle()
        assertEquals(DocumentOperation.IMPORT_ATTACHMENT, received.single().first)
    }
    @Test fun resultDuringPickerPauseDoesNotRunUntilResumedWithLockOff() {
        mount()
        compose.onNodeWithText("Import").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertTrue(received.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { received.size == 1 }
    }
}
