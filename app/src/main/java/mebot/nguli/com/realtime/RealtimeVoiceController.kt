package mebot.nguli.com.realtime

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable

enum class RealtimeCallPhase {
    IDLE, PREPARING_MEDIA, GATHERING_ICE, REQUESTING_SESSION, EXCHANGING_SDP,
    WAITING_FOR_READY, CONNECTED, DISCONNECTED, FAILED, ENDING, ENDED,
}

enum class RealtimePeerLinkState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }
enum class RealtimeDataChannelState { CONNECTING, OPEN, CLOSING, CLOSED }

data class RealtimeReadiness(
    val iceGatheringComplete: Boolean = false,
    val peerConnected: Boolean = false,
    val dataChannelOpen: Boolean = false,
    val remoteAudioTrackReceived: Boolean = false,
    val sessionCreated: Boolean = false,
    val failureReason: String? = null,
) {
    /** No call is live until final ICE, the peer, both media directions, channel, and session are ready. */
    val isReady: Boolean get() = failureReason == null && iceGatheringComplete && peerConnected &&
        dataChannelOpen && remoteAudioTrackReceived && sessionCreated
}

data class RealtimeCallSnapshot(
    val phase: RealtimeCallPhase = RealtimeCallPhase.IDLE,
    val readiness: RealtimeReadiness = RealtimeReadiness(),
    val reasonCode: String? = null,
    val microphoneMuted: Boolean = false,
) {
    val isConnected: Boolean get() = phase == RealtimeCallPhase.CONNECTED && readiness.isReady
}

/** Callbacks are invoked by the platform WebRTC adapter, possibly off the main thread. */
interface RealtimePeerCallbacks {
    fun onPeerState(state: RealtimePeerLinkState)
    fun onDataChannelState(state: RealtimeDataChannelState)
    /** Call only after an actual remote audio receiver/track is attached and unmuted. */
    fun onRemoteAudioTrackReceived()
    fun onDataChannelText(message: String)
}

/**
 * Adapter boundary for platform WebRTC. Implementations add microphone audio and create the
 * `oai-events` channel before the offer, gather all ICE before signaling, and deterministically
 * close/release the track and peer. No fake adapter is installed in production.
 */
interface RealtimePeerConnection {
    suspend fun addMicrophoneTrack()
    suspend fun createDataChannel(label: String)
    suspend fun createOffer(): String
    suspend fun setLocalDescription(offerSdp: String)
    suspend fun awaitIceGatheringComplete(timeoutMillis: Long): String
    suspend fun setRemoteDescription(answerSdp: String)
    fun setMicrophoneMuted(muted: Boolean): Boolean
    /** Synchronous local privacy stop; must not wait for broker/network cleanup. */
    fun privacyStopImmediately()
    fun sendDataChannelMessage(eventJson: String): Boolean
    suspend fun close()
}

fun interface RealtimePeerConnectionFactory {
    fun create(callbacks: RealtimePeerCallbacks): RealtimePeerConnection
}

/**
 * Official Realtime GA WebRTC controller. The host must call [privacyStop] synchronously on
 * lock/background/account/context/target/language changes before asynchronous UI teardown.
 */
class RealtimeVoiceController(
    private val broker: RealtimeBrokerClient,
    private val brokerConfig: RealtimeBrokerConfig,
    private val peerFactory: RealtimePeerConnectionFactory,
    /** Foreground UI-owned scope for timers and user-turn limits. Native cleanup has a separate safety scope. */
    private val callScope: CoroutineScope,
    private val iceGatherTimeoutMillis: Long = ICE_GATHER_TIMEOUT_MILLIS,
    private val readinessTimeoutMillis: Long = READINESS_TIMEOUT_MILLIS,
    private val maxCallDurationMillis: Long = MAX_CALL_DURATION_MILLIS,
    private val maxUserTurns: Int = MAX_USER_TURNS,
) {
    private val lock = Any()
    private var generation: Long = 0
    private var activePeer: RealtimePeerConnection? = null
    private var activeCallReference: RealtimeCallReference? = null
    private var durationLimitJob: Job? = null
    private var cleanupJob: Job? = null
    private var completedUserTurns: Int = 0
    private var readiness = MutableStateFlow(RealtimeReadiness())
    private var createdSession = CompletableDeferred<Unit>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RealtimeCallSnapshot())
    private val mutableEvents = MutableSharedFlow<RealtimeServerEvent>(extraBufferCapacity = 64)

    val state: StateFlow<RealtimeCallSnapshot> = mutableState.asStateFlow()
    val events: SharedFlow<RealtimeServerEvent> = mutableEvents.asSharedFlow()

    init {
        require(iceGatherTimeoutMillis in 1..MAX_ICE_GATHER_TIMEOUT_MILLIS)
        require(readinessTimeoutMillis in 1..MAX_READINESS_TIMEOUT_MILLIS)
        require(maxCallDurationMillis in 1..MAX_CALL_DURATION_MILLIS)
        require(maxUserTurns in 1..MAX_USER_TURNS)
    }

    /** User explicitly starts after the host grants microphone permission. */
    suspend fun connect(
        context: RealtimeSessionContext,
        /** Synchronous host snapshot check, re-run immediately before the SDP dispatch boundary. */
        expectedCurrent: () -> Boolean = { true },
    ) {
        ensureHostCurrent(expectedCurrent)
        val callGeneration = synchronized(lock) {
            if (mutableState.value.phase !in setOf(RealtimeCallPhase.IDLE, RealtimeCallPhase.ENDED))
                throw RealtimeTransportException("call_already_active")
            generation += 1
            activeCallReference = RealtimeCallReference(UUID.randomUUID().toString())
            activePeer = null
            durationLimitJob = null
            completedUserTurns = 0
            generation
        }
        readiness = MutableStateFlow(RealtimeReadiness())
        createdSession = CompletableDeferred()
        mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.PREPARING_MEDIA)
        var peer: RealtimePeerConnection? = null
        val reference = synchronized(lock) { activeCallReference } ?: throw RealtimeTransportException("temporary_error")
        try {
            ensureCallCurrent(callGeneration, expectedCurrent)
            peer = peerFactory.create(callbacks(callGeneration))
            synchronized(lock) {
                checkGenerationLocked(callGeneration)
                activePeer = peer
            }
            peer.addMicrophoneTrack()
            ensureCallCurrent(callGeneration, expectedCurrent)
            peer.createDataChannel(OPENAI_DATA_CHANNEL_LABEL)
            ensureCallCurrent(callGeneration, expectedCurrent)
            val offer = peer.createOffer()
            validateSdp(offer, "invalid_sdp")
            peer.setLocalDescription(offer)
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.GATHERING_ICE)
            val finalOffer = peer.awaitIceGatheringComplete(iceGatherTimeoutMillis)
            ensureCallCurrent(callGeneration, expectedCurrent)
            validateSdp(finalOffer, "invalid_sdp")
            if (finalOffer.length > MAX_SDP_CHARACTERS) throw RealtimeTransportException("invalid_sdp")
            update(callGeneration) { it.copy(iceGatheringComplete = true) }

            ensureCallCurrent(callGeneration, expectedCurrent)
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.REQUESTING_SESSION)
            val handshake = broker.createRealtimeCall(brokerConfig, reference.value, context, finalOffer)
            // In case host context changed while broker was creating the session, immediately
            // stop the server-owned call and do not apply its answer to the stale peer.
            try {
                ensureCallCurrent(callGeneration, expectedCurrent)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { broker.endRealtimeCall(brokerConfig, handshake.callReference) }
                throw cancelled
            }
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.EXCHANGING_SDP,
                readiness = readiness.value)
            peer.setRemoteDescription(handshake.answerSdp)
            synchronized(lock) {
                checkGenerationLocked(callGeneration)
                activeCallReference = handshake.callReference
            }
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.WAITING_FOR_READY,
                readiness = readiness.value)
            val ready = withTimeout(readinessTimeoutMillis) {
                readiness.first { it.isReady || it.failureReason != null }
            }
            if (!ready.isReady) throw RealtimeTransportException(ready.failureReason ?: "peer_disconnected")
            ensureCallCurrent(callGeneration, expectedCurrent)
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.CONNECTED, readiness = ready)
            val timer = callScope.launch {
                delay(maxCallDurationMillis)
                privacyStop("call_duration_limit")
            }
            synchronized(lock) {
                if (callGeneration == generation) durationLimitJob = timer else timer.cancel()
            }
        } catch (cancelled: CancellationException) {
            failAndCleanup(callGeneration, peer, reference, cancelled.message ?: "call_cancelled", cancelled = true)
            throw cancelled
        } catch (error: RealtimeTransportException) {
            failAndCleanup(callGeneration, peer, reference, error.reasonCode, cancelled = false)
            throw error
        } catch (_: Exception) {
            val error = RealtimeTransportException("temporary_error")
            failAndCleanup(callGeneration, peer, reference, error.reasonCode, cancelled = false)
            throw error
        }
    }

    /** Retry is an explicit new server call after prior peer and broker resources have ended. */
    suspend fun reconnect(context: RealtimeSessionContext, expectedCurrent: () -> Boolean = { true }) {
        end()
        connect(context, expectedCurrent)
    }

    /**
     * Synchronous privacy boundary: invalidate generation and mute the native microphone before
     * scheduling broker hangup/peer disposal. Safe to call from lock/background/context observers.
     */
    fun privacyStop(reasonCode: String = "call_ended"): Job {
        val peer: RealtimePeerConnection?
        val reference: RealtimeCallReference?
        val timer: Job?
        synchronized(lock) {
            cleanupJob?.takeIf { it.isActive }?.let { return it }
            if (mutableState.value.phase in setOf(RealtimeCallPhase.IDLE, RealtimeCallPhase.ENDED) &&
                activePeer == null && activeCallReference == null) {
                mutableState.value = mutableState.value.copy(phase = RealtimeCallPhase.ENDED, reasonCode = reasonCode)
                return cleanupScope.launch { }
            }
            generation += 1
            peer = activePeer.also { activePeer = null }
            reference = activeCallReference.also { activeCallReference = null }
            timer = durationLimitJob.also { durationLimitJob = null }
            // Synchronous and local: this is intentionally done before any network suspension.
            runCatching { peer?.privacyStopImmediately() }
            mutableState.value = RealtimeCallSnapshot(phase = RealtimeCallPhase.ENDING,
                readiness = readiness.value, reasonCode = reasonCode, microphoneMuted = true)
            cleanupJob = cleanupScope.launch {
                try {
                    runCatching { peer?.sendDataChannelMessage(RealtimeClientEvents.cancelResponse()) }
                    runCatching { peer?.sendDataChannelMessage(RealtimeClientEvents.clearOutputAudio()) }
                    // Always close microphone/media locally without waiting for network hangup.
                    if (peer != null) closePeerBounded(peer)
                    if (reference != null) withTimeoutOrNull(BROKER_END_TIMEOUT_MILLIS) {
                        runCatching { broker.endRealtimeCall(brokerConfig, reference) }
                    }
                } finally {
                    mutableState.value = RealtimeCallSnapshot(RealtimeCallPhase.ENDED,
                        readiness = RealtimeReadiness(), reasonCode = reasonCode, microphoneMuted = true)
                }
            }
        }
        timer?.cancel()
        return synchronized(lock) { cleanupJob ?: cleanupScope.launch { } }
    }

    /** Suspendable host stop; cleanup is performed under NonCancellable even if caller is cancelled. */
    suspend fun end(reasonCode: String? = null) {
        val cleanup = privacyStop(reasonCode ?: "call_ended")
        withContext(NonCancellable) { cleanup.join() }
    }

    /** Must be called synchronously before backgrounding or locking the device. */
    fun onHostBackgroundOrLock(): Job = privacyStop("host_background_or_lock")

    /** Personal/context changes invalidate current scoped context before any next request. */
    fun onContextChanged(): Job = privacyStop("context_changed")

    /** Stop remote recording without ending a healthy call. False means no active track accepted the request. */
    fun setMicrophoneMuted(muted: Boolean): Boolean = synchronized(lock) {
        if (mutableState.value.phase != RealtimeCallPhase.CONNECTED || !readiness.value.isReady) return false
        val peer = activePeer ?: return false
        if (!peer.setMicrophoneMuted(muted)) return false
        mutableState.value = mutableState.value.copy(microphoneMuted = muted)
        true
    }

    private fun callbacks(callGeneration: Long): RealtimePeerCallbacks = object : RealtimePeerCallbacks {
        override fun onPeerState(state: RealtimePeerLinkState) {
            var terminalReason: String? = null
            synchronized(lock) {
                if (callGeneration != generation) return
                val reason = when (state) {
                    RealtimePeerLinkState.FAILED -> "peer_failed"
                    RealtimePeerLinkState.DISCONNECTED, RealtimePeerLinkState.CLOSED -> "peer_disconnected"
                    else -> null
                }
                readiness.value = readiness.value.copy(
                    peerConnected = state == RealtimePeerLinkState.CONNECTED,
                    failureReason = reason,
                )
                val snapshot = mutableState.value
                if (reason != null) {
                    mutableState.value = snapshot.copy(phase = RealtimeCallPhase.DISCONNECTED,
                        readiness = readiness.value, reasonCode = reason)
                    if (snapshot.phase == RealtimeCallPhase.CONNECTED) terminalReason = reason
                } else if (snapshot.phase == RealtimeCallPhase.WAITING_FOR_READY) {
                    mutableState.value = snapshot.copy(readiness = readiness.value)
                }
            }
            terminalReason?.let(::privacyStop)
        }

        override fun onDataChannelState(state: RealtimeDataChannelState) {
            var terminalReason: String? = null
            synchronized(lock) {
                if (callGeneration != generation) return
                val reason = if (state == RealtimeDataChannelState.CLOSED) "data_channel_closed" else null
                readiness.value = readiness.value.copy(dataChannelOpen = state == RealtimeDataChannelState.OPEN,
                    failureReason = reason)
                val snapshot = mutableState.value
                if (reason != null) {
                    mutableState.value = snapshot.copy(phase = RealtimeCallPhase.DISCONNECTED,
                        readiness = readiness.value, reasonCode = reason)
                    if (snapshot.phase == RealtimeCallPhase.CONNECTED) terminalReason = reason
                } else if (snapshot.phase == RealtimeCallPhase.WAITING_FOR_READY) {
                    mutableState.value = snapshot.copy(readiness = readiness.value)
                }
            }
            terminalReason?.let(::privacyStop)
        }

        override fun onRemoteAudioTrackReceived() = update(callGeneration) {
            it.copy(remoteAudioTrackReceived = true, failureReason = null)
        }

        override fun onDataChannelText(message: String) {
            val event = RealtimeEventParser.parse(message) ?: return
            var terminalReason: String? = null
            var reachedTurnLimit = false
            synchronized(lock) {
                if (callGeneration != generation) return
                when (event) {
                    is RealtimeServerEvent.SessionCreated -> {
                        createdSession.complete(Unit)
                        readiness.value = readiness.value.copy(sessionCreated = true, failureReason = null)
                    }
                    is RealtimeServerEvent.Error -> {
                        readiness.value = readiness.value.copy(failureReason = event.code)
                        val snapshot = mutableState.value
                        val phase = if (snapshot.phase == RealtimeCallPhase.CONNECTED) RealtimeCallPhase.FAILED else snapshot.phase
                        mutableState.value = snapshot.copy(phase = phase, readiness = readiness.value, reasonCode = event.code)
                        if (snapshot.phase == RealtimeCallPhase.CONNECTED) terminalReason = event.code
                    }
                    RealtimeServerEvent.UserSpeechStopped -> if (mutableState.value.phase == RealtimeCallPhase.CONNECTED) {
                        completedUserTurns += 1
                        reachedTurnLimit = completedUserTurns >= maxUserTurns
                    }
                    else -> Unit
                }
                val snapshot = mutableState.value
                if (snapshot.phase == RealtimeCallPhase.WAITING_FOR_READY)
                    mutableState.value = snapshot.copy(readiness = readiness.value)
                // Emit under the generation lock so an old call can never leak an event to a new UI.
                mutableEvents.tryEmit(event)
            }
            terminalReason?.let(::privacyStop)
            if (reachedTurnLimit) privacyStop("call_turn_limit")
        }
    }

    private fun update(callGeneration: Long, transform: (RealtimeReadiness) -> RealtimeReadiness) {
        synchronized(lock) {
            if (callGeneration != generation) return
            readiness.value = transform(readiness.value)
            val current = mutableState.value
            if (current.phase == RealtimeCallPhase.WAITING_FOR_READY)
                mutableState.value = current.copy(readiness = readiness.value)
        }
    }

    private suspend fun failAndCleanup(
        callGeneration: Long,
        peer: RealtimePeerConnection?,
        reference: RealtimeCallReference,
        reason: String,
        cancelled: Boolean,
    ) {
        synchronized(lock) {
            if (callGeneration == generation) {
                generation += 1
                if (activePeer === peer) activePeer = null
                activeCallReference = null
                mutableState.value = RealtimeCallSnapshot(
                    phase = if (cancelled) RealtimeCallPhase.ENDED else RealtimeCallPhase.FAILED,
                    readiness = readiness.value,
                    reasonCode = sanitizeReason(reason),
                    microphoneMuted = true,
                )
            }
        }
        withContext(NonCancellable) {
            peer?.let { closePeerBounded(it) }
            withTimeoutOrNull(BROKER_END_TIMEOUT_MILLIS) {
                runCatching { broker.endRealtimeCall(brokerConfig, reference) }
            }
        }
    }

    private suspend fun closePeerBounded(peer: RealtimePeerConnection) {
        withContext(NonCancellable) {
            withTimeoutOrNull(PEER_CLOSE_TIMEOUT_MILLIS) { runCatching { peer.close() } }
        }
        synchronized(lock) { if (activePeer === peer) activePeer = null }
    }

    private fun ensureHostCurrent(expectedCurrent: () -> Boolean) {
        if (!runCatching(expectedCurrent).getOrDefault(false)) throw CancellationException("Stale voice context")
    }

    private fun ensureCallCurrent(callGeneration: Long, expectedCurrent: () -> Boolean) {
        synchronized(lock) { checkGenerationLocked(callGeneration) }
        ensureHostCurrent(expectedCurrent)
    }

    private fun checkGenerationLocked(callGeneration: Long) {
        if (callGeneration != generation) throw CancellationException("Realtime call ended")
    }

    private fun validateSdp(value: String, errorCode: String) {
        if (value.length > MAX_SDP_CHARACTERS || !value.startsWith("v=0") || !value.contains("m=audio"))
            throw RealtimeTransportException(errorCode)
    }

    private fun sanitizeReason(reason: String): String = reason.take(80).filter { it.isLetterOrDigit() || it in "_.-" }.ifBlank { "temporary_error" }

    private companion object {
        const val OPENAI_DATA_CHANNEL_LABEL = "oai-events"
        const val ICE_GATHER_TIMEOUT_MILLIS = 20_000L
        const val MAX_ICE_GATHER_TIMEOUT_MILLIS = 30_000L
        const val READINESS_TIMEOUT_MILLIS = 35_000L
        const val MAX_READINESS_TIMEOUT_MILLIS = 60_000L
        const val MAX_CALL_DURATION_MILLIS = 15 * 60 * 1000L
        const val MAX_USER_TURNS = 24
        const val MAX_SDP_CHARACTERS = 512_000
        const val PEER_CLOSE_TIMEOUT_MILLIS = 5_000L
        const val BROKER_END_TIMEOUT_MILLIS = 5_000L
    }
}
