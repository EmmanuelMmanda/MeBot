package app.mynguli.alarm

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.AlarmPlan
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AlarmGrantResolverShadow::class])
class AlarmAudioAccessTest {
    private lateinit var context: Context
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlarmGrantResolverShadow.failTake = false
        AlarmGrantResolverShadow.failRelease = false
        AlarmStore(context).clear()
        AlarmAudioAccess.forgetDrafts(context)
        context.createDeviceProtectedStorageContext().getSharedPreferences("mebot_alarm_audio_access_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun cleanUp() {
        AlarmGrantResolverShadow.failRelease = false
        AlarmAudioAccess.forgetDrafts(context)
        AlarmStore(context).clear()
        AlarmAudioAccess.prune(context, emptyList())
    }

    @Test fun onlyNewReadGrantsAreOwnedAndUnrelatedWriteAccessSurvivesCleanup() {
        val unrelated = uri("unrelated")
        val shared = uri("shared-write")
        context.contentResolver.takePersistableUriPermission(unrelated, read or write)
        context.contentResolver.takePersistableUriPermission(shared, write)
        assertTrue(AlarmAudioAccess.take(context, unrelated, read))
        assertTrue(AlarmAudioAccess.take(context, shared, read or write))
        AlarmAudioAccess.forgetDrafts(context)
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(unrelated))
        assertTrue(canWrite(unrelated))
        assertFalse(canRead(shared))
        assertTrue(canWrite(shared))
    }

    @Test fun invalidUrisMissingReadFlagsAndProviderRefusalDoNotClaimAccess() {
        assertFalse(AlarmAudioAccess.take(context, Uri.parse("https://example.com/alarm.wav"), read))
        assertFalse(AlarmAudioAccess.take(context, Uri.parse("file:///private/alarm.wav"), read))
        assertFalse(AlarmAudioAccess.take(context, uri("missing-read"), write))
        AlarmGrantResolverShadow.failTake = true
        assertFalse(AlarmAudioAccess.take(context, uri("refused"), read))
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test fun aGrantStaysUntilEverySavedPlanAndRuntimeReferenceIsGone() {
        val selected = uri("reused")
        assertTrue(AlarmAudioAccess.take(context, selected, read))
        AlarmAudioAccess.forgetDrafts(context)
        val one = plan("one", selected)
        val two = plan("two", selected)
        AlarmAudioAccess.prune(context, listOf(one, two))
        AlarmAudioAccess.prune(context, listOf(two))
        assertTrue(canRead(selected))
        val store = AlarmStore(context)
        store.session = AlarmSession(plan = two, occurrenceId = "ringing", startedAtMillis = 1)
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(selected))
        store.session = null
        store.pending = listOf(ScheduledAlarm("pending", 2, two))
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(selected))
        store.pending = emptyList()
        store.scheduled = listOf(ScheduledAlarm("next", 3, two))
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(selected))
        store.scheduled = emptyList()
        AlarmAudioAccess.prune(context, emptyList())
        assertFalse(canRead(selected))
    }

    @Test fun rotationAndPickerDisposalKeepDraftAccessUntilItIsExplicitlyFinished() {
        val selected = uri("draft")
        val first = AlarmAudioAccess.beginDraft("", "stable-editor")
        first.replace(selected.toString())
        assertTrue(AlarmAudioAccess.take(context, selected, read))
        first.close()
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(selected))
        val restored = AlarmAudioAccess.beginDraft(selected.toString(), "stable-editor")
        // An old composition's repeated disposal must not release the replacement lease.
        first.close()
        AlarmAudioAccess.pruneAt(context, emptyList(), later())
        assertTrue(canRead(selected))
        restored.discard()
        AlarmAudioAccess.prune(context, emptyList())
        assertFalse(canRead(selected))
    }

    @Test fun canceledDraftsAndUnreferencedPickerGrantsExpireWithoutLeaking() {
        val abandoned = uri("abandoned")
        val selected = uri("replacement")
        val draft = AlarmAudioAccess.beginDraft(abandoned.toString(), "replace-editor")
        assertTrue(AlarmAudioAccess.take(context, abandoned, read))
        draft.replace(selected.toString())
        assertTrue(AlarmAudioAccess.take(context, selected, read))
        AlarmAudioAccess.pruneAt(context, emptyList(), later())
        assertFalse(canRead(abandoned))
        assertTrue(canRead(selected))
        draft.close()
        AlarmAudioAccess.pruneAt(context, emptyList(), later() + 2 * AlarmAudioAccess.RESTORE_GRACE_MILLIS)
        assertFalse(canRead(selected))
    }

    @Test fun failedRevocationIsBestEffortAndRetriedOnTheNextReconciliation() {
        val selected = uri("retry")
        assertTrue(AlarmAudioAccess.take(context, selected, read))
        AlarmAudioAccess.forgetDrafts(context)
        AlarmGrantResolverShadow.failRelease = true
        AlarmAudioAccess.prune(context, emptyList())
        assertTrue(canRead(selected))
        AlarmGrantResolverShadow.failRelease = false
        AlarmAudioAccess.prune(context, emptyList())
        assertFalse(canRead(selected))
    }

    private fun uri(name: String) = Uri.parse("content://example.audio/document/$name")
    private fun plan(id: String, uri: Uri) = AlarmPlan(id = id, soundId = "custom", soundUri = uri.toString())
    private fun canRead(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    private fun canWrite(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    private fun later() = System.currentTimeMillis() + 3 * AlarmAudioAccess.RESTORE_GRACE_MILLIS
}

@Implements(ContentResolver::class)
class AlarmGrantResolverShadow : ShadowContentResolver() {
    companion object {
        var failTake = false
        var failRelease = false
    }
    @Implementation
    public override fun takePersistableUriPermission(uri: Uri, flags: Int) {
        if (failTake) throw SecurityException("Synthetic provider rejected persistent access")
        super.takePersistableUriPermission(uri, flags)
    }
    @Implementation
    public override fun releasePersistableUriPermission(uri: Uri, flags: Int) {
        if (failRelease) throw SecurityException("Synthetic revocation failure")
        super.releasePersistableUriPermission(uri, flags)
    }
}
