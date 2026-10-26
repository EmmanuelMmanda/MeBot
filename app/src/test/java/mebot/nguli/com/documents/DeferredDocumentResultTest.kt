package mebot.nguli.com.documents

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
class DeferredDocumentResultTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var request = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { request = requestCode }
        fun malformedRingtone() = dispatchResult(request, Activity.RESULT_OK,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.os.Bundle()))
        fun result(code: Int = Activity.RESULT_OK) = dispatchResult(request, code,
            Intent().setData(Uri.parse("content://synthetic/audio"))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.parse("content://synthetic/ringtone"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    private val registry = Registry()
    private val unlocked = mutableStateOf(true)
    private val owner = mutableStateOf("synthetic-original")
    private val epoch = mutableLongStateOf(0)
    private val results = mutableListOf<ActivityResult>()
    private lateinit var launchAction: (Intent) -> Unit
    private var ringtoneContract = false
    private var handler: (ActivityResult) -> Unit = {}
    private fun mount(gated: Boolean = false, restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry, LocalDocumentEpoch provides { epoch.longValue }) {
                val holder = rememberSaveableStateHolder()
                if (!gated || unlocked.value) holder.SaveableStateProvider("specialized") {
                    val launch = rememberDeferredDocumentLauncher(owner.value, { unlocked.value }) { results += it; handler(it) }
                    launchAction = launch
                    Button(onClick = { launch(Intent(if (ringtoneContract) RingtoneManager.ACTION_RINGTONE_PICKER else Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")) }) { Text("Choose") }
                } else Text("Locked")
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }
    @Test fun retainedCompositionCannotDeliverWhileLockedAndPreservesAudioGrantMetadata() {
        ringtoneContract = true
        mount()
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { unlocked.value = false; registry.result() }
        compose.waitForIdle(); assertTrue(results.isEmpty())
        compose.runOnUiThread { unlocked.value = true }
        compose.waitForIdle()
        val result = results.single()
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals("content://synthetic/audio", result.data?.data.toString())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, result.data!!.flags)
        @Suppress("DEPRECATION")
        val ringtone = result.data!!.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        assertEquals("content://synthetic/ringtone", ringtone.toString())
    }
    @Test fun gatedDisposalReRegistersPendingResultAfterUnlock() {
        mount(gated = true)
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { unlocked.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle(); assertTrue(results.isEmpty())
        compose.runOnUiThread { unlocked.value = true }
        compose.waitForIdle()
        assertEquals(Activity.RESULT_OK, results.single().resultCode)
    }
    @Test fun queuedResultSurvivesRecreationAndWaitsForForeground() {
        val restoration = StateRestorationTester(compose)
        mount(restoration = restoration)
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { unlocked.value = false; registry.result() }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { unlocked.value = true }
        compose.waitForIdle(); assertTrue(results.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, results.size)
    }
    @Test fun changedOwnerOrResetCancelsInsteadOfWritingOrApplyingAudio() {
        mount()
        for (reset in listOf(false, true)) {
            compose.onNodeWithText("Choose").performClick()
            compose.runOnUiThread {
                unlocked.value = false
                if (reset) epoch.longValue++ else owner.value = "synthetic-replacement"
                registry.result()
            }
            compose.waitForIdle()
            val before = results.size
            compose.runOnUiThread { unlocked.value = true }
            compose.waitForIdle()
            assertEquals(before + 1, results.size)
            assertEquals(Activity.RESULT_CANCELED, results.last().resultCode)
            assertNull(results.last().data)
        }
    }
    @Test fun lateDownloadCompletionWhilePausedRejectsLaunchForCallerCleanup() {
        mount()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread {
            assertThrows(IllegalStateException::class.java) { launchAction(Intent(Intent.ACTION_CREATE_DOCUMENT)) }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertEquals(1, results.size)
    }
    @Test fun malformedRingtoneMetadataDoesNotCrashOrApplySelection() {
        ringtoneContract = true
        mount()
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { registry.malformedRingtone() }
        compose.waitForIdle()
        assertEquals(Activity.RESULT_CANCELED, results.single().resultCode)
        assertNull(results.single().data)
    }
    @Test fun staleResourceResultTriggersCleanupWithoutWritingOutput() {
        val temp = java.io.File.createTempFile("synthetic-resource", ".txt").apply { writeText("Synthetic resource bytes") }
        val output = java.io.File(temp.parentFile, temp.name + ".output")
        handler = { result ->
            if (result.resultCode == Activity.RESULT_OK) temp.copyTo(output)
            temp.delete()
        }
        try {
            mount(gated = true)
            compose.onNodeWithText("Choose").performClick()
            compose.runOnUiThread { unlocked.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { registry.result(); epoch.longValue++ }
            compose.waitForIdle()
            assertTrue(temp.exists()); assertFalse(output.exists())
            compose.runOnUiThread { unlocked.value = true }
            compose.waitForIdle()
            assertFalse(temp.exists()); assertFalse(output.exists())
        } finally { temp.delete(); output.delete() }
    }
    @Test fun cancellationReturnsWithoutDataAndFreshSelectionStillWorks() {
        mount()
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { registry.result(Activity.RESULT_CANCELED) }
        compose.waitForIdle()
        assertEquals(Activity.RESULT_CANCELED, results.single().resultCode)
        compose.onNodeWithText("Choose").performClick()
        compose.runOnUiThread { registry.result() }
        compose.waitForIdle()
        assertEquals(Activity.RESULT_OK, results.last().resultCode)
    }
}
