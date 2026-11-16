package mebot.nguli.com.jobs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.feed.FeedTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FeedTestApplication::class)
class JobsViewModelTest {
    private lateinit var app: FeedTestApplication
    private val models = ViewModelStore()

    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.jobsRepository.erase()
        app.jobsRepository.update { it.copy(jobs = listOf(JobPosting(id = "test:1", title = "Engineer"))) }
    }

    @After fun close() {
        models.clear()
        Dispatchers.resetMain()
    }

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle()): JobsViewModel {
        val vm = JobsViewModel(app, handle)
        models.put("jobs", vm)
        withTimeout(5_000) { vm.ui.first { it.ready } }
        return vm
    }

    @Test fun restoredPendingExportIsInterruptedNotSuccessfulAndDoesNotResumeWriting(): Unit = runBlocking {
        val output = java.io.File(app.cacheDir, "interrupted-jobs-export.pdf").apply { writeText("original") }
        val handle = SavedStateHandle(mapOf("jobs.exportPending" to true))
        val vm = model(handle)
        assertEquals("interrupted", vm.ui.value.exportResult)
        assertFalse(vm.ui.value.exportBusy)
        assertEquals(false, handle.get<Boolean>("jobs.exportPending"))
        // No exportPdf call or destination is supplied on restoration: only a user retry can write.
        assertEquals("original", output.readText())
        vm.clearExportResult()
        assertEquals("", vm.ui.value.exportResult)
        assertEquals("original", output.readText())
    }

    @Test fun repeatedSaveWhileFirstWritePendingIsIdempotent(): Unit = runBlocking {
        val vm = model()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) {
            app.feedRepository.withJobsConsistency { entered.complete(Unit); release.await() }
        }
        withTimeout(5_000) { entered.await() }
        try {
            vm.save("test:1")
            vm.save("test:1")
            vm.save("test:1")
            assertTrue(vm.ui.value.saving)
        } finally { release.complete(Unit) }
        gate.join()
        withTimeout(5_000) { vm.ui.first { !it.saving && it.state.applications.size == 1 } }
        assertEquals(listOf("test:1"), app.jobsRepository.current().applications.map { it.jobId })
    }

    @Test fun finalPreferencesQueuedAfterEarlierEditsWinAndDraftIsPreserved(): Unit = runBlocking {
        val vm = model()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) {
            app.feedRepository.withJobsConsistency { entered.complete(Unit); release.await() }
        }
        withTimeout(5_000) { entered.await() }
        val final = JobPreferences(roles = "Engineer", skills = "Kotlin", onboarded = true)
        try {
            vm.preferences(JobPreferences(roles = "Old draft"))
            vm.application(JobApplication(jobId = "test:1", draft = "Reviewed application"))
            vm.preferences(final)
        } finally { release.complete(Unit) }
        gate.join()
        withTimeout(5_000) { vm.ui.first { !it.saving && it.state.preferences == final } }
        val stored = app.jobsRepository.current()
        assertEquals(final, stored.preferences)
        assertEquals("Reviewed application", stored.applications.single().draft)
        assertNull(vm.ui.value.error)
    }
}
