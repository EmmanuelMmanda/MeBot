package mebot.nguli.com.jobs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.jobs.*

data class JobsUiState(val state: JobsState = JobsState(), val loading: Boolean = true, val error: String? = null, val exportBusy: Boolean = false, val exportResult: String = "", val ready: Boolean = false, val saving: Boolean = false, val refreshPhase: String = "idle")
class JobsViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = (application as MeBotApplication).jobsRepository
    private val mutable = MutableStateFlow(JobsUiState())
    private val changes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var queuedChanges = 0
    private val pendingBookmarks = mutableSetOf<String>()
    private var assistantPreparationPending = false
    val ui = mutable.asStateFlow()
    init {
        if (savedStateHandle.get<Boolean>("jobs.exportPending") == true) {
            savedStateHandle["jobs.exportPending"] = false
            mutable.update { it.copy(exportResult = "interrupted") }
        }
        viewModelScope.launch {
            for (action in changes) {
                mutable.update { it.copy(error = null) }
                try {
                    action()
                    val committed = repository.current()
                    mutable.update { it.copy(state = committed, ready = true) }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutable.update { it.copy(error = if (failure is JobsStateChangedException) "state_changed" else "storage_error") } }
                finally {
                    queuedChanges--
                    mutable.update { it.copy(saving = queuedChanges > 0) }
                }
            }
        }
        viewModelScope.launch {
            flow { emitAll(JobsRefreshScheduler.observe(getApplication())) }.catch {
                mutable.update { it.copy(loading = false, error = "storage_error") }
            }.collect { status ->
                mutable.update { previous -> previous.copy(
                    loading = if (!previous.ready) previous.loading else status.phase == JobsRefreshPhase.RUNNING,
                    refreshPhase = status.phase.name.lowercase(java.util.Locale.ROOT),
                    error = when (status.phase) {
                        JobsRefreshPhase.SUCCESS -> status.outcome.takeIf { it == "cached" || it == "partial" }
                        JobsRefreshPhase.FAILURE -> status.outcome.ifBlank { "provider_error" }
                        else -> previous.error
                    },
                ) }
            }
        }
        viewModelScope.launch {
            repository.state.catch { mutable.update { it.copy(loading = false, error = "storage_error") } }
                .collect { data ->
                    mutable.update { it.copy(state = data, ready = true, loading = it.refreshPhase == "running") }
                }
        }
    }
    fun refresh() {
        mutable.update { it.copy(error = null) }
        try { JobsRefreshScheduler.schedule(getApplication()) }
        catch (_: Exception) { mutable.update { it.copy(error = "storage_error") } }
    }
    fun cancelRefresh() { JobsRefreshScheduler.cancel(getApplication()) }
    fun exportPdf(uri: Uri, text: String) {
        if (mutable.value.exportBusy || text.isBlank()) return
        savedStateHandle["jobs.exportPending"] = true
        mutable.update { it.copy(exportBusy = true, exportResult = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = JobsAssistant.draftPdfBytes(getApplication(), text)
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                }
                savedStateHandle["jobs.exportPending"] = false
                mutable.update { it.copy(exportBusy = false, exportResult = "saved") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                savedStateHandle["jobs.exportPending"] = false
                mutable.update { it.copy(exportBusy = false, exportResult = if (failure is IllegalArgumentException) "unsupported" else "failed") }
            }
        }
    }
    fun clearExportResult() { mutable.update { it.copy(exportResult = "") } }
    fun preferences(value: JobPreferences, expectedScope: String = mutable.value.state.editingScope) {
        val generation = repository.generation()
        mutate {
            repository.preferences(value, generation, expectedScope) { committed -> JobsAlertScheduler.schedule(getApplication(), committed) }
        }
    }
    fun save(id: String, expectedScope: String = mutable.value.state.editingScope) {
        if (!pendingBookmarks.add(id)) return
        val desired = mutable.value.state.applications.none { it.jobId == id }
        val generation = repository.generation()
        mutate { try { repository.setSaved(id, desired, generation, expectedScope) } finally { pendingBookmarks.remove(id) } }
    }
    fun application(value: JobApplication, expectedScope: String = mutable.value.state.editingScope) {
        val generation = repository.generation()
        mutate { repository.application(value, generation, expectedScope) }
    }
    /** A one-shot handoff, never replayed after process death. The editable document is
     * acknowledged on disk before navigation or any externally transmitted AI request. */
    fun prepareAssistant(value: JobApplication?, expectedScope: String, onCommitted: () -> Unit) {
        if (assistantPreparationPending) return
        assistantPreparationPending = true
        val generation = repository.generation()
        mutate {
            try {
                if (value != null) repository.application(value, generation, expectedScope)
                val committed = repository.current()
                if (repository.generation() != generation || committed.editingScope != expectedScope)
                    throw JobsStateChangedException()
                mutable.update { it.copy(state = committed, ready = true) }
                onCommitted()
            } finally { assistantPreparationPending = false }
        }
    }

    private fun mutate(action: suspend () -> Unit) {
        queuedChanges++
        mutable.update { it.copy(error = null, saving = true) }
        changes.trySend(action)
    }
}
