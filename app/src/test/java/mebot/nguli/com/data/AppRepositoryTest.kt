package mebot.nguli.com.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.AppState
import mebot.nguli.com.core.BackupCodec
import mebot.nguli.com.core.DailyVisit
import mebot.nguli.com.core.FocusSession
import mebot.nguli.com.core.Outcome
import mebot.nguli.com.core.Profile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storageContext: Context
    private lateinit var storageDirectory: File
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        storageDirectory = temporaryFolder.newFolder("files")
        val application = ApplicationProvider.getApplicationContext<Context>()
        storageContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = storageDirectory
        }
    }

    @After
    fun tearDown() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun freshInstallationHasNoPersonalIdentityOrScheduledAlarm() = runBlocking {
        val state = newRepository().repository.current()
        assertEquals("", state.profile.name)
        assertEquals("", state.profile.intention)
        assertFalse(state.profile.onboardingComplete)
        assertTrue(state.visits.isEmpty())
        assertTrue(state.alarms.isEmpty())
        assertNull(state.focus)
    }

    @Test
    fun aCommittedVisitAndProfileSurviveRepositoryRecreation() = runBlocking {
        val original = newRepository()
        val expected = sampleState()
        original.repository.update { expected }
        original.job.cancelAndJoin()

        val restored = newRepository().repository.current()
        assertEquals(expected, restored)
        assertEquals("Tulia, kisha chagua hatua moja.\nKeep this note private.", restored.visits.single().note)
    }

    @Test
    fun exportedBackupPreservesDataWhileImportDisablesAlarmsAndClearsActiveFocus() = runBlocking {
        val handle = newRepository()
        val expected = sampleState().copy(
            alarms = listOf(AlarmPlan(id = "alarm-example", hour = 6, minute = 45, enabled = true)),
            focus = FocusSession(
                id = "focus-example",
                title = "Read one section",
                durationMinutes = 15,
                startedAtMillis = 1_760_000_000_000,
                endsAtMillis = 1_760_000_900_000,
            ),
        )
        handle.repository.update { expected }
        val backup = handle.repository.export()
        assertEquals(expected, BackupCodec.decode(backup))

        handle.repository.erase()
        val imported = handle.repository.import(backup)

        assertEquals(expected.profile, imported.profile)
        assertEquals(expected.visits, imported.visits)
        assertEquals(expected.settings, imported.settings)
        assertEquals(expected.alarms.single().copy(enabled = false), imported.alarms.single())
        assertNull(imported.focus)
        handle.job.cancelAndJoin()
        assertEquals(imported, newRepository().repository.current())
    }

    @Test
    fun malformedImportLeavesTheExistingStoredStateUntouched() = runBlocking {
        val repository = newRepository().repository
        repository.update { sampleState() }
        val before = repository.export()

        val failure = runCatching { repository.import("{\"schemaVersion\": 1, \"visits\": [") }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(before, repository.export())
    }

    @Test
    fun futureBackupVersionIsRejectedBeforeReplacingExistingRecords() = runBlocking {
        val repository = newRepository().repository
        repository.update { sampleState() }
        val before = repository.export()
        val future = before.replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")
        assertTrue(future != before)

        val failure = runCatching { repository.import(future) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, repository.export())
    }

    @Test
    fun semanticallyInvalidBackupDoesNotPartiallyReplaceData() = runBlocking {
        val repository = newRepository().repository
        repository.update { sampleState() }
        val before = repository.export()
        val invalid = before.replace("\"energy\": 3", "\"energy\": 99")
        assertTrue(invalid != before)

        val failure = runCatching { repository.import(invalid) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, repository.export())
    }

    @Test
    fun erasePersistsAnEmptyProfileAndHistory() = runBlocking {
        val handle = newRepository()
        handle.repository.update { sampleState() }
        assertEquals(AppState(), handle.repository.erase())
        handle.job.cancelAndJoin()

        assertEquals(AppState(), newRepository().repository.current())
    }

    @Test
    fun concurrentUpdatesDoNotLoseCommittedChanges() = runBlocking {
        val repository = newRepository().repository
        coroutineScope {
            repeat(40) {
                launch(Dispatchers.Default) {
                    repository.update { state ->
                        val count = state.profile.intention.toIntOrNull() ?: 0
                        state.copy(profile = state.profile.copy(intention = (count + 1).toString()))
                    }
                }
            }
        }
        assertEquals("40", repository.current().profile.intention)
    }

    @Test
    fun corruptedDiskDataFailsVisiblyWithoutOverwritingTheOriginalBytes() = runBlocking {
        val file = storageDirectory.resolve("mebot/state.json")
        file.parentFile!!.mkdirs()
        val corrupt = "{not valid stored data"
        file.writeText(corrupt)

        val failure = runCatching { newRepository().repository.current() }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(corrupt, file.readText())
    }

    @Test
    fun futureStoredSchemaFailsWithoutReplacingTheOriginalBytes() = runBlocking {
        val file = storageDirectory.resolve("mebot/state.json")
        file.parentFile!!.mkdirs()
        val unsupported = BackupCodec.encode(sampleState())
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")
        file.writeText(unsupported)

        val failure = runCatching { newRepository().repository.current() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(unsupported, file.readText())
    }

    private fun newRepository(): RepositoryHandle {
        val job = SupervisorJob()
        jobs += job
        return RepositoryHandle(AppRepository(storageContext, CoroutineScope(Dispatchers.IO + job)), job)
    }

    private fun sampleState(): AppState {
        val outcome = Outcome(id = "outcome-example", text = "Finish one useful section")
        val visit = DailyVisit(
            id = "visit-example",
            date = "2026-10-21",
            createdAtMillis = 1_760_000_000_000,
            updatedAtMillis = 1_760_000_060_000,
            arrival = "scattered",
            energy = 3,
            note = "Tulia, kisha chagua hatua moja.\nKeep this note private.",
            outcomes = listOf(outcome),
            selectedOutcomeId = outcome.id,
            nextStep = "Open the document and write the first paragraph",
            departure = "clearer",
            completedAtMillis = 1_760_000_060_000,
        )
        return AppState(
            profile = Profile(name = "Test reader", intention = "Make room for a clear next step", onboardingComplete = true),
            visits = listOf(visit),
        )
    }

    private data class RepositoryHandle(val repository: AppRepository, val job: Job)
}
