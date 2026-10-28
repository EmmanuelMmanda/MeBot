package mebot.nguli.com.feed

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.realtime.RealtimeBrokerConfig
import mebot.nguli.com.realtime.RealtimeBrokerSettingsStore
import mebot.nguli.com.realtime.RealtimeCapability
import mebot.nguli.com.realtime.RealtimeServerCapability
import mebot.nguli.com.realtime.RecordingTranscriptionCapability
import mebot.nguli.com.ui.feed.RealtimeSetup099UiState
import mebot.nguli.com.security.AppLockController
import mebot.nguli.com.security.LockRecord
import mebot.nguli.com.security.LockStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Realtime099ViewModelTestApplication::class)
class Realtime099ViewModelTest {
    private lateinit var app: Realtime099ViewModelTestApplication
    private lateinit var viewModels: ViewModelStore
    private var nextKey = 0

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedRepository.erase()
        viewModels = ViewModelStore()
    }

    @After fun tearDown() = runBlocking {
        if (::viewModels.isInitialized) viewModels.clear()
        if (::app.isInitialized) {
            app.appLock.foreground(true)
            if (app.appLock.state.value.enabled) {
                if (app.appLock.state.value.locked) app.appLock.unlock(TEST_PIN.toCharArray())
                app.appLock.disable(TEST_PIN.toCharArray())
            }
        }
        Dispatchers.resetMain()
    }

    @Test fun startupAndSaveNeverCallCapabilityOrChangeFeedState() = runBlocking {
        val feedBefore = app.feedRepository.current()
        val store = MemoryVoiceStore(app)
        var capabilityCalls = 0
        val vm = createViewModel(store) { config ->
            capabilityCalls++
            unavailableCapability
        }
        awaitReady(vm)
        assertEquals("Opening the settings screen only reads local storage", 0, capabilityCalls)

        vm.save(SERVICE_URL + "/", TEST_TOKEN)
        awaitUi(vm) { !it.isSaving && it.tokenConfigured }
        assertEquals(SERVICE_URL, store.read()!!.serviceUrl)
        assertEquals("Saving only writes the separate broker config", 1, store.writes.size)
        assertEquals(0, capabilityCalls)
        assertEquals("Voice setup never mutates the text/feed provider state", feedBefore, app.feedRepository.current())

        vm.checkCapability()
        awaitUi(vm) { !it.isChecking && it.capability != null }
        assertEquals("A network capability check occurs only after the explicit Test action", 1, capabilityCalls)
        assertEquals(feedBefore, app.feedRepository.current())
    }

    @Test fun blankTokenIsReusedOnlyForTheUnchangedNormalizedUrl() = runBlocking {
        val store = MemoryVoiceStore(app, RealtimeBrokerConfig.create(SERVICE_URL, TEST_TOKEN))
        var capabilityCalls = 0
        val vm = createViewModel(store) { capabilityCalls++; unavailableCapability }
        awaitReady(vm)

        vm.save("$SERVICE_URL/", null)
        awaitUi(vm) { !it.isSaving && it.tokenConfigured }
        assertEquals("The same normalized URL may retain its existing token", listOf(SERVICE_URL to TEST_TOKEN), store.writes)
        assertEquals(TEST_TOKEN, store.read()!!.serviceToken())

        vm.save(NEW_SERVICE_URL, null)
        awaitUi(vm) { !it.isSaving && it.errorCode == "invalid_input" }
        assertEquals("A changed URL cannot reuse the old token", listOf(SERVICE_URL to TEST_TOKEN), store.writes)
        assertEquals(SERVICE_URL, store.read()!!.serviceUrl)
        assertEquals(0, capabilityCalls)
    }

    @Test fun changedUrlNeedsNewTokenAndExplicitReplacementStaysLocal() = runBlocking {
        val feedBefore = app.feedRepository.current()
        val store = MemoryVoiceStore(app, RealtimeBrokerConfig.create(SERVICE_URL, TEST_TOKEN))
        var capabilityCalls = 0
        val vm = createViewModel(store) { capabilityCalls++; unavailableCapability }
        awaitReady(vm)

        vm.save(NEW_SERVICE_URL, null)
        awaitUi(vm) { !it.isSaving && it.errorCode == "invalid_input" }
        assertEquals(0, store.writes.size)
        assertEquals(SERVICE_URL, store.read()!!.serviceUrl)

        vm.save(NEW_SERVICE_URL, REPLACEMENT_TOKEN)
        awaitUi(vm) { !it.isSaving && it.savedServiceUrl == NEW_SERVICE_URL }
        assertEquals(listOf(NEW_SERVICE_URL to REPLACEMENT_TOKEN), store.writes)
        assertEquals(0, capabilityCalls)
        assertEquals("The text provider remains unchanged", feedBefore, app.feedRepository.current())
    }

    @Test fun lockingDuringExplicitCapabilityCheckIgnoresLateCompletion() = runBlocking {
        app.appLock.foreground(true)
        assertTrue(app.appLock.setup(TEST_PIN.toCharArray()))
        assertTrue(app.appLock.state.value.locked)
        assertTrue(app.appLock.unlock(TEST_PIN.toCharArray()))

        val store = MemoryVoiceStore(app, RealtimeBrokerConfig.create(SERVICE_URL, TEST_TOKEN))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<RealtimeServerCapability>()
        var capabilityCalls = 0
        val vm = createViewModel(store) {
            capabilityCalls++
            started.complete(Unit)
            response.await()
        }
        awaitReady(vm)

        vm.checkCapability()
        withTimeout(10_000) { started.await() }
        assertTrue(vm.ui.value.isChecking)
        assertEquals(1, capabilityCalls)

        app.appLock.lock()
        awaitUi(vm) { !it.isChecking }
        response.complete(RealtimeServerCapability(RealtimeCapability.Available, RecordingTranscriptionCapability.Available))
        delay(50)
        assertNull("A stale result after lock must not reappear", vm.ui.value.capability)
        assertNull(vm.ui.value.errorCode)
        assertFalse(vm.ui.value.isChecking)
    }

    @Test fun resetClearsOnlyDedicatedVoiceCredentialsWithoutAProbeOrFeedMutation() = runBlocking {
        val feedBefore = app.feedRepository.current()
        val store = MemoryVoiceStore(app, RealtimeBrokerConfig.create(SERVICE_URL, TEST_TOKEN))
        var capabilityCalls = 0
        val vm = createViewModel(store) { capabilityCalls++; unavailableCapability }
        awaitReady(vm)
        var clearedCallback = false
        var failureCallback = false
        val clips = java.io.File(app.cacheDir, "voice-captures").apply { mkdirs() }
        val newDraftClip = java.io.File(clips, "voice-new-draft.m4a").apply { writeBytes(ByteArray(1024)) }
        val abandonedCapture = java.io.File(clips, "recording-interrupted.pending.m4a").apply { writeBytes(ByteArray(12)) }

        vm.clearForReset(onCleared = { clearedCallback = true }, onFailure = { failureCallback = true }).join()

        assertTrue(clearedCallback)
        assertFalse("Erase must remove an unsent new-draft recording", newDraftClip.exists())
        assertFalse("Erase must remove interrupted recorder bytes", abandonedCapture.exists())
        assertFalse(failureCallback)
        assertEquals(1, store.clearCalls)
        assertNull(store.read())
        assertEquals(0, capabilityCalls)
        assertEquals("The voice settings owner does not mutate feed or text-provider state", feedBefore, app.feedRepository.current())
    }

    @Test fun backgroundCancellationDoesNotSuppressAnExplicitEraseContinuation() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : RealtimeBrokerSettingsStore(app) {
            override fun read(): RealtimeBrokerConfig? = null
            override fun clear() {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val vm = Realtime099ViewModel(app, store, RealtimeCapabilityProbe099 { unavailableCapability })
        viewModels.put("reset-barrier", vm)
        awaitReady(vm)
        var continuations = 0
        var failures = 0
        val reset = vm.clearForReset({ continuations++ }, { failures++ })
        try {
            withTimeout(10_000) { entered.await() }
            vm.cancelOperations() // Same privacy path as Activity.onPause / lock.
            assertTrue(vm.ui.value.isSaving)
            assertTrue("Repeated erase joins the existing transaction", reset === vm.clearForReset({ continuations++ }, { failures++ }))
        } finally { release.countDown() }
        withTimeout(10_000) { reset.join() }
        assertEquals(1, continuations)
        assertEquals(0, failures)
        assertFalse(vm.ui.value.isSaving)
    }

    private fun createViewModel(
        store: MemoryVoiceStore,
        capability: suspend (RealtimeBrokerConfig?) -> RealtimeServerCapability,
    ): Realtime099ViewModel {
        val vm = Realtime099ViewModel(app, store, RealtimeCapabilityProbe099(capability))
        viewModels.put("voice-test-${nextKey++}", vm)
        return vm
    }

    private suspend fun awaitReady(vm: Realtime099ViewModel) {
        awaitUi(vm) { !it.isSaving }
    }

    private suspend fun awaitUi(vm: Realtime099ViewModel, predicate: (RealtimeSetup099UiState) -> Boolean) {
        withTimeout(10_000) { vm.ui.first(predicate) }
    }

    private class MemoryVoiceStore(context: Context, initial: RealtimeBrokerConfig? = null) : RealtimeBrokerSettingsStore(context) {
        @Volatile private var value: RealtimeBrokerConfig? = initial
        val writes = mutableListOf<Pair<String, String>>()
        var clearCalls = 0
            private set
        @Synchronized override fun read(): RealtimeBrokerConfig? = value
        @Synchronized override fun write(serviceUrl: String, serviceToken: String) {
            writes += serviceUrl to serviceToken
            value = RealtimeBrokerConfig.create(serviceUrl, serviceToken)
        }
        @Synchronized override fun clear() {
            clearCalls++
            value = null
        }
    }

    companion object {
        private const val TEST_PIN = "482619"
        private const val SERVICE_URL = "https://voice.example.test"
        private const val NEW_SERVICE_URL = "https://new-voice.example.test"
        private const val TEST_TOKEN = "synthetic-mebot-service-token-for-tests-0001"
        private const val REPLACEMENT_TOKEN = "another-synthetic-service-token-for-tests-0002"
        private val unavailableCapability = RealtimeServerCapability(
            RealtimeCapability.Unavailable(RealtimeCapability.Reason.TRANSPORT_NOT_AVAILABLE),
            RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED),
        )
    }
}

class Realtime099ViewModelTestApplication : MeBotApplication() {
    override val appLock by lazy {
        AppLockController(RealtimeLockMemoryStorage(), elapsed = { 100_000L }, boot = { 7 }, deviceLocked = { false })
    }
}

private class RealtimeLockMemoryStorage : LockStorage {
    private var record: LockRecord? = null
    override fun enabled() = record != null
    override fun read() = record
    override fun write(record: LockRecord) { this.record = record }
    override fun disable() { record = null }
}
