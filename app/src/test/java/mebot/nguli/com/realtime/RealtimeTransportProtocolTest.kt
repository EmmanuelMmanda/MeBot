package mebot.nguli.com.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTransportProtocolTest {
    private val token = "voice-service-token-for-tests-123456789"
    private val config = RealtimeBrokerConfig.create("https://voice.example.org/", token)
    private val answerSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    private val requestId = "8bbd2cf4-becd-4c2a-a981-a610899ebed1"

    @Test fun brokerConfigAndSensitiveModelsNeverPrintCredentialsContextOrSdp() {
        assertEquals("https://voice.example.org", config.serviceUrl)
        assertFalse(config.toString().contains(token))
        assertThrows { RealtimeBrokerConfig.create("http://voice.example.org", token) }
        assertThrows { RealtimeBrokerConfig.create("https://voice.example.org/path?secret=1", token) }
        assertThrows { RealtimeBrokerConfig.create("https://voice.example.org", "short") }
        val context = RealtimeSessionContext(confirmedContext = listOf(
            RealtimeContextNote("about-you-global", "private profile text", confirmed = true, global = true),
        ))
        assertFalse(context.toString().contains("private profile text"))
        assertFalse(RealtimeCallHandshake(answerSdp, RealtimeCallReference(requestId)).toString().contains("m=audio"))
    }

    @Test fun generalScopeSendsOnlyConfirmedGlobalAboutYouNote() = runBlocking {
        val connection = FakeConnection("https://voice.example.org/v1/realtime/calls", 200) {
            callResponse(requestId)
        }
        val client = RealtimeBrokerClient(openConnection = { connection })
        client.createRealtimeCall(config, requestId, RealtimeSessionContext(confirmedContext = listOf(
            RealtimeContextNote("about-you-global", "Confirmed profile", "profile", confirmed = true, global = true),
            RealtimeContextNote("news-note", "Private current news interest", scopes = setOf("news"), confirmed = true),
            RealtimeContextNote("hidden", "Unconfirmed secret note", scopes = setOf("news"), confirmed = false),
        )), finalSdp())

        val body = connection.requestBytes.toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(body).jsonObject
        val notes = root["context"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, notes.size)
        assertEquals("about-you-global", notes.single()["id"]!!.jsonPrimitive.content)
        assertFalse(body.contains("Private current news interest"))
        assertFalse(body.contains("Unconfirmed secret note"))
        assertEquals("Bearer $token", connection.getRequestProperty("Authorization"))
        assertFalse(body.contains(token))
        assertFalse(body.contains("OPENAI_API_KEY"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("v=0", root["sdp"]!!.jsonPrimitive.content.take(3))
    }

    @Test fun selectedItemEvidenceAndModuleScopedContextStaySeparate() = runBlocking {
        val connection = FakeConnection("https://voice.example.org/v1/realtime/calls", 200) { callResponse(requestId) }
        val client = RealtimeBrokerClient(openConnection = { connection })
        val item = RealtimeItemEvidence("technology", "module-tech", "Release", "Release summary",
            "article", "feed", "publisher", "Official", "https://example.org/release", 100,
            citationReferences = listOf(RealtimeCitation("Official notes", "https://example.org/release")),
            furtherReading = listOf(RealtimeCitation("More context", "https://example.org/more")))
        client.createRealtimeCall(config, requestId, RealtimeSessionContext(
            moduleKind = "technology", moduleId = "module-tech", moduleTitle = "Technology", moduleQuery = "latest releases",
            confirmedContext = listOf(
                RealtimeContextNote("about-you-global", "I like practical details", "profile", confirmed = true, global = true),
                RealtimeContextNote("tech-note", "I maintain Python services", scopes = setOf("technology"), confirmed = true),
                RealtimeContextNote("school-note", "Scholarship need", scopes = setOf("scholarships"), confirmed = true),
            ), selectedItem = item, recentHistory = listOf(RealtimeTurn("user", "Explain the change"))), finalSdp())
        val body = connection.requestBytes.toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(body).jsonObject
        assertTrue(root["context"].toString().contains("I like practical details"))
        assertTrue(root["context"].toString().contains("I maintain Python services"))
        assertFalse(body.contains("Scholarship need"))
        assertEquals("Release", root["item"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(root["item"].toString().contains("Official notes"))
        assertTrue(root["item"].toString().contains("furtherReading"))
        assertTrue(root["history"].toString().contains("Explain the change"))
    }

    @Test fun oneFinalGatheredOfferUsesOnlyAuthenticatedBrokerAndOpaqueCallRefEndsIt() = runBlocking {
        val sequence = mutableListOf<String>()
        val calls = mutableListOf<FakeConnection>()
        val broker = RealtimeBrokerClient(openConnection = { url ->
            sequence += "http:${url.path}"
            FakeConnection(url.toString(), 200) {
                when (url.path) {
                    "/v1/realtime/calls" -> callResponseFrom(calls.last().requestBytes.toString(Charsets.UTF_8))
                    else -> "{}".toByteArray()
                }
            }.also(calls::add)
        })
        val peer = FakePeer(sequence)
        val controller = RealtimeVoiceController(broker, config, RealtimePeerConnectionFactory { callbacks ->
            peer.callbacks = callbacks
            peer
        }, callScope = this)

        controller.connect(RealtimeSessionContext())
        assertEquals(RealtimeCallPhase.CONNECTED, controller.state.value.phase)
        assertTrue(controller.state.value.isConnected)
        assertTrue(controller.state.value.readiness.iceGatheringComplete)
        assertTrue(sequence.indexOf("mic") < sequence.indexOf("channel:oai-events"))
        assertTrue(sequence.indexOf("channel:oai-events") < sequence.indexOf("offer"))
        assertTrue(sequence.indexOf("local-sdp") < sequence.indexOf("ice-complete"))
        assertTrue(sequence.indexOf("ice-complete") < sequence.indexOf("http:/v1/realtime/calls"))
        assertTrue(sequence.indexOf("http:/v1/realtime/calls") < sequence.indexOf("remote-sdp"))
        assertEquals(1, calls.count { it.url.path == "/v1/realtime/calls" })
        val callRequest = calls.first { it.url.path == "/v1/realtime/calls" }
        assertEquals("Bearer $token", callRequest.getRequestProperty("Authorization"))
        assertFalse(callRequest.url.host == "api.openai.com")
        assertTrue(callRequest.requestBytes.toString(Charsets.UTF_8).contains("a=candidate:1"))

        assertTrue(controller.setMicrophoneMuted(true))
        assertTrue(controller.state.value.microphoneMuted)
        assertTrue(controller.setMicrophoneMuted(false))
        controller.end()
        assertTrue(peer.messages.any { it.contains("response.cancel") })
        assertTrue(peer.messages.any { it.contains("output_audio_buffer.clear") })
        assertEquals(RealtimeCallPhase.ENDED, controller.state.value.phase)
        assertEquals(1, calls.count { it.url.path == "/v1/realtime/calls/end" })
        val endRequest = calls.first { it.url.path == "/v1/realtime/calls/end" }
        assertEquals("Bearer $token", endRequest.getRequestProperty("Authorization"))
        val endJson = Json.parseToJsonElement(endRequest.requestBytes.toString(Charsets.UTF_8)).jsonObject
        val endedReference = endJson["callReference"]!!.jsonPrimitive.content
        assertTrue(runCatching { UUID.fromString(endedReference) }.isSuccess)
        assertTrue(sequence.indexOf("close") < sequence.lastIndexOf("http:/v1/realtime/calls/end"))
    }

    @Test fun hostContextGuardCanCancelBeforeBrokerDispatchAndOldCallbacksCannotLeak() = runBlocking {
        val connections = mutableListOf<FakeConnection>()
        val broker = RealtimeBrokerClient(openConnection = { url ->
            FakeConnection(url.toString(), 200) {
                if (url.path == "/v1/realtime/calls") callResponseFrom(connections.last().requestBytes.toString(Charsets.UTF_8)) else "{}".toByteArray()
            }.also(connections::add)
        })
        var hostCurrent = true
        val peer = FakePeer(mutableListOf()).also { it.afterIceGathered = { hostCurrent = false } }
        val controller = RealtimeVoiceController(broker, config, RealtimePeerConnectionFactory { callbacks -> peer.callbacks = callbacks; peer }, this)
        var failure: Throwable? = null
        try { controller.connect(RealtimeSessionContext()) { hostCurrent } } catch (error: Throwable) { failure = error }
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(connections.none { it.url.path == "/v1/realtime/calls" })
        assertTrue(connections.any { it.url.path == "/v1/realtime/calls/end" }) // tombstone prevents an in-flight create race
        assertTrue(peer.closed)

        // A callback object retained by a closed peer must not emit into a later call/UI generation.
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = mutableListOf<RealtimeServerEvent>()
        val collector = collectorScope.launch { controller.events.collect { seen += it } }
        try {
            delay(50)
            val staleCallbacks = peer.callbacks
            controller.privacyStop("test")
            delay(50)
            seen.clear()
            staleCallbacks.onDataChannelText("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"stale","delta":"private"}""")
            delay(50)
            assertTrue(seen.isEmpty())
        } finally { collectorScope.cancel() }
    }

    @Test fun terminalPeerFailureImmediatelyClosesMicrophoneAndPeer() = runBlocking {
        val connections = mutableListOf<FakeConnection>()
        val broker = RealtimeBrokerClient(openConnection = { url -> FakeConnection(url.toString(), 200) {
            if (url.path == "/v1/realtime/calls") callResponseFrom(connections.last().requestBytes.toString(Charsets.UTF_8)) else "{}".toByteArray()
        }.also(connections::add) })
        val peer = FakePeer(mutableListOf())
        val controller = RealtimeVoiceController(broker, config, RealtimePeerConnectionFactory { callbacks -> peer.callbacks = callbacks; peer }, this)
        controller.connect(RealtimeSessionContext())
        peer.callbacks.onPeerState(RealtimePeerLinkState.FAILED)
        withTimeout(2_000) { controller.state.first { it.phase == RealtimeCallPhase.ENDED } }
        assertEquals("peer_failed", controller.state.value.reasonCode)
        assertTrue(peer.closed)
        assertTrue(peer.muted)
    }

    @Test fun endWhileWaitingForRealtimeReadinessImmediatelyStopsPeerAndNeverConnects() = runBlocking {
        val connections = mutableListOf<FakeConnection>()
        val broker = RealtimeBrokerClient(openConnection = { url -> FakeConnection(url.toString(), 200) {
            if (url.path == "/v1/realtime/calls") callResponseFrom(connections.last().requestBytes.toString(Charsets.UTF_8)) else "{}".toByteArray()
        }.also(connections::add) })
        val peer = FakePeer(mutableListOf())
        val enteredRemoteDescription = CompletableDeferred<Unit>()
        val releaseRemoteDescription = CompletableDeferred<Unit>()
        peer.beforeRemoteDescription = enteredRemoteDescription
        peer.remoteDescriptionGate = releaseRemoteDescription
        val controller = RealtimeVoiceController(broker, config, RealtimePeerConnectionFactory { callbacks ->
            peer.callbacks = callbacks
            peer
        }, this)
        val connecting = launch { runCatching { controller.connect(RealtimeSessionContext()) } }
        withTimeout(2_000) { enteredRemoteDescription.await() }
        val cleanup = controller.privacyStop("context_changed")
        assertTrue(peer.muted) // synchronous media stop before any network wait
        withTimeout(2_000) { cleanup.join() }
        assertEquals(RealtimeCallPhase.ENDED, controller.state.value.phase)
        releaseRemoteDescription.complete(Unit)
        withTimeout(2_000) { connecting.join() }
        assertFalse(controller.state.value.isConnected)
        assertTrue(peer.closed)
        assertTrue(connections.any { it.url.path == "/v1/realtime/calls/end" })
    }

    @Test fun explicitTranscriptionUploadsRawM4aAndReturnsEditableText() = runBlocking {
        val audio = byteArrayOf(0, 1, 2, 3, 4)
        val connection = FakeConnection("https://voice.example.org/v1/transcriptions", 200) { """{"text":"Editable words"}""".toByteArray() }
        val client = RealtimeBrokerClient(openConnection = { connection })
        assertEquals("Editable words", client.transcribeRecording(config, audio))
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer $token", connection.getRequestProperty("Authorization"))
        assertEquals("audio/mp4", connection.getRequestProperty("Content-Type"))
        assertTrue(connection.requestBytes.toByteArray().contentEquals(audio))
    }

    @Test fun readinessRequiresIcePeerChannelRemoteAudioAndRealtimeSession() {
        assertFalse(RealtimeReadiness().isReady)
        assertFalse(RealtimeReadiness(iceGatheringComplete = true, peerConnected = true, dataChannelOpen = true, remoteAudioTrackReceived = true).isReady)
        assertFalse(RealtimeReadiness(iceGatheringComplete = true, peerConnected = true, dataChannelOpen = true, sessionCreated = true).isReady)
        assertTrue(RealtimeReadiness(iceGatheringComplete = true, peerConnected = true, dataChannelOpen = true,
            remoteAudioTrackReceived = true, sessionCreated = true).isReady)
        assertFalse(RealtimeReadiness(iceGatheringComplete = true, peerConnected = true, dataChannelOpen = true,
            remoteAudioTrackReceived = true, sessionCreated = true, failureReason = "peer_failed").isReady)
    }

    @Test fun parserHandlesGaTranscriptsInterruptionCancelAndSanitizedErrors() {
        assertEquals(RealtimeServerEvent.UserSpeechStarted, RealtimeEventParser.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(RealtimeServerEvent.UserTranscriptDelta("item-1", "hello"), RealtimeEventParser.parse("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"item-1","delta":"hello"}"""))
        assertEquals(RealtimeServerEvent.AssistantTranscriptCompleted("item-2", "answer"), RealtimeEventParser.parse("""{"type":"response.output_audio_transcript.done","item_id":"item-2","transcript":"answer"}"""))
        assertEquals(RealtimeServerEvent.ResponseCancelled, RealtimeEventParser.parse("""{"type":"response.done","response":{"status":"cancelled"}}"""))
        assertEquals(RealtimeServerEvent.Error("invalid_value"), RealtimeEventParser.parse("""{"type":"error","error":{"code":"invalid_value","message":"do not surface this"}}"""))
        assertNull(RealtimeEventParser.parse("not-json"))
        assertNull(RealtimeEventParser.parse("""{"type":"response.output_audio.delta","delta":"base64-audio-is-not-text"}"""))
    }

    private fun finalSdp() = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=candidate:1 1 UDP 1 192.0.2.1 5000 typ host\r\n"
    private fun callResponse(id: String) = """{"answerSdp":${kotlinx.serialization.json.JsonPrimitive(answerSdp)},"callReference":"$id"}""".toByteArray()
    private fun callResponseFrom(request: String): ByteArray {
        val id = Json.parseToJsonElement(request).jsonObject["requestId"]!!.jsonPrimitive.content
        return callResponse(id)
    }
    private fun assertThrows(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid broker config") } catch (_: IllegalArgumentException) { }
    }

    private class FakeConnection(url: String, private val status: Int, private val response: () -> ByteArray) : HttpURLConnection(URL(url)) {
        val requestBytes = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream() = requestBytes
        override fun getInputStream() = ByteArrayInputStream(response())
        override fun getErrorStream() = if (status in 200..299) null else ByteArrayInputStream(response())
        override fun getResponseCode(): Int = status
        override fun getContentType(): String = "application/json"
    }

    private class FakePeer(private val sequence: MutableList<String>) : RealtimePeerConnection {
        lateinit var callbacks: RealtimePeerCallbacks
        var beforeRemoteDescription: CompletableDeferred<Unit>? = null
        var remoteDescriptionGate: CompletableDeferred<Unit>? = null
        val messages = mutableListOf<String>()
        var closed = false
        var muted = false
        var afterIceGathered: () -> Unit = {}
        override suspend fun addMicrophoneTrack() { sequence += "mic" }
        override suspend fun createDataChannel(label: String) { sequence += "channel:$label"; callbacks.onDataChannelState(RealtimeDataChannelState.OPEN) }
        override suspend fun createOffer(): String { sequence += "offer"; return "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
        override suspend fun setLocalDescription(offerSdp: String) { sequence += "local-sdp"; assertTrue(offerSdp.startsWith("v=0")) }
        override suspend fun awaitIceGatheringComplete(timeoutMillis: Long): String {
            sequence += "ice-complete"
            callbacks.onPeerState(RealtimePeerLinkState.CONNECTED)
            callbacks.onRemoteAudioTrackReceived()
            afterIceGathered()
            return "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=candidate:1 1 UDP 1 192.0.2.1 5000 typ host\r\n"
        }
        override suspend fun setRemoteDescription(answerSdp: String) {
            sequence += "remote-sdp"
            beforeRemoteDescription?.complete(Unit)
            remoteDescriptionGate?.await()
            callbacks.onDataChannelText("""{"type":"session.created","session":{"id":"sess_test"}}""")
        }
        override fun setMicrophoneMuted(muted: Boolean): Boolean { this.muted = muted; return true }
        override fun privacyStopImmediately() { muted = true }
        override fun sendDataChannelMessage(eventJson: String): Boolean { messages += eventJson; return true }
        override suspend fun close() { closed = true; sequence += "close" }
    }
}
