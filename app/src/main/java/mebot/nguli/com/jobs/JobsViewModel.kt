package mebot.nguli.com.jobs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.feed.FeedFailure

data class JobsUiState(val state: JobsState = JobsState(), val loading: Boolean = true, val error: String? = null)
class JobsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MeBotApplication).jobsRepository
    private val mutable = MutableStateFlow(JobsUiState())
    val ui = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            repository.state.catch { mutable.update { it.copy(loading = false, error = "storage_error") } }
                .collect { data -> mutable.update { it.copy(state = data, loading = false) } }
        }
    }
    fun refresh() {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = repository.refresh()
                val partial = repository.current().failedSources.isNotEmpty()
                mutable.update { it.copy(loading = false, error = if (!updated) "cached" else if (partial) "partial" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, error = (failure as? FeedFailure)?.code ?: "provider_error") } }
        }
    }
    fun preferences(value: JobPreferences) = mutate {
        repository.preferences(value)
        JobsAlertScheduler.schedule(getApplication(), value)
    }
    fun save(id: String) = mutate { repository.save(id) }
    fun application(value: JobApplication) = mutate { repository.application(value) }
    private fun mutate(action: suspend () -> Unit) { viewModelScope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = "storage_error") } }
    } }
}
