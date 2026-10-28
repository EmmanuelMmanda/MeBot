package mebot.nguli.com.feed

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mebot.nguli.com.core.AiConnection
import mebot.nguli.com.realtime.RealtimeBrokerClient
import mebot.nguli.com.realtime.RealtimeBrokerConfig
import mebot.nguli.com.realtime.RealtimeBrokerSettingsStore
import mebot.nguli.com.realtime.RealtimeCallPhase
import mebot.nguli.com.realtime.RealtimeDataChannelState
import mebot.nguli.com.realtime.RealtimePeerCallbacks
import mebot.nguli.com.realtime.RealtimePeerConnection
import mebot.nguli.com.realtime.RealtimePeerConnectionFactory
import mebot.nguli.com.realtime.RealtimePeerLinkState
import mebot.nguli.com.realtime.RealtimeSessionContext
import mebot.nguli.com.realtime.RealtimeTransportException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Realtime099ViewModelTestApplication::class)
class RealtimeCall099ViewModelTest {
    private lateinit var app: Realtime099ViewModelTestApplication
    private lateinit var store: ViewModelStore
    private var viewModelId = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.appLock.foreground(true)
        if (app.appLock.state.value.locked) runBlocking { app.appLock.unlock(TEST_PIN.toCharArray()) }
        store = ViewModelStore()
    }

    @After fun tearDown() = runBlocking {
        if (::store.isInitialized) store.clear()
        if (::app.isInitialized) {
            app.appLock.foreground(true)
            if (app.appLock.state.value.enabled) {
                if (app.appLock.state.value.locked) app.appLock.unlock(TEST_PIN.toCharArray())
                app.appLock.disable(TEST_PIN.toCharArray())
            }
        }
        Dispatchers.resetMain()
    }

    @Test fun stalePermissionOrHostGrantCannotCreateMediaOrCall() = runBlocking {
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)

        // MainActivity invokes start only after the explicit permission result. A grant that
        // arrives after the page/context is stale is rejected before a peer or service request.
        vm.start(snapshot(), factory) { false }
        assertTrue(factory.peers.isEmpty())
        assertEquals(0, backend.createCalls.get())
        assertEquals(0, backend.endCalls.get())
        store.clear()
    }

    @Test fun microphonePermissionRevokedBeforeMediaCaptureFailsClosedWithoutSdpRequest() = runBlocking {
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory(permissionRevokedBeforeMedia = true)
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        withTimeout(TEST_TIMEOUT) { vm.ui.first { it.snapshot.phase == RealtimeCallPhase.FAILED } }

        assertEquals("microphone_permission_required", vm.ui.value.snapshot.reasonCode)
        assertFalse(peer.microphoneActive)
        withTimeout(TEST_TIMEOUT) { waitFor { peer.closed && backend.endCalls.get() >= 1 } }
        assertEquals("Revoked permission prevents all SDP/provider requests", 0, backend.createCalls.get())
        assertTrue(backend.endCalls.get() >= 1)
        store.clear()
    }

    @Test fun stoppingDuringMediaSetupImmediatelyMutesAndPreventsSdpDispatch() = runBlocking {
        val gate = StageGate("addMicrophoneTrack")
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory(gate)
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        withTimeout(TEST_TIMEOUT) { gate.entered.await() }
        assertTrue(peer.microphoneActive)

        vm.stop("context_changed")
        assertFalse("Local microphone must stop synchronously before cleanup/network", peer.microphoneActive)
        assertEquals(1, peer.privacyStopCalls)
        // Local teardown precedes asynchronous broker hangup; observe both within one budget.
        withTimeout(TEST_TIMEOUT) { waitFor { peer.closed && backend.endCalls.get() >= 1 } }
        assertEquals("No broker call may be dispatched before media setup completes", 0, backend.createCalls.get())
        assertTrue("The server receives an end tombstone for the pending request", backend.endCalls.get() >= 1)
        assertFalse(vm.ui.value.snapshot.isConnected)
        store.clear()
    }

    @Test fun stopWhileBrokerSdpRequestIsInFlightDisablesMicBeforeHangupAndIgnoresAnswer() = runBlocking {
        val callGate = BlockingGate()
        val backend = FakeBrokerBackend(createGate = callGate)
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        withTimeout(TEST_TIMEOUT) { callGate.entered.await() }
        assertEquals(1, backend.createCalls.get())
        assertTrue(peer.microphoneActive)

        vm.stop("host_background_or_lock")
        assertFalse("Privacy stop must not wait for broker response", peer.microphoneActive)
        assertEquals(1, peer.privacyStopCalls)
        callGate.release()
        awaitClosed(peer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }
        assertEquals("The stale answer is never applied", 0, peer.remoteDescriptionCalls)
        assertFalse(vm.ui.value.snapshot.isConnected)
        store.clear()
    }

    @Test fun stopDuringRemoteSdpIgnoresLateTranscriptAndLeavesNoLiveCall() = runBlocking {
        val gate = StageGate("setRemoteDescription")
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory(gate)
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        withTimeout(TEST_TIMEOUT) { gate.entered.await() }
        vm.stop("context_changed")
        assertFalse(peer.microphoneActive)
        assertEquals(1, peer.privacyStopCalls)

        peer.callbacks.onDataChannelText(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"stale","delta":"private transcript"}""",
        )
        gate.release.complete(Unit)
        awaitClosed(peer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }
        delay(30)
        assertTrue("Late transcript events from the ended generation are ignored", vm.ui.value.recentEvents.isEmpty())
        assertFalse(vm.ui.value.snapshot.isConnected)
        store.clear()
    }

    @Test fun appLockStopsAnActiveCallImmediatelyAndSchedulesServerEnd() = runBlocking {
        app.appLock.foreground(true)
        if (!app.appLock.state.value.enabled) assertTrue(app.appLock.setup(TEST_PIN.toCharArray()))
        if (app.appLock.state.value.locked) assertTrue(app.appLock.unlock(TEST_PIN.toCharArray()))
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        awaitConnected(vm)

        app.appLock.lock()
        withTimeout(TEST_TIMEOUT) { waitFor { !peer.microphoneActive } }
        assertEquals(1, peer.privacyStopCalls)
        awaitClosed(peer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }
        assertEquals(RealtimeCallPhase.ENDED, vm.ui.value.snapshot.phase)
        store.clear()
    }

    @Test fun repeatedStartDoesNotDuplicateActiveCallThenCanRestartAfterCleanup() = runBlocking {
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)
        val context = snapshot()
        vm.start(context, factory) { true }
        val firstPeer = factory.awaitPeer()
        awaitConnected(vm)

        vm.start(context, factory) { true }
        delay(30)
        assertEquals(1, factory.peers.size)
        assertEquals(1, backend.createCalls.get())

        vm.stop("user_ended")
        assertFalse(firstPeer.microphoneActive)
        awaitClosed(firstPeer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }

        vm.start(context, factory) { true }
        val secondPeer = factory.awaitPeer(index = 1)
        awaitConnected(vm)
        assertEquals(2, factory.peers.size)
        assertTrue(secondPeer.microphoneActive)
        assertEquals(2, backend.createCalls.get())
        vm.stop("user_ended")
        assertFalse(secondPeer.microphoneActive)
        awaitClosed(secondPeer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 2 } }
        store.clear()
    }

    @Test fun viewModelClearSynchronouslyStopsCaptureAndEventuallyEndsCall() = runBlocking {
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        awaitConnected(vm)

        store.clear() // AndroidViewModel.onCleared() owns the synchronous privacy stop.
        assertFalse(peer.microphoneActive)
        assertEquals(1, peer.privacyStopCalls)
        awaitClosed(peer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }
    }

    @Test fun peerDisconnectTriggersImmediatePrivacyStopAndServerEnd() = runBlocking {
        val backend = FakeBrokerBackend()
        val factory = FakePeerFactory()
        val vm = createViewModel(backend)
        vm.start(snapshot(), factory) { true }
        val peer = factory.awaitPeer()
        awaitConnected(vm)

        peer.callbacks.onPeerState(RealtimePeerLinkState.DISCONNECTED)
        assertFalse("A disconnected peer cannot keep its microphone open", peer.microphoneActive)
        assertEquals(1, peer.privacyStopCalls)
        awaitClosed(peer)
        withTimeout(TEST_TIMEOUT) { waitFor { backend.endCalls.get() >= 1 } }
        assertEquals(RealtimeCallPhase.ENDED, vm.ui.value.snapshot.phase)
        store.clear()
    }

    private fun createViewModel(backend: FakeBrokerBackend): RealtimeCall099ViewModel {
        val vm = RealtimeCall099ViewModel(
            app,
            MemoryVoiceStore(app, RealtimeBrokerConfig.create(SERVICE_URL, SERVICE_TOKEN)),
            RealtimeBrokerClient(openConnection = backend::open),
        )
        store.put("call-${viewModelId++}", vm)
        return vm
    }

    private fun snapshot() = RealtimeScope099Snapshot(
        conversationId = null,
        connection = AiConnection(),
        context = RealtimeSessionContext(),
    )

    private suspend fun awaitConnected(vm: RealtimeCall099ViewModel) {
        withTimeout(TEST_TIMEOUT) { vm.ui.first { it.snapshot.isConnected } }
    }

    private suspend fun awaitClosed(peer: FakePeer) {
        withTimeout(TEST_TIMEOUT) { waitFor { peer.closed } }
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        while (!predicate()) delay(10)
    }

    private class MemoryVoiceStore(context: Context, private val config: RealtimeBrokerConfig) : RealtimeBrokerSettingsStore(context) {
        override fun read(): RealtimeBrokerConfig = config
        override fun write(serviceUrl: String, serviceToken: String) = Unit
        override fun clear() = Unit
    }

    private class StageGate(val stage: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FakePeerFactory(
        private val gate: StageGate? = null,
        private val permissionRevokedBeforeMedia: Boolean = false,
    ) : RealtimePeerConnectionFactory {
        val peers = mutableListOf<FakePeer>()
        private val created = mutableListOf<CompletableDeferred<FakePeer>>()

        @Synchronized override fun create(callbacks: RealtimePeerCallbacks): RealtimePeerConnection {
            val peer = FakePeer(callbacks, gate, permissionRevokedBeforeMedia)
            peers += peer
            created += CompletableDeferred(peer)
            return peer
        }

        suspend fun awaitPeer(index: Int = 0): FakePeer = withTimeout(TEST_TIMEOUT) {
            while (synchronized(this@FakePeerFactory) { created.size <= index }) delay(5)
            val deferred = synchronized(this@FakePeerFactory) { created[index] }
            deferred.await()
        }
    }

    private class FakePeer(
        val callbacks: RealtimePeerCallbacks,
        private val gate: StageGate?,
        private val permissionRevokedBeforeMedia: Boolean,
    ) : RealtimePeerConnection {
        @Volatile var microphoneActive = false
            private set
        @Volatile var closed = false
            private set
        @Volatile var privacyStopCalls = 0
            private set
        @Volatile var remoteDescriptionCalls = 0
            private set
        private val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"

        private suspend fun checkpoint(stage: String) {
            if (gate?.stage == stage) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
        }

        override suspend fun addMicrophoneTrack() {
            if (permissionRevokedBeforeMedia) throw RealtimeTransportException("microphone_permission_required")
            microphoneActive = true
            checkpoint("addMicrophoneTrack")
        }
        override suspend fun createDataChannel(label: String) {
            check(label == "oai-events")
            callbacks.onDataChannelState(RealtimeDataChannelState.OPEN)
            checkpoint("createDataChannel")
        }
        override suspend fun createOffer(): String { checkpoint("createOffer"); return sdp }
        override suspend fun setLocalDescription(offerSdp: String) { check(offerSdp == sdp); checkpoint("setLocalDescription") }
        override suspend fun awaitIceGatheringComplete(timeoutMillis: Long): String {
            callbacks.onPeerState(RealtimePeerLinkState.CONNECTED)
            callbacks.onRemoteAudioTrackReceived()
            checkpoint("awaitIceGatheringComplete")
            return sdp
        }
        override suspend fun setRemoteDescription(answerSdp: String) {
            remoteDescriptionCalls++
            check(answerSdp == sdp)
            checkpoint("setRemoteDescription")
            callbacks.onDataChannelText("""{"type":"session.created","session":{"id":"fake-session"}}""")
        }
        override fun setMicrophoneMuted(muted: Boolean): Boolean {
            if (closed) return false
            microphoneActive = !muted
            return true
        }
        override fun privacyStopImmediately() {
            privacyStopCalls++
            microphoneActive = false
        }
        override fun sendDataChannelMessage(eventJson: String): Boolean = !closed
        override suspend fun close() { microphoneActive = false; closed = true }
    }

    private class BlockingGate {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun await() {
            entered.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Fake broker gate timed out" }
        }
        fun release() { released.countDown() }
    }

    private class FakeBrokerBackend(private val createGate: BlockingGate? = null) {
        val createCalls = AtomicInteger()
        val endCalls = AtomicInteger()

        fun open(url: URL): HttpURLConnection = FakeConnection(url, this)

        private fun reply(url: URL, requestBody: String): Pair<Int, ByteArray> = when (url.path) {
            "/v1/realtime/calls" -> {
                createCalls.incrementAndGet()
                createGate?.await()
                val root = Json.parseToJsonElement(requestBody).jsonObject
                val requestId = root["requestId"]!!.jsonPrimitive.content
                200 to """{"answerSdp":"v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n","callReference":"$requestId"}""".toByteArray()
            }
            "/v1/realtime/calls/end" -> {
                endCalls.incrementAndGet()
                200 to """{"ok":true}""".toByteArray()
            }
            else -> 404 to """{"error":"not_found"}""".toByteArray()
        }

        private inner class FakeConnection(url: URL, backend: FakeBrokerBackend) : HttpURLConnection(url) {
            private val body = ByteArrayOutputStream()
            @Volatile private var result: Pair<Int, ByteArray>? = null
            private val owner = backend
            override fun connect() = Unit
            override fun disconnect() {
                if (url.path == "/v1/realtime/calls") owner.createGate?.release()
            }
            override fun usingProxy(): Boolean = false
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (result == null) result = owner.reply(url, body.toString(Charsets.UTF_8))
                return result!!.first
            }
            override fun getInputStream(): InputStream {
                if (result == null) getResponseCode()
                return ByteArrayInputStream(result!!.second)
            }
            override fun getErrorStream(): InputStream? = null
        }
    }

    companion object {
        private const val TEST_TIMEOUT = 10_000L
        private const val TEST_PIN = "482619"
        private const val SERVICE_URL = "https://voice.example.test"
        private const val SERVICE_TOKEN = "synthetic-mebot-service-token-for-tests-0001"
    }
}
