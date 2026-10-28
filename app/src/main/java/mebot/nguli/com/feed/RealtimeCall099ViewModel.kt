package mebot.nguli.com.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.realtime.*
import mebot.nguli.com.ui.feed.RealtimeCall099UiState

/** Foreground-only ephemeral call state. No audio, transcript, context or credential persistence. */
internal class RealtimeCall099ViewModel internal constructor(
    application: Application,
    private val store: RealtimeBrokerSettingsStore,
    private val broker: RealtimeBrokerClient,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        RealtimeBrokerSettingsStore(application),
        RealtimeBrokerClient(),
    )

    private val app = application as MeBotApplication
    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableUi = MutableStateFlow(RealtimeCall099UiState())
    val ui = mutableUi.asStateFlow()
    private var controller: RealtimeVoiceController? = null
    private var startJob: Job? = null
    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var cleanupJob: Job? = null
    private var generation = 0L
    var boundScope: RealtimeScope099Snapshot? = null
        private set

    init {
        callScope.launch { app.appLock.state.collect { if (it.locked) stop("app_locked") } }
    }

    fun start(snapshot: RealtimeScope099Snapshot, factory: RealtimePeerConnectionFactory, expectedCurrent: () -> Boolean) {
        if (app.appLock.state.value.locked || !expectedCurrent() || startJob?.isActive == true ||
            mutableUi.value.snapshot.phase !in setOf(RealtimeCallPhase.IDLE, RealtimeCallPhase.ENDED,
                RealtimeCallPhase.FAILED, RealtimeCallPhase.DISCONNECTED)) return
        val epoch = ++generation
        boundScope = snapshot
        mutableUi.value = RealtimeCall099UiState(RealtimeCallSnapshot(phase = RealtimeCallPhase.PREPARING_MEDIA))
        startJob = callScope.launch {
            try {
                cleanupJob?.join()
                val config = withContext(Dispatchers.IO) { store.read() }
                    ?: throw RealtimeTransportException("service_not_configured")
                fun current() = epoch == generation && !app.appLock.state.value.locked && expectedCurrent()
                if (!current()) throw CancellationException()
                val active = RealtimeVoiceController(broker, config, factory, callScope)
                controller = active
                stateJob?.cancel()
                eventJob?.cancel()
                stateJob = callScope.launch {
                    active.state.collect { state -> if (epoch == generation) mutableUi.value = mutableUi.value.copy(snapshot = state, microphoneMuted = state.microphoneMuted) }
                }
                eventJob = callScope.launch {
                    active.events.collect { event -> if (epoch == generation && current()) {
                        mutableUi.value = mutableUi.value.copy(recentEvents = (mutableUi.value.recentEvents + event).takeLast(40))
                    } }
                }
                active.connect(snapshot.context, ::current)
            } catch (cancelled: CancellationException) {
                if (epoch == generation) mutableUi.value = mutableUi.value.copy(snapshot = RealtimeCallSnapshot(RealtimeCallPhase.ENDED))
                throw cancelled
            } catch (failure: RealtimeTransportException) {
                if (epoch == generation) mutableUi.value = mutableUi.value.copy(snapshot = RealtimeCallSnapshot(RealtimeCallPhase.FAILED, reasonCode = failure.reasonCode))
            } catch (_: Exception) {
                if (epoch == generation) mutableUi.value = mutableUi.value.copy(snapshot = RealtimeCallSnapshot(RealtimeCallPhase.FAILED, reasonCode = "temporary_error"))
            }
        }
    }

    /** Called synchronously on the UI thread at privacy/lifecycle/navigation boundaries. */
    fun stop(reason: String = "user_ended") {
        generation++
        boundScope = null
        val active = controller
        controller = null
        // Invalidate/mute/destroy local capture before cancelling or awaiting any network work.
        if (active != null) cleanupJob = active.privacyStop(reason)
        startJob?.cancel(); startJob = null
        stateJob?.cancel(); stateJob = null
        eventJob?.cancel(); eventJob = null
        mutableUi.value = RealtimeCall099UiState(RealtimeCallSnapshot(RealtimeCallPhase.ENDED, reasonCode = reason))
    }

    fun setMuted(muted: Boolean) { controller?.setMicrophoneMuted(muted) }

    override fun onCleared() {
        stop("host_destroyed")
        val cleanup = cleanupJob
        if (cleanup == null || cleanup.isCompleted) callScope.cancel()
        else cleanup.invokeOnCompletion { callScope.cancel() }
        super.onCleared()
    }
}
