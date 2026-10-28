package mebot.nguli.com.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.realtime.*
import mebot.nguli.com.ui.feed.RealtimeSetup099UiState

/** Suspending seam for an explicit capability GET; tests can prove the request boundary without network. */
internal fun interface RealtimeCapabilityProbe099 {
    suspend fun getCapability(config: RealtimeBrokerConfig?): RealtimeServerCapability
}

/** Separate, explicit voice configuration. No automatic network check or provider replacement. */
class Realtime099ViewModel internal constructor(
    application: Application,
    private val store: RealtimeBrokerSettingsStore,
    private val capabilityProbe: RealtimeCapabilityProbe099,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        RealtimeBrokerSettingsStore(application),
        RealtimeCapabilityProbe099 { config -> RealtimeBrokerClient().getCapability(config) },
    )

    private val app = application as MeBotApplication
    private val brokerForTranscription = RealtimeBrokerClient()
    private val mutableUi = MutableStateFlow(RealtimeSetup099UiState(isSaving = true))
    val ui = mutableUi.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var resetJob: Job? = null
    private var resetting = false

    init {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { store.read() }
            mutableUi.value = RealtimeSetup099UiState(savedServiceUrl = config?.serviceUrl.orEmpty(), tokenConfigured = config != null)
        }
        viewModelScope.launch {
            app.appLock.state.collect { if (it.locked) cancelOperations() }
        }
    }

    fun save(serviceUrl: String, serviceToken: String?) {
        if (resetting || app.appLock.state.value.locked || operation?.isActive == true) return
        val epoch = ++generation
        mutableUi.value = mutableUi.value.copy(isSaving = true, errorCode = null, capability = null)
        operation = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val existing = store.read()
                    val normalized = serviceUrl.trim().trimEnd('/')
                    val token = serviceToken ?: existing?.takeIf { it.serviceUrl == normalized }?.serviceToken()
                        ?: throw IllegalArgumentException("A service token is required")
                    val validated = RealtimeBrokerConfig.create(normalized, token)
                    if (app.appLock.state.value.locked || epoch != generation) throw CancellationException()
                    store.write(validated.serviceUrl, validated.serviceToken())
                    validated.serviceUrl
                }
                if (epoch == generation) mutableUi.value = RealtimeSetup099UiState(savedServiceUrl = saved, tokenConfigured = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { if (epoch == generation) mutableUi.value = mutableUi.value.copy(errorCode = "invalid_input") }
            catch (_: Exception) { if (epoch == generation) mutableUi.value = mutableUi.value.copy(errorCode = "storage_error") }
            finally { if (epoch == generation) mutableUi.value = mutableUi.value.copy(isSaving = false) }
        }
    }

    /** User-triggered capability GET only: never creates a voice session or uploads audio. */
    fun checkCapability() {
        if (resetting || app.appLock.state.value.locked || operation?.isActive == true) return
        val epoch = ++generation
        // A displayed availability result is only current after this explicit recheck completes.
        mutableUi.value = mutableUi.value.copy(isChecking = true, capability = null, errorCode = null)
        operation = viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { store.read() }
                if (app.appLock.state.value.locked || epoch != generation) throw CancellationException()
                val capability = capabilityProbe.getCapability(config)
                if (epoch == generation && !app.appLock.state.value.locked) mutableUi.value = mutableUi.value.copy(capability = capability)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == generation) mutableUi.value = mutableUi.value.copy(capability = null, errorCode = "network_error") }
            finally { if (epoch == generation) mutableUi.value = mutableUi.value.copy(isChecking = false) }
        }
    }

    fun remove() {
        if (resetting || app.appLock.state.value.locked || operation?.isActive == true) return
        val epoch = ++generation
        mutableUi.value = mutableUi.value.copy(isSaving = true, errorCode = null, capability = null)
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                if (epoch == generation) mutableUi.value = RealtimeSetup099UiState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == generation) mutableUi.value = mutableUi.value.copy(errorCode = "storage_error") }
            finally { if (epoch == generation) mutableUi.value = mutableUi.value.copy(isSaving = false) }
        }
    }

    /** Full local erase is not blocked by an in-flight capability check. */
    fun clearForReset(onCleared: () -> Unit, onFailure: () -> Unit): Job {
        resetJob?.takeIf { it.isActive }?.let { return it }
        cancelOperations()
        resetting = true
        mutableUi.value = mutableUi.value.copy(isSaving = true, errorCode = null, capability = null)
        return viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.clear()
                    mebot.nguli.com.ui.feed.purgeWorkspaceVoiceAudio(getApplication())
                }
                // Privacy/lifecycle cancellation invalidates network operations, not an explicit erase.
                mutableUi.value = RealtimeSetup099UiState()
                onCleared()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableUi.value = mutableUi.value.copy(isSaving = false, errorCode = "storage_error")
                onFailure()
            } finally { resetting = false }
        }.also { resetJob = it }
    }

    /** Upload is possible only from the explicit recording consent action, never from settings. */
    fun transcriber(providerLabel: String, usageDisclosure: String, expectedCurrent: () -> Boolean): mebot.nguli.com.ui.feed.WorkspaceVoiceTranscriber? {
        if (mutableUi.value.isSaving || mutableUi.value.isChecking || mutableUi.value.capability?.transcription?.canTranscribe != true) return null
        val epoch = generation
        return mebot.nguli.com.ui.feed.WorkspaceVoiceTranscriber(providerLabel, usageDisclosure) { file ->
            fun current() = epoch == generation && !app.appLock.state.value.locked && expectedCurrent()
            if (!current()) throw CancellationException()
            val pair = withContext(Dispatchers.IO) {
                val directory = java.io.File(getApplication<Application>().cacheDir, "voice-captures").canonicalFile
                val safe = file.canonicalFile
                require(safe.parentFile == directory && safe.name.startsWith("voice-") && safe.extension == "m4a")
                require(safe.isFile && safe.length() in 1..(2L * 1024 * 1024))
                val config = store.read() ?: throw RealtimeTransportException("service_not_configured")
                config to safe.readBytes().also { require(it.size <= 2 * 1024 * 1024) }
            }
            if (!current()) throw CancellationException()
            val text = brokerForTranscription.transcribeRecording(pair.first, pair.second)
            if (!current()) throw CancellationException()
            text
        }
    }

    fun cancelOperations() {
        generation++
        operation?.cancel()
        operation = null
        mutableUi.value = mutableUi.value.copy(isSaving = resetting, isChecking = false)
    }
}
