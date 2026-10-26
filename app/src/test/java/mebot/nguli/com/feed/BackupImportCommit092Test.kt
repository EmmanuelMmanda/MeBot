package mebot.nguli.com.feed

import kotlinx.coroutines.*
import mebot.nguli.com.commitBackupImport
import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class BackupImportCommit092Test {
    @Test fun newerInvalidImportAfterDurableWriteCannotSkipAlarmReconciliation():Unit=runBlocking {
        val epoch=AtomicLong(1)
        val original=AppState(profile=Profile(name="Before"))
        val candidate=AppState(profile=Profile(name="Restored"))
        var stored=original
        var scheduleMatches=original
        val committed=CompletableDeferred<Unit>();val returnFromWrite=CompletableDeferred<Unit>()
        val first=async {
            commitBackupImport(candidate,1,epoch::get,
                update={ transform ->
                    stored=transform(stored) // Simulate durable DataStore write before its suspension returns.
                    committed.complete(Unit);returnFromWrite.await();stored
                },reconcileCommitted={ scheduleMatches=candidate })
        }
        committed.await()
        epoch.incrementAndGet() // A newer import starts, but decoding fails and therefore never commits.
        returnFromWrite.complete(Unit)
        assertTrue(first.await())
        assertEquals(candidate,stored)
        assertEquals(stored,scheduleMatches)
    }
    @Test fun rejectedStaleTransformDoesNotReconcileUncommittedCandidate():Unit=runBlocking {
        val current=AppState(profile=Profile(name="Erased replacement"))
        var stored=current
        var reconciled=false
        val applied=commitBackupImport(AppState(profile=Profile(name="Stale")),1,{2},
            update={transform -> transform(stored).also { stored=it }},
            reconcileCommitted={reconciled=true})
        assertFalse(applied)
        assertFalse(reconciled)
        assertEquals(current,stored)
    }
}
