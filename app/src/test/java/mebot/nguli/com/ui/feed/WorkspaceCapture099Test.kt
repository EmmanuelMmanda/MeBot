package mebot.nguli.com.ui.feed

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceCapture099Test {
    @Test fun recordingFinalizesToReadyAndRetainsPartialFlag() {
        val device = FakeCaptureDevice()
        val controller = WorkspaceCaptureController(device)
        controller.start()
        assertTrue(controller.state is WorkspaceCaptureState.Recording)

        val ticket = controller.beginFinish()!!
        assertEquals(WorkspaceCaptureState.Processing, controller.state)
        device.interrupted = true
        val result = controller.finish(ticket)
        val clip = (result as WorkspaceCaptureState.Ready).clip
        assertTrue(clip.partial)
        assertTrue(clip.file.isFile)
        assertEquals(WorkspaceCaptureState.Ready(clip), controller.state)
    }

    @Test fun cancellationDuringProcessingFencesStaleFinishAndDeletesUnsentTake() {
        val device = FakeCaptureDevice()
        val controller = WorkspaceCaptureController(device)
        controller.start()
        val stagingFile = (controller.state as WorkspaceCaptureState.Recording).file
        val ticket = controller.beginFinish()!!

        controller.cancelRecording()
        assertFalse(stagingFile.exists())
        assertEquals(WorkspaceCaptureState.Idle, controller.finish(ticket))
        assertEquals(WorkspaceCaptureState.Idle, controller.state)
    }

    @Test fun lateTranscriptCannotCrossDraftOwnerOrCancellationGeneration() {
        assertTrue(isWorkspaceTranscriptionCurrent(4, 4, "thread-a", "thread-a"))
        assertFalse(isWorkspaceTranscriptionCurrent(4, 4, "thread-a", "thread-b"))
        assertFalse(isWorkspaceTranscriptionCurrent(4, 5, "thread-a", "thread-a"))
        assertFalse(isWorkspaceCaptureOwnerCurrent("thread-a", "thread-b"))
    }

    @Test fun transcriptCompletionAppendsWithoutReplacingTextTypedSinceRecordingStarted() {
        val typedAfterRecording = "Keep this typed draft"
        assertEquals("Keep this typed draft\nRecognized words",
            mergeWorkspaceTranscriptIntoDraft(typedAfterRecording, "Recognized words"))
        assertEquals("Recognized words", mergeWorkspaceTranscriptIntoDraft(" \n ", "Recognized words"))
    }

    @Test fun recoverableContainerFailureIsExplicit() {
        val device = FakeCaptureDevice().apply { throwOnFinish = true }
        val controller = WorkspaceCaptureController(device)
        controller.start()
        val ticket = controller.beginFinish()!!
        val result = controller.finish(ticket)
        assertEquals(WorkspaceCaptureState.Failed(WorkspaceCaptureFailure.SAVE), result)
        assertNull(controller.finish(ticket).let { it as? WorkspaceCaptureState.Ready })
    }

    @Test fun cachePathValidationAcceptsOnlyFinalizedPrivateVoiceClips() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "voice-captures").apply { mkdirs() }
        val valid = File.createTempFile("voice-", ".m4a", directory).apply { writeBytes(ByteArray(1024)) }
        val truncated = File.createTempFile("voice-", ".m4a", directory).apply { writeBytes(ByteArray(8)) }
        val outside = File.createTempFile("voice-", ".m4a", context.cacheDir).apply { writeBytes(ByteArray(1024)) }

        val accepted = resolveWorkspaceClip(context, valid.absolutePath, 500L)
        assertEquals(valid.canonicalFile, accepted?.file?.canonicalFile)
        assertEquals(500L, accepted?.durationMillis)
        assertNull(resolveWorkspaceClip(context, outside.absolutePath, 500L))
        assertNull(resolveWorkspaceClip(context, File(directory, "../${outside.name}").path, 500L))
        assertEquals(WORKSPACE_CAPTURE_MAX_MILLIS,
            resolveWorkspaceClip(context, valid.absolutePath, WORKSPACE_CAPTURE_MAX_MILLIS * 3)?.durationMillis)
        deleteWorkspaceClipFile(context, truncated.absolutePath)
        assertFalse("Small corrupt temp clips should still be purged", truncated.exists())
        deleteWorkspaceClipFile(context, outside.absolutePath)
        assertTrue("Deletion must never escape the dedicated cache directory", outside.exists())
        outside.delete(); valid.delete()
    }

    @Test fun boundedOrphanCleanupPreservesActiveAndReferencedClipsAndDeletesStagingOrTruncatedFiles() {
        val context = RuntimeEnvironment.getApplication()
        purgeWorkspaceVoiceAudio(context)
        val directory = File(context.cacheDir, "voice-captures").apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun final(namePrefix: String = "voice-") = File.createTempFile(namePrefix, ".m4a", directory)
        fun staging() = File.createTempFile("recording-", ".pending.m4a", directory)
        fun aged(file: File, ageMillis: Long) {
            file.writeBytes(ByteArray(8))
            assertTrue("Fixture mtime must be set", file.setLastModified(now - ageMillis))
        }

        val truncatedOrphan = final().also { aged(it, 4L * 24L * 60L * 60L * 1000L) }
        val abandonedStage = staging().also { aged(it, 3L * 24L * 60L * 60L * 1000L) }
        val boundedRemainder = final().also { aged(it, 2L * 24L * 60L * 60L * 1000L) }
        val referenced = final().also { aged(it, 6L * 24L * 60L * 60L * 1000L) }
        val activeStage = staging().also { aged(it, 5L * 24L * 60L * 60L * 1000L) }
        val recent = final().also { aged(it, 60L * 60L * 1000L) }
        val unknown = File(directory, "not-owned.bin").apply { writeBytes(ByteArray(8)); setLastModified(now - 7L * 24L * 60L * 60L * 1000L) }

        val firstPass = cleanupOrphanedWorkspaceVoiceAudio(
            context,
            referencedPaths = setOf(referenced.absolutePath),
            activePath = activeStage.absolutePath,
            nowMillis = now,
            minimumAgeMillis = 24L * 60L * 60L * 1000L,
            maxDeletes = 2,
        )
        assertEquals("One pass cannot delete more than its explicit limit", 2, firstPass)
        assertFalse("Truncated abandoned final files are still purgeable", truncatedOrphan.exists())
        assertFalse("Old unreferenced staging files are purgeable", abandonedStage.exists())
        assertTrue("Active staging must survive even when old", activeStage.exists())
        assertTrue("Intentional referenced clips survive even when truncated/old", referenced.exists())
        assertTrue("The delete budget leaves additional orphans for a later pass", boundedRemainder.exists())
        assertTrue("Recent unreferenced clips are not considered orphaned yet", recent.exists())
        assertTrue("Unknown files in the directory are not owned audio", unknown.exists())

        assertEquals(1, cleanupOrphanedWorkspaceVoiceAudio(
            context,
            referencedPaths = setOf(referenced.absolutePath),
            activePath = activeStage.absolutePath,
            nowMillis = now,
            minimumAgeMillis = 24L * 60L * 60L * 1000L,
            maxDeletes = 2,
        ))
        assertFalse(boundedRemainder.exists())
        listOf(referenced, activeStage, recent, unknown).forEach { it.delete() }
    }

    @Test fun explicitErasePurgesAllOwnedFinalAndStagingAudioButNeverOutsideOrUnknownFiles() {
        val context = RuntimeEnvironment.getApplication()
        purgeWorkspaceVoiceAudio(context)
        val directory = File(context.cacheDir, "voice-captures").apply { mkdirs() }
        val finalized = File.createTempFile("voice-erase-", ".m4a", directory).apply { writeBytes(ByteArray(1024)) }
        val truncated = File.createTempFile("voice-erase-", ".m4a", directory).apply { writeBytes(ByteArray(8)) }
        val staging = File.createTempFile("recording-erase-", ".pending.m4a", directory).apply { writeBytes(ByteArray(8)) }
        val unknown = File(directory, "do-not-delete.bin").apply { writeBytes(ByteArray(16)) }
        val outside = File.createTempFile("voice-outside-", ".m4a", context.cacheDir).apply { writeBytes(ByteArray(1024)) }

        assertEquals(3, purgeWorkspaceVoiceAudio(context))
        assertFalse(finalized.exists())
        assertFalse("Explicit erase also purges truncated local captures", truncated.exists())
        assertFalse("Explicit erase also purges abandoned staging data", staging.exists())
        assertTrue("Unknown files must remain untouched", unknown.exists())
        assertTrue("Purge must not escape the dedicated cache directory", outside.exists())
        unknown.delete(); outside.delete()
    }

    @Test fun saveableReferenceRoundTripsWithoutPuttingAudioBytesInConversationState() {
        val file = File.createTempFile("voice-", ".m4a").apply { writeBytes(ByteArray(700)) }
        val encoded = encodeWorkspaceClip(WorkspaceClip(file, 12_345L, partial = true), transcriptApplied = true)
        val decoded = decodeWorkspaceClip(encoded)
        assertEquals(file.absolutePath, decoded?.path)
        assertEquals(12_345L, decoded?.durationMillis)
        assertTrue(decoded?.transcriptApplied == true)
        assertNull(decodeWorkspaceClip("bad\u001fpath"))
        file.delete()
    }

    private class FakeCaptureDevice : WorkspaceAudioCaptureDevice {
        private var current: File? = null
        var interrupted = false
        var throwOnFinish = false

        override fun start(): File = File.createTempFile("capture-test-", ".pending").also { current = it }
        override fun amplitude(): Float = 0.5f
        override fun finish(): WorkspaceCaptureFinish {
            if (throwOnFinish) error("finalize failed")
            val file = current ?: return WorkspaceCaptureFinish(null, interrupted)
            file.writeBytes(ByteArray(1024))
            current = null
            return WorkspaceCaptureFinish(file, interrupted)
        }
        override fun cancel() { current?.delete(); current = null }
        override fun delete(file: File) { file.delete() }
        override fun release() { }
    }
}
