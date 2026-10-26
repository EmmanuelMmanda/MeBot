package mebot.nguli.com.feed

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CredentialPresenceTest {
    private fun delayedSnapshotCannotReplaceDefinitive(initialSnapshot: Boolean, definitive: Boolean) {
        val presence=CredentialPresence()
        val captured=CountDownLatch(1)
        val release=CountDownLatch(1)
        val done=CountDownLatch(1)
        val reader=thread {
            val epoch=presence.snapshotEpoch()
            captured.countDown()
            check(release.await(5,TimeUnit.SECONDS))
            presence.publishSnapshot(epoch,initialSnapshot)
            done.countDown()
        }
        try {
            assertTrue(captured.await(5,TimeUnit.SECONDS))
            presence.definitive(definitive)
            release.countDown()
            assertTrue(done.await(5,TimeUnit.SECONDS))
            assertEquals(definitive,presence.state.value)
        } finally { release.countDown();reader.join(5000) }
    }
    @Test fun delayedAbsentStartupSnapshotCannotUndoSuccessfulConnect() =
        delayedSnapshotCannotReplaceDefinitive(false,true)
    @Test fun delayedPresentStartupSnapshotCannotUndoDisconnectResetOrImportClear() =
        delayedSnapshotCannotReplaceDefinitive(true,false)
    @Test fun unchangedEpochAcceptsStartupPresence() {
        val presence=CredentialPresence()
        presence.publishSnapshot(presence.snapshotEpoch(),true)
        assertTrue(presence.state.value)
    }
}
