package mebot.nguli.com.jobs

import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test

class JobsRefreshSchedulerTest {
    @Test fun `waiting states distinguish offline queue and retry`() {
        assertEquals(JobsRefreshPhase.IDLE, JobsRefreshScheduler.phase(null, true, 0))
        assertEquals(JobsRefreshPhase.QUEUED, JobsRefreshScheduler.phase(WorkInfo.State.ENQUEUED, true, 0))
        assertEquals(JobsRefreshPhase.OFFLINE, JobsRefreshScheduler.phase(WorkInfo.State.ENQUEUED, false, 0))
        assertEquals(JobsRefreshPhase.RETRYING, JobsRefreshScheduler.phase(WorkInfo.State.ENQUEUED, true, 1))
        assertEquals(JobsRefreshPhase.OFFLINE, JobsRefreshScheduler.phase(WorkInfo.State.ENQUEUED, false, 1))
    }
    @Test fun `terminal states are not overwritten by network state`() {
        assertEquals(JobsRefreshPhase.SUCCESS, JobsRefreshScheduler.phase(WorkInfo.State.SUCCEEDED, false, 0))
        assertEquals(JobsRefreshPhase.FAILURE, JobsRefreshScheduler.phase(WorkInfo.State.FAILED, false, 3))
        assertEquals(JobsRefreshPhase.CANCELLED, JobsRefreshScheduler.phase(WorkInfo.State.CANCELLED, false, 0))
        assertEquals(JobsRefreshPhase.RUNNING, JobsRefreshScheduler.phase(WorkInfo.State.RUNNING, true, 1))
    }
    @Test fun `only transient source failures are retried within the bound`() {
        assertTrue(JobsRefreshWorker.shouldRetry("offline", 0))
        assertTrue(JobsRefreshWorker.shouldRetry("provider_error", 2))
        assertFalse(JobsRefreshWorker.shouldRetry("provider_error", 3))
        assertFalse(JobsRefreshWorker.shouldRetry("storage_error", 0))
        assertFalse(JobsRefreshWorker.shouldRetry("cached", 0))
    }
}
