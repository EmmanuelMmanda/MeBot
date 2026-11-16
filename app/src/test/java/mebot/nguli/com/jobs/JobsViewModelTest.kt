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
    @Test fun freshSubmissionFromPreResetEditorIsRejectedAfterNewStateArrives(): Unit = runBlocking {
        val vm = model()
        val displayedScope = vm.ui.value.state.editingScope
        app.jobsRepository.erase()
        withTimeout(5_000) { vm.ui.first { it.state.editingScope != displayedScope } }
        vm.application(JobApplication(jobId = "old", draft = "Pre-reset draft"), displayedScope)
        withTimeout(5_000) { vm.ui.first { !it.saving && it.error == "state_changed" } }
        assertTrue(app.jobsRepository.current().applications.isEmpty())
        assertEquals("", app.jobsRepository.current().preferences.roles)
    }
    @Test fun assistantHandoffAcknowledgesUnsavedApplicationBeforeCallback(): Unit = runBlocking {
        val vm = model()
        val application = JobApplication("test:1", status = "preparing", notes = "New notes", draft = "Unsaved reviewed draft")
        val observed = CompletableDeferred<JobApplication>()
        vm.prepareAssistant(application, vm.ui.value.state.editingScope) {
            observed.complete(vm.ui.value.state.applications.single())
        }
        val acknowledged = withTimeout(5_000) { observed.await() }
        assertEquals(application.notes, acknowledged.notes)
        assertEquals(application.draft, acknowledged.draft)
        assertEquals(application.status, acknowledged.status)
        assertEquals(acknowledged, app.jobsRepository.current().applications.single())
        withTimeout(5_000) { vm.ui.first { !it.saving } }
        assertNull(vm.ui.value.error)
    }

    @Test fun duplicatePendingAssistantHandoffInvokesCallbackOnlyOnce(): Unit = runBlocking {
        val vm = model()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) {
            app.feedRepository.withJobsConsistency { entered.complete(Unit); release.await() }
        }
        withTimeout(5_000) { entered.await() }
        val count = java.util.concurrent.atomic.AtomicInteger()
        try {
            val scope = vm.ui.value.state.editingScope
            vm.prepareAssistant(JobApplication("test:1", draft = "First reviewed draft"), scope) { count.incrementAndGet() }
            vm.prepareAssistant(JobApplication("test:1", draft = "Duplicate must not replace first"), scope) { count.incrementAndGet() }
            assertEquals(0, count.get())
        } finally { release.complete(Unit) }
        gate.join()
        withTimeout(5_000) { vm.ui.first { !it.saving } }
        assertEquals(1, count.get())
        assertEquals("First reviewed draft", app.jobsRepository.current().applications.single().draft)
    }

    @Test fun staleScopeRejectsAssistantHandoffEvenWithoutApplicationWrite(): Unit = runBlocking {
        val vm = model()
        val oldScope = vm.ui.value.state.editingScope
        app.jobsRepository.erase()
        withTimeout(5_000) { vm.ui.first { it.state.editingScope != oldScope } }
        val count = java.util.concurrent.atomic.AtomicInteger()
        vm.prepareAssistant(null, oldScope) { count.incrementAndGet() }
        withTimeout(5_000) { vm.ui.first { !it.saving && it.error == "state_changed" } }
        assertEquals(0, count.get())
        vm.prepareAssistant(JobApplication("test:1", draft = "Old draft"), oldScope) { count.incrementAndGet() }
        withTimeout(5_000) { vm.ui.first { !it.saving && it.error == "state_changed" } }
        assertEquals(0, count.get())
        assertTrue(app.jobsRepository.current().applications.isEmpty())
    }

    @Test fun failedDurableWriteNeverHandsOffAndCanRetry(): Unit = runBlocking {
        val vm = model()
        val before = app.jobsRepository.current()
        val count = java.util.concurrent.atomic.AtomicInteger()
        // The real serializer refuses files over 16 MiB, without replacing the previous file.
        vm.prepareAssistant(JobApplication("test:1", draft = "x".repeat(17 * 1024 * 1024)), before.editingScope) {
            count.incrementAndGet()
        }
        withTimeout(10_000) { vm.ui.first { !it.saving && it.error == "storage_error" } }
        assertEquals(0, count.get())
        assertEquals(before, app.jobsRepository.current())
        vm.prepareAssistant(JobApplication("test:1", draft = "Bounded retry"), before.editingScope) { count.incrementAndGet() }
        withTimeout(5_000) { vm.ui.first { !it.saving && it.state.applications.singleOrNull()?.draft == "Bounded retry" } }
        assertEquals(1, count.get())
    }

}
