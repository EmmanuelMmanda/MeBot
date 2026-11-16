package mebot.nguli.com.jobs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real WorkManager queue/constraints, with a fake worker body: never touches a provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JobsRefreshWorkManagerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private val starts = AtomicInteger()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                    if (workerClassName != JobsRefreshWorker::class.java.name) return null
                    return object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result {
                            starts.incrementAndGet()
                            return Result.success()
                        }
                    }
                }
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
    }

    @After fun tearDown() { manager.cancelAllWork().result.get(5, TimeUnit.SECONDS) }

    private fun records() = manager.getWorkInfosForUniqueWork(JobsRefreshScheduler.WORK_NAME).get(5, TimeUnit.SECONDS)

    @Test fun `repeated schedules keep a single unfinished request and wait for connectivity`() {
        JobsRefreshScheduler.schedule(context)
        val first = records().single { !it.state.isFinished }
        repeat(4) { JobsRefreshScheduler.schedule(context) }
        val pending = records().filterNot { it.state.isFinished }
        assertEquals(1, pending.size)
        assertEquals(first.id, pending.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, pending.single().state)
        assertEquals(0, starts.get())
        // The test driver is the only source of network-constraint satisfaction here.
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.SUCCEEDED, requireNotNull(manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS)).state)
        assertEquals(1, starts.get())
    }

    @Test fun `cancelling an offline queued refresh prevents execution and allows a new request`() {
        JobsRefreshScheduler.schedule(context)
        val first = records().single { !it.state.isFinished }
        JobsRefreshScheduler.cancel(context)
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS)).state)
        // Cancellation is persisted in WorkManager, not merely a UI spinner change.
        assertTrue(records().all { it.state.isFinished })
        assertEquals(0, starts.get())
        JobsRefreshScheduler.schedule(context)
        val next = records().single { !it.state.isFinished }
        assertNotEquals(first.id, next.id)
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(next.id)
        assertEquals(WorkInfo.State.SUCCEEDED, requireNotNull(manager.getWorkInfoById(next.id).get(5, TimeUnit.SECONDS)).state)
        assertEquals(1, starts.get())
    }
}
